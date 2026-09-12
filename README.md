# Flash Booking

Sistema de reserva de ingressos para eventos com capacidade limitada, operando em modelo
de **flash sale**: muitos clientes disputando poucos assentos, em janelas curtas de tempo,
com múltiplas instâncias da API rodando simultaneamente.

A propriedade que o sistema não pode perder é uma só: **nunca vender mais ingressos do que
a capacidade do evento** — independentemente de quantas instâncias existam.

> **Status:** completo para o escopo do desafio. Eventos e reservas funcionam de ponta a
> ponta contra um PostgreSQL real, com idempotência, cancelamento, **expiração automática**,
> **resiliência (timeout, retry, circuit breaker) na única dependência externa**, **eventos
> assíncronos via Kafka com Transactional Outbox e consumidor idempotente**, observabilidade
> (correlação ponta a ponta, métricas de negócio, sondas separadas) e **garantia de que não
> há oversell sob concorrência** — provada por 132 testes, incluindo 1.000 requisições
> simultâneas contra 100 lugares.

---

## Índice

| |                                                   | |
|---|---------------------------------------------------|--|
| [1. Descrição](#flash-booking) | [8. Kafka](#kafka)                                | [15. Expiração](#expiração) |
| [2. Arquitetura](#arquitetura) | [9. Kafka UI](#kafka-ui)                          | [16. Resiliência](#resiliência) |
| [3. Stack](#stack) | [10. Endpoints](#api)                             | [17. Escalabilidade](#escalabilidade) |
| [4. Requisitos](#pré-requisitos) | [11. Exemplos de requests](#api)                  | [18. Decisões arquiteturais](#decisões-arquiteturais) |
| [5. Como executar](#como-executar-a-aplicação) | [12. Testes](#como-testar)                        | [19. Trade-offs](#trade-offs) |
| [6. Docker Compose](#como-subir-a-infraestrutura) | [13. Concorrência](#-por-que-não-existe-oversell) | [20. Limitações conhecidas](#limitações-conhecidas) |
| [7. PostgreSQL](#banco-de-dados) | [14. Idempotência](#idempotência)                 | [21. setup.sh](#subida-automatizada-setupsh) | [22. Frontend](#frontend) | [23. Frontend Architecture](#frontend-architecture) |

---

## Arquitetura

MVC em camadas, com uma regra simples: **cada camada só conhece a de baixo**, e a decisão
que não pode falhar mora no banco.

```
     HTTP                     ┌──────────────────────────────────────────┐
  cliente ──▶ Controller ──▶  │ Service (regra de negócio, transação)    │
              (fino: valida,  │   ReservationService · ReservationTx     │
               delega,        │   EventService · ExpirationService       │
               escolhe HTTP)  └───────────────┬──────────────────────────┘
                                              │
                            ┌─────────────────┼──────────────────┐
                            ▼                 ▼                  ▼
                      Repository        SeatAllocator     NotificationGateway
                      (persistência)    (a decisão de     (borda externa:
                            │            vender é 1 SQL)   timeout/retry/CB)
                            ▼                 │                  ▲
                    ┌───────────────────────────────────┐        │
                    │ PostgreSQL — a fonte da verdade   │        │
                    │  events · event_inventory         │        │
                    │  reservations · outbox_messages   │        │
                    │  processed_events                 │        │
                    └───────────────┬───────────────────┘        │
                                    │ relay (SKIP LOCKED)        │
                                    ▼                            │
                                  Kafka ──▶ Consumer (idempotente)
```

| Camada | Responsabilidade | Não faz |
|---|---|---|
| `controller` | Valida o formato da entrada, delega, escolhe o status HTTP | Regra de negócio, tratamento de erro (há um handler global) |
| `service` | Regra de negócio e fronteira transacional | Conhecer HTTP |
| `repository` | Persistência — incluindo o SQL condicional cuja semântica *é* a solução de concorrência | Decidir regra |
| `messaging` | Outbox, relay, consumidor idempotente | Participar da decisão de vender |
| `notification` | A única dependência externa, com resiliência | Ser condição da venda |
| `observability` | Correlação e métricas de negócio | Reimplementar o que o Actuator já dá |

📖 Detalhamento completo: [ARCHITECTURE.md](ARCHITECTURE.md) ·

---

## 🔴 Por que não existe oversell

A pergunta central do sistema tem uma resposta de uma frase:

> **A aplicação nunca decide se há ingresso disponível.** Ela pede ao PostgreSQL que
> incremente o contador **apenas se o resultado couber na capacidade**, em um único comando
> atômico, e confere quantas linhas mudaram.

```sql
UPDATE event_inventory
   SET reserved_count = reserved_count + :quantity
 WHERE event_id = :eventId
   AND reserved_count + :quantity <= total_capacity
```

`1` linha afetada = vendido. `0` = esgotado. Não existe, em lugar nenhum do código, o padrão
que produz oversell — ler a disponibilidade, decidir em memória e gravar de volta.

Sob `READ COMMITTED`, quem perde a corrida pela linha bloqueia e, ao ser liberado, **reavalia
a condição contra o valor já commitado** — não contra o que havia lido. Por isso a correção
não depende de quantas instâncias da API existem.

Três camadas de defesa: o `UPDATE` condicional, a `CHECK` constraint como rede de segurança,
e testes de concorrência que incluem a **contraprova** — a mesma carga com a estratégia
ingênua, mostrando o oversell aparecer.

## Stack

| Camada | Escolha |
|---|---|
| Linguagem | Java 21 (LTS) |
| Framework | Spring Boot 4.1.1 (Spring MVC) |
| Build | Gradle Wrapper 9.7.1 (Kotlin DSL) |
| Persistência | Spring Data JPA / Hibernate + HikariCP |
| Validação | Bean Validation (Hibernate Validator) |
| Banco | PostgreSQL 16 |
| Migrations | Flyway |
| Infra local | Docker Compose |
| Mensageria | Apache Kafka 3.9 (KRaft) + Kafka UI |
| Resiliência | Resilience4j (timeout, retry, circuit breaker, fallback) |
| Testes | JUnit 5 + Mockito + Spring Boot Test + **Testcontainers** (PostgreSQL e Kafka) |
| Observabilidade | Spring Boot Actuator + Micrometer + log estruturado nativo |

Deliberadamente **ausentes**, com o motivo registrado em ADR: **Redis** (o ponto de
coordenação já é o PostgreSQL — [§ Escalabilidade](#escalabilidade)), **ShedLock**
(`SKIP LOCKED` resolve o agendamento distribuído), **Clean/Hexagonal, CQRS, Event Sourcing**
(indireção sem ganho em um domínio de dois agregados) e **projeção de disponibilidade**
(seria uma segunda fonte da verdade). Ver
[ADR-0005](docs/adr/0005-entrega-incremental-e-nao-objetivos.md).

## Pré-requisitos

- **JDK 21** — o build declara uma *toolchain* Java 21. Se a máquina não tiver esse JDK,
  o Gradle o provisiona automaticamente (plugin `foojay-resolver-convention`).
- **Docker** — necessário para o PostgreSQL local e para os testes de integração
  (Testcontainers). Não há substituto em memória: os testes rodam contra PostgreSQL de
  verdade, e o motivo está em [ARCHITECTURE.md](ARCHITECTURE.md#105-estratégia-de-testes-de-integração).
- **Node.js 20+ e npm** — somente para o frontend. O backend sobe sem eles
  (`./setup.sh --backend-only`).
- **O repositório do frontend clonado ao lado deste** — também só para o frontend; ver
  [Layout de diretórios](#layout-de-diretórios) logo abaixo.

Nada além disso precisa ser instalado à mão. A distribuição do Gradle, o JDK 21 da
toolchain, as dependências Java, as imagens Docker, o schema do banco (Flyway), o tópico do
Kafka e o `node_modules` do frontend são todos baixados ou criados na primeira execução —
que por isso é bem mais demorada que as seguintes e exige rede. Também **não é necessário
criar um `.env`**: o `docker-compose.yml` e o `application-local.yml` já trazem valores
default de desenvolvimento para tudo.

## Subida automatizada (setup.sh)

### Layout de diretórios

O frontend é um **repositório separado**, e o `setup.sh` o procura como *irmão* deste, em
`../flash-booking-front-end`:

```
Projetos/
├── flash-booking/              ← este repositório (backend)
└── flash-booking-front-end/    ← repositório do frontend
```

Em uma máquina nova, portanto, são dois clones lado a lado:

```bash
git clone https://github.com/allysonacs/flash-booking.git
git clone https://github.com/allysonacs/flash-booking-front-end.git
cd flash-booking
./setup.sh
```

Se o frontend estiver em outro lugar, aponte o caminho com `FRONTEND_DIR`; se não for
usá-lo, suba só o backend:

```bash
FRONTEND_DIR=/caminho/do/front ./setup.sh
./setup.sh --backend-only
```

O script confere esse diretório **antes** de qualquer outra dependência, justamente para que
"faltou clonar o frontend" apareça junto com o resto do que estiver faltando, e não depois.

### Subindo

Para subir **tudo de uma vez** — infraestrutura, backend e frontend — o script na raiz
primeiro verifica as dependências e só então inicia os serviços:

```bash
./setup.sh
```

Ele verifica o repositório do frontend, Docker (CLI e daemon), Docker Compose, Java (um JDK
de verdade: no macOS o `/usr/bin/java` existe mesmo sem nenhum instalado), o Gradle wrapper,
`curl`, o `docker-compose.yml` e as portas 5432, 9092, 8081, 8080 e 5173 — reportando tudo o
que estiver faltando de uma vez, com a instrução de correção ao lado, em vez de falhar no
primeiro item. Depois sobe o PostgreSQL e o Kafka esperando pelo *healthcheck* de cada um,
sobe a aplicação e espera o `/actuator/health` responder `UP`, e por fim delega a parte de
frontend ao `setup.sh` do projeto do frontend (que instala as dependências do npm e sobe o
dev server). No final, imprime as URLs.

Uma porta ocupada por um container ou pelo backend **deste projeto** é reaproveitada, não é
erro: rodar `./setup.sh` de novo com tudo no ar não duplica nada.

| Comando | O que faz |
|---|---|
| `./setup.sh` | Verifica tudo e sobe tudo. Ctrl+C encerra backend, frontend e containers |
| `./setup.sh --check` | Só a verificação de dependências; sai com código 1 se algo falta |
| `./setup.sh --backend-only` | Infraestrutura + backend, sem frontend |
| `./setup.sh --status` | Mostra o que está no ar |
| `./setup.sh --logs` | Acompanha os logs de backend e frontend juntos |
| `./setup.sh --stop` | Encerra backend, frontend e containers (volumes preservados) |
| `./setup.sh --fresh` | Apaga os volumes antes de subir — começa do zero |
| `./setup.sh --fast-expiration` | Sobe com `RESERVATION_TTL=25s`, para demonstrar a expiração ao vivo |

As portas podem ser sobrescritas por ambiente: `SERVER_PORT`, `FRONTEND_PORT`,
`POSTGRES_PORT`, `KAFKA_PORT`, `KAFKA_UI_PORT`. O diretório do frontend, quando estiver
fora do padrão, por `FRONTEND_DIR`.

PIDs e logs ficam em `.run/` (ignorado pelo Git). As seções abaixo descrevem os mesmos
passos manualmente — é o que o script executa.

## Como subir a infraestrutura

```bash
docker compose up -d          # PostgreSQL, Kafka (KRaft) e Kafka UI
docker compose ps             # aguarde o status "healthy"
```

O banco sobe com um *healthcheck* (`pg_isready`) e o Kafka com outro (listagem de tópicos);
ambos com volume nomeado, de modo que os dados sobrevivem a `docker compose stop`.

A **Kafka UI** fica em <http://localhost:8081> — é por lá que se confere, de fora da
aplicação, que o outbox realmente publicou: o tópico
`flash-booking.reservations.created`, suas 3 partições, as mensagens e o *lag* do grupo
`flash-booking.notification-dispatcher`.

Para começar do zero:

```bash
docker compose down -v
```

Credenciais padrão são de desenvolvimento e vivem no `docker-compose.yml` apenas como
valores default. Para sobrescrevê-las: `cp .env.example .env` e edite o `.env`.

## Como executar a aplicação

**Opção 1 — aplicação local, infraestrutura no Docker** (fluxo recomendado):

```bash
docker compose up -d postgres kafka kafka-ui
./gradlew bootRun
```

A aplicação cria o tópico na subida e começa a drenar o outbox a cada segundo. O envio de
notificações vem desligado no perfil local (não há serviço externo rodando na sua máquina);
para ligá-lo, use `NOTIFICATION_ENABLED=true` apontando `NOTIFICATION_BASE_URL` para um mock.

**Opção 2 — tudo no Docker**, com a aplicação esperando o banco ficar saudável:

```bash
docker compose --profile app up --build
```

Em ambos os casos:

```bash
curl -s localhost:8080/actuator/health
# {"groups":["liveness","readiness"],"status":"UP"}

# Sondas separadas: liveness NÃO inclui o banco (para não reiniciar a frota quando ele cai);
# readiness inclui o banco e, de propósito, NÃO inclui o Kafka — a venda não depende dele.
curl -s localhost:8080/actuator/health/readiness
curl -s localhost:8080/actuator/metrics/flashbooking.reservations.created
```

Toda resposta traz um `X-Correlation-Id`; se você enviar o seu, ele é preservado e aparece em
todos os logs daquela requisição — inclusive nos do consumidor Kafka, em outra instância:

```bash
curl -i -X POST localhost:8080/events/{id}/reservations \
  -H 'Content-Type: application/json' -H 'X-Correlation-Id: minha-venda-1' \
  -d '{"quantity": 2}'
```

## Como testar

```bash
./gradlew test     # apenas os testes (exige Docker em execução)
./gradlew build    # compila, empacota e roda os testes
```

São **132 testes** em quatro níveis, e nenhum deles existe para subir percentual de
cobertura: cada um fixa um comportamento que, se mudar, quebra o sistema.

| Nível | O que prova | Onde |
|---|---|---|
| **Unitário** (Mockito) | Decisões de orquestração: replay de idempotência, guarda do cancelamento, laço do job | `service/`, `scheduler/`, `controller/*ControllerTest` |
| **Slice HTTP** (`@WebMvcTest`) | Status, formato e contrato de erro, sem subir banco | `controller/` |
| **Integração** (Testcontainers) | O dado atravessa até o PostgreSQL — conferido por SQL, porque um `201` não prova gravação | `repository/`, `controller/*ApiIntegrationTest` |
| 🔴 **Concorrência** | A invariante sob disputa real, com threads e transações de verdade | `concurrency/`, `messaging/`, `notification/` |

Os cenários críticos cobertos:

| Cenário | Teste |
|---|---|
| **Flash sale: 100 lugares, 1.000 requisições** | `FlashSaleStressTest` |
| Oversell sob 100/200/1000 concorrentes | `ReservationConcurrencyIntegrationTest` |
| **Contraprova**: a estratégia ingênua **produz** oversell | `SeatAllocationStrategyIntegrationTest` |
| 50 requisições com a mesma chave de idempotência | `ReservationIdempotencyIntegrationTest` |
| Expiração, devolução de assentos, cancelada vs. expirada | `ReservationExpirationServiceIntegrationTest` |
| 20 varreduras simultâneas; cancelamento × expiração | `ReservationExpirationConcurrencyIntegrationTest` |
| Outbox: grava na transação, publica, retenta, não republica | `OutboxPublicationIntegrationTest` |
| Consumo, mensagem duplicada, falha temporária e persistente | `ReservationCreatedConsumerIntegrationTest`, `ConsumerFailureRetryIntegrationTest` |
| Timeout, retry, circuito abrindo e se recuperando, fallback | `NotificationResilienceIntegrationTest` |
| Correlação, métricas de negócio, Actuator sem endpoint perigoso | `observability/` |
| Índices do caminho crítico presentes no schema | `SchemaIndexTest` |

```bash
./gradlew test --tests "*FlashSale*" --tests "*Concurrency*" --tests "*SeatAllocation*"
```

Os testes de integração sobem PostgreSQL e Kafka efêmeros via **Testcontainers**, nas mesmas
imagens usadas pelo Compose, aplicam as migrations com Flyway e conferem as linhas gravadas
lendo-as de volta por SQL puro. Não há banco em memória: um substituto aceitaria SQL e
semântica de concorrência que o PostgreSQL recusaria — e é justamente a semântica que está
sob teste.

## Banco de dados

O schema é propriedade do **Flyway** — o Hibernate roda com `ddl-auto=validate` e apenas
confere, na subida, que o mapeamento corresponde ao schema migrado.

```
src/main/resources/db/migration/
├── V1__create_event_schema.sql              # events + event_inventory
├── V2__create_reservation_schema.sql        # reservations + índice único de idempotência
├── V3__create_outbox_and_processed_events.sql
├── V4__indexes_and_observability.sql        # índices do caminho crítico + correlation_id
└── V5__refresh_column_comments.sql
```

| Tabela | Papel |
|---|---|
| `events` | Metadados estáveis do evento |
| `event_inventory` | **Linha quente** do inventário (`reserved_count`), com a `CHECK` que torna oversell impossível mesmo com bug de aplicação |
| `reservations` | O compromisso do cliente; estados `PENDING → CONFIRMED / CANCELLED / EXPIRED` |
| `outbox_messages` | Eventos de integração gravados **na mesma transação** da venda |
| `processed_events` | Marcas de idempotência do consumidor (`message_id`, `consumer_group`) |

Não existe coluna de disponibilidade: ela é derivada de `total_capacity - reserved_count`.
O porquê está em [ADR-0008](docs/adr/0008-disponibilidade-derivada.md).

**Índices**, cada um ligado a uma consulta real (e cobertos por `SchemaIndexTest`):

| Índice | Serve a |
|---|---|
| `ux_reservations_event_idempotency_key` (único, parcial) | Idempotência decidida pelo banco |
| `ix_reservations_event_status` | Consultas por evento e a chave estrangeira — que o PostgreSQL **não** indexa sozinho |
| `ix_reservations_due` (parcial em `PENDING`) | A varredura de expiração, em todas as instâncias |
| `ix_outbox_messages_pending` (parcial) | O relay, a cada segundo |
| `ix_outbox_messages_published`, `ix_processed_events_processed_at` | A retenção periódica |

**Pool de conexões:** HikariCP, `maximum-pool-size` 20 por instância (configurável),
`connection-timeout` 3s — falhar rápido é melhor do que enfileirar quem já perdeu a corrida.

Para inspecionar o banco:

```bash
docker exec -it flash-booking-postgres psql -U flashbooking -d flashbooking
```

## Kafka

O Kafka carrega os **fatos já consumados** da venda para quem quiser reagir a eles. Ele
**não** participa da decisão de vender — essa distinção é o centro do desenho e está
detalhada em [ARCHITECTURE.md § 14](ARCHITECTURE.md).

| Item | Valor |
|---|---|
| Tópico | `flash-booking.reservations.created` |
| Chave | `eventId` (o show) — reservas do mesmo show caem na mesma partição e mantêm a ordem |
| Partições | 3 (configurável) — é o teto de paralelismo do consumo |
| Grupo | `flash-booking.notification-dispatcher` |
| Produtor | `acks=all`, produtor idempotente, publicação **com espera de confirmação** |
| Consumidor | Offset commitado **depois** do processamento; dedupe por `processed_events` |
| Entrega | **At-least-once** — e por isso o consumidor é idempotente |

O caminho completo:

```
POST /reservations ─▶ [ transação: reserva + evento no outbox + assentos ] ─▶ commit
                                          │
                      relay (@Scheduled, FOR UPDATE SKIP LOCKED, 1s)
                                          ▼
                        Kafka ─▶ consumidor ─▶ INSERT processed_events ─▶ efeito
```

**Se o Kafka cair, a venda continua.** O outbox acumula, a métrica
`flashbooking.outbox.pending` cresce, e tudo drena quando o broker volta. O broker não está
no `readiness` justamente para que uma falha de integração não tire a API do ar.

## Kafka UI

<http://localhost:8081> — é por onde se confere, de fora da aplicação, que o outbox
realmente publicou:

- o tópico e suas partições;
- as mensagens, com chave, headers (`message-id`, `correlation-id`) e payload;
- o **lag** do grupo `flash-booking.notification-dispatcher`, que é o sinal de consumo lento.

Para ver o fluxo inteiro funcionando: crie um evento, crie uma reserva e observe a mensagem
aparecer no tópico em cerca de um segundo.

## Configuração

Configuração externalizada e separada por perfil. **Nenhum segredo no código ou no
repositório** — os valores default existem apenas para desenvolvimento local.

| Arquivo | Papel |
|---|---|
| `application.yml` | O que não muda entre ambientes: JPA, Flyway, Actuator |
| `application-local.yml` | Perfil padrão: datasource e pool apontando para o Compose |
| `application-test.yml` | Testes: sem datasource — quem o fornece é o Testcontainers |

| Variável | Padrão | Descrição |
|---|---|---|
| `SERVER_PORT` | `8080` | Porta HTTP da aplicação |
| `DB_HOST` / `DB_PORT` | `localhost` / `5432` | Endereço do PostgreSQL |
| `POSTGRES_DB` | `flashbooking` | Nome do banco |
| `POSTGRES_USER` | `flashbooking` | Usuário do banco |
| `POSTGRES_PASSWORD` | `flashbooking` | Senha do banco |
| `DB_POOL_MAX_SIZE` | `20` | Tamanho máximo do pool HikariCP |
| `DB_POOL_MIN_IDLE` | `5` | Conexões ociosas mantidas |
| `RESERVATION_TTL` | `15m` | Prazo de validade de uma reserva pendente |
| `RESERVATION_EXPIRATION_ENABLED` | `true` | Liga a varredura que expira reservas vencidas |
| `RESERVATION_EXPIRATION_INTERVAL` | `PT30S` | Intervalo entre varreduras, contado a partir do fim da anterior |
| `RESERVATION_EXPIRATION_BATCH_SIZE` | `200` | Reservas reivindicadas por transação |
| `RESERVATION_EXPIRATION_MAX_BATCHES` | `10` | Teto de lotes por execução |
| `NOTIFICATION_ENABLED` | `true` | Liga o envio de notificações ao serviço externo |
| `NOTIFICATION_BASE_URL` | `http://localhost:9090` | Endereço do serviço de notificação |
| `NOTIFICATION_CONNECT_TIMEOUT` | `500ms` | Tempo máximo para abrir a conexão |
| `NOTIFICATION_READ_TIMEOUT` | `1s` | Tempo máximo de espera pela resposta |
| `NOTIFICATION_RETRY_MAX_ATTEMPTS` | `3` | Tentativas por notificação (1 + 2 repetições) |
| `NOTIFICATION_CB_FAILURE_RATE` | `50` | % de falhas que abre o circuito |
| `NOTIFICATION_CB_WAIT_OPEN` | `30s` | Tempo em `OPEN` antes de tentar `HALF_OPEN` |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Endereço do broker |
| `KAFKA_PORT` | `9092` | Porta do Kafka publicada no host |
| `KAFKA_UI_PORT` | `8081` | Porta da Kafka UI |
| `EVENTS_TOPIC` | `flash-booking.reservations.created` | Tópico dos eventos de reserva |
| `EVENTS_TOPIC_PARTITIONS` | `3` | Partições do tópico (teto de paralelismo do consumo) |
| `EVENTS_RELAY_ENABLED` | `true` | Liga o relay que drena o outbox |
| `EVENTS_RELAY_INTERVAL` | `PT1S` | Intervalo entre passadas do relay |
| `EVENTS_CONSUMER_GROUP` | `flash-booking.notification-dispatcher` | Grupo de consumo |
| `EVENTS_RETENTION_ENABLED` | `true` | Liga a limpeza do outbox e das marcas de idempotência |
| `EVENTS_RETENTION_PUBLISHED_FOR` | `7 days` | Retenção das mensagens já publicadas |
| `EVENTS_RETENTION_PROCESSED_FOR` | `14 days` | Retenção das marcas de idempotência (maior que a do tópico) |
| `LOG_STRUCTURED_FORMAT` | *(vazio)* | `ecs` para logs JSON em container; vazio = legível por humanos |
| `OUTBOX_GAUGE_INTERVAL` | `PT10S` | Frequência de amostragem da fila do outbox |
| `APP_LOG_LEVEL` | `INFO` | Nível de log da aplicação |
| `HIBERNATE_SQL_LOG_LEVEL` | `INFO` | Use `DEBUG` para ver o SQL emitido |

## Estrutura do projeto

```
flash-booking/
├── build.gradle.kts            # dependências e toolchain Java 21
├── settings.gradle.kts         # nome do projeto + provisionamento de JDK
├── docker-compose.yml          # PostgreSQL, Kafka e Kafka UI (+ aplicação, no profile "app")
├── Dockerfile                  # build multi-stage, runtime sem privilégios
├── .env.example                # variáveis do Compose
├── ARCHITECTURE.md             # arquitetura e estratégias planejadas
├── docs/adr/                   # Architecture Decision Records
└── src/
    ├── main/java/com/example/flashbooking/
    │   ├── FlashBookingApplication.java
    │   ├── config/             # beans de infraestrutura e propriedades tipadas
    │   ├── controller/         # EventController — somente HTTP
    │   ├── dto/request/        # CreateEventRequest (com Bean Validation)
    │   ├── dto/response/       # EventResponse
    │   ├── entity/             # Event, EventInventory, Reservation + enums de estado
    │   ├── exception/          # exceções de domínio + GlobalExceptionHandler
    │   ├── messaging/          # evento de integração, outbox, relay e consumidor idempotente
    │   ├── notification/       # a única dependência externa: gateway com timeout/retry/CB
    │   ├── repository/         # repositórios; o SQL condicional do inventário vive aqui
    │   ├── scheduler/          # varredura de expiração e relay do outbox
    │   └── service/            # EventService, ReservationService, ReservationTxService
    │       └── allocation/     # SeatAllocator + AtomicUpdateSeatAllocator
    ├── main/resources/
    │   ├── application.yml · application-local.yml · application-test.yml
    │   └── db/migration/       # V1 eventos + inventário · V2 reservas · V3 outbox + idempotência
    └── test/java/com/example/flashbooking/
        ├── support/             # base Testcontainers, disparador de threads, serviço externo de mentira
        ├── concurrency/         # 🔴 oversell, idempotência e a contraprova da estratégia
        ├── messaging/           # 🔴 outbox, publicação, duplicata e falha do consumidor
        ├── notification/        # 🔴 timeout, retry, circuito abrindo e se recuperando
        ├── scheduler/           # expiração agendada
        ├── service/             # unitários, com repositórios mockados
        ├── controller/          # slices HTTP (@WebMvcTest) e integração ponta a ponta
        ├── repository/          # persistência contra PostgreSQL real
        └── exception/           # contrato de erros
```

Pacotes ainda sem classes contêm apenas `package-info.java` documentando a responsabilidade
da camada. **Nenhuma classe vazia foi criada para preencher diretório.**

Os testes cobrem três níveis com propósitos distintos: o **unitário** verifica a regra com os
repositórios mockados; o **slice HTTP** verifica status, formato e tradução de erros sem
subir banco; o **de integração** prova que o dado atravessa até o PostgreSQL — e confere as
linhas por SQL, porque um 201 sozinho não prova gravação.

## API

| Método | Rota | Descrição | Estado |
|---|---|---|---|
| `POST` | `/events` | Cria evento com capacidade | ✅ |
| `GET` | `/events/{id}` | Consulta evento e disponibilidade | ✅ |
| `POST` | `/events/{id}/reservations` | Cria reserva (anti-oversell, idempotente) | ✅ |
| `GET` | `/reservations/{id}` | Consulta reserva | ✅ |
| `POST` | `/reservations/{id}/confirmation` | Confirma reserva dentro do prazo (não mexe no inventário) | ✅ |
| `DELETE` | `/reservations/{id}` | Cancela reserva e devolve assentos | ✅ |

### Swagger / OpenAPI

Com a aplicação no ar, a documentação interativa fica em:

| | URL |
|---|---|
| **Swagger UI** | http://localhost:8080/swagger-ui.html |
| Especificação OpenAPI (JSON) | http://localhost:8080/v3/api-docs |
| Especificação OpenAPI (YAML) | http://localhost:8080/v3/api-docs.yaml |

Todos os endpoints aparecem com parâmetros, header `Idempotency-Key`, corpos de exemplo e cada
resposta de erro possível (com o `code` correspondente), e podem ser chamados direto pelo
botão *Try it out*. A especificação é gerada a partir do código (springdoc-openapi): as
anotações ficam nas interfaces `EventApi` e `ReservationApi`, que os controllers implementam,
e o `OpenApiDocumentationTest` falha se algum endpoint deixar de ser documentado. Para
desligar em um ambiente, use `SWAGGER_ENABLED=false`.

### `POST /events` — cria um evento

```bash
curl -i -X POST localhost:8080/events \
  -H 'Content-Type: application/json' \
  -d '{"name": "Show de Rock", "capacity": 300}'
```

```http
HTTP/1.1 201 Created
Location: /events/7f000001-a093-12b9-81a0-9312c1270000
Content-Type: application/json
```

```json
{
  "id": "7f000001-a093-12b9-81a0-9312c1270000",
  "name": "Show de Rock",
  "status": "ON_SALE",
  "totalCapacity": 300,
  "reservedCount": 0,
  "availableCapacity": 300,
  "createdAt": "2026-09-12T00:44:34.998217Z"
}
```

| Campo | Regra |
|---|---|
| `name` | Obrigatório, não em branco, até 200 caracteres |
| `capacity` | Obrigatório, inteiro maior que zero |

### `GET /events/{id}` — consulta evento e disponibilidade

```bash
curl -s localhost:8080/events/7f000001-a093-12b9-81a0-9312c1270000
```

```json
{
  "id": "7f000001-a093-12b9-81a0-9312c1270000",
  "name": "Show de Rock",
  "status": "ON_SALE",
  "totalCapacity": 300,
  "reservedCount": 0,
  "availableCapacity": 300,
  "createdAt": "2026-09-12T00:44:34.998217Z"
}
```

`availableCapacity` é calculado (`totalCapacity - reservedCount`), nunca lido de uma coluna
— ver [ADR-0008](docs/adr/0008-disponibilidade-derivada.md).

### `POST /events/{id}/reservations` — reserva ingressos

```bash
curl -i -X POST localhost:8080/events/{eventId}/reservations \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: pedido-42' \
  -d '{"quantity": 2}'
```

```http
HTTP/1.1 201 Created
Location: /reservations/7f000001-a093-17ce-81a0-9327d8580001
```

```json
{
  "id": "7f000001-a093-17ce-81a0-9327d8580001",
  "eventId": "7f000001-a093-17ce-81a0-9327d71f0000",
  "quantity": 2,
  "status": "PENDING",
  "expiresAt": "2026-09-12T01:22:37.176565Z",
  "createdAt": "2026-09-12T01:07:37.173602Z"
}
```

| Campo | Regra |
|---|---|
| `quantity` | Obrigatório, inteiro maior que zero |
| `Idempotency-Key` (header) | Opcional, até 100 caracteres |

**Idempotência.** Repetir a requisição com a mesma chave devolve a reserva original e
responde `200 OK` — e não `201 Created`, porque nada foi criado desta vez. Repetir a chave
com um payload diferente responde `409`. A garantia é um índice único no banco, então
funciona com qualquer número de instâncias, inclusive para duas requisições que chegam no
mesmo instante ([ADR-0011](docs/adr/0011-idempotencia-por-constraint-unica.md)).

Sem assentos suficientes:

```json
{
  "title": "Operação não permitida",
  "status": 409,
  "detail": "Não há 1 assento(s) disponível(is) para o evento 7f000001-...",
  "instance": "/events/7f000001-.../reservations",
  "code": "INSUFFICIENT_CAPACITY",
  "timestamp": "2026-09-12T01:07:37.476253Z"
}
```

### `GET /reservations/{id}` — consulta a reserva

```json
{
  "id": "7f000001-a093-17ce-81a0-9327d8e10002",
  "eventId": "7f000001-a093-17ce-81a0-9327d71f0000",
  "quantity": 1,
  "status": "PENDING",
  "expiresAt": "2026-09-12T01:22:37.313211Z",
  "createdAt": "2026-09-12T01:07:37.311025Z"
}
```

Estados: `PENDING` → `CONFIRMED` | `CANCELLED` | `EXPIRED`. A reserva nasce `PENDING` e as
três transições estão implementadas. Passado o `expiresAt`, uma varredura agendada muda a
reserva para `EXPIRED` e devolve os ingressos à disponibilidade do evento — em todas as
instâncias ao mesmo tempo, sem que duas processem a mesma reserva (ver
[ARCHITECTURE.md §12.7](ARCHITECTURE.md)).

As três saem de `PENDING` por um `UPDATE` que carrega `status = 'PENDING'` no `WHERE`, o que
as torna mutuamente exclusivas: é o banco, e não a aplicação, que impede a mesma reserva de
ser cancelada e expirada ao mesmo tempo — e, com isso, de devolver o mesmo assento duas vezes.
**Só `CANCELLED` e `EXPIRED` devolvem assentos**; a confirmação não mexe no inventário, como
a seção seguinte explica.

### `POST /reservations/{id}/confirmation` — confirma a reserva

```bash
curl -i -X POST localhost:8080/reservations/{id}/confirmation    # 200 OK
```

```json
{
  "id": "7f000001-a093-17ce-81a0-9327d8e10002",
  "eventId": "7f000001-a093-17ce-81a0-9327d71f0000",
  "quantity": 1,
  "status": "CONFIRMED",
  "expiresAt": "2026-09-12T01:22:37.313211Z",
  "createdAt": "2026-09-12T01:07:37.311025Z"
}
```

Sem corpo: o cliente pede *a confirmação desta reserva*, e não escolhe um status de destino —
as transições de uma reserva não são um campo editável. Daí o sub-recurso em vez de um `PATCH`
de `status`.

**A disponibilidade do evento não muda.** Esta é a única transição que sai de `PENDING` sem
mexer no inventário: os assentos foram comprometidos na criação da reserva, e confirmar apenas
impede que voltem. Quem espera ver `availableCapacity` cair ao confirmar está contando o mesmo
assento duas vezes.

**A confirmação é idempotente**: repetir sobre uma reserva já confirmada responde `200` com a
mesma reserva, pelo mesmo critério do `DELETE` — o cliente pediu um estado, e esse estado é o
atual.

**Duas guardas, não uma.** O `UPDATE` exige `status = 'PENDING'` **e** `expires_at > now()`:

| Situação | Resposta |
|---|---|
| Pendente e dentro do prazo | `200 OK` com a reserva `CONFIRMED` |
| Já confirmada | `200 OK` (idempotente) |
| Prazo vencido, varredura ainda não passou | `409 RESERVATION_EXPIRED` |
| Já cancelada ou expirada | `409 INVALID_RESERVATION_STATE` |
| Reserva inexistente | `404 RESERVATION_NOT_FOUND` |

A guarda de prazo não é redundante com a varredura de expiração, e é a parte que merece
atenção em code review. Sem ela existiria esta janela: o prazo vence, a varredura ainda não
passou (ela roda em intervalo, não no instante do vencimento), a confirmação chega e é aceita.
A reserva deixa de ser `PENDING`, a varredura seguinte não a encontra mais — e os assentos
ficam vendidos para quem perdeu a hora. **O TTL só vale enquanto nenhuma transição puder
atravessá-lo.** O corte usa `now()` do próprio banco, não o relógio da aplicação: com várias
instâncias, é o único relógio comum a todas elas — o mesmo critério que a varredura usa.

`RESERVATION_EXPIRED` existe separado de `INVALID_RESERVATION_STATE` porque, nessa janela, a
reserva ainda está gravada como `PENDING`: responder "estado inválido: PENDING" seria uma
contradição, e "o prazo acabou" e "esta reserva foi cancelada" são histórias diferentes para
quem está comprando.

### `DELETE /reservations/{id}` — cancela e devolve os assentos

```bash
curl -i -X DELETE localhost:8080/reservations/{id}    # 204 No Content
```

Os assentos voltam para a disponibilidade do evento na mesma transação que muda o estado da
reserva. **O `DELETE` é idempotente**: repetir responde `204` e não devolve o assento duas
vezes — a devolução é guardada pela transição `PENDING → CANCELLED`, e só quem consegue
aplicá-la devolve. Cancelar uma reserva em estado incompatível — confirmada, expirada —
responde `409 INVALID_RESERVATION_STATE`.

### Erros

Toda falha responde em **RFC 7807** (`application/problem+json`) com um `code` estável —
programe contra ele, não contra o texto da mensagem.

```bash
curl -s -X POST localhost:8080/events \
  -H 'Content-Type: application/json' -d '{"name": "", "capacity": 0}'
```

```json
{
  "title": "Requisição inválida",
  "status": 400,
  "detail": "Um ou mais campos são inválidos",
  "instance": "/events",
  "code": "VALIDATION_ERROR",
  "timestamp": "2026-09-12T00:44:35.128675Z",
  "errors": [
    { "field": "capacity", "message": "capacity deve ser maior que zero" },
    { "field": "name", "message": "name é obrigatório" }
  ]
}
```

```json
{
  "title": "Recurso não encontrado",
  "status": 404,
  "detail": "Evento não encontrado: 6abd7aa7-011c-4469-bb1b-f2574cafbafe",
  "instance": "/events/6abd7aa7-011c-4469-bb1b-f2574cafbafe",
  "code": "EVENT_NOT_FOUND",
  "timestamp": "2026-09-12T00:44:35.205053Z"
}
```

| `code` | HTTP | Quando acontece |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Campo ausente ou fora das restrições (`errors[]` diz qual) |
| `MALFORMED_REQUEST` | 400 | Corpo ausente ou JSON inválido |
| `INVALID_PARAMETER` | 400 | Identificador de rota fora do formato UUID |
| `EVENT_NOT_FOUND` | 404 | Evento inexistente |
| `RESERVATION_NOT_FOUND` | 404 | Reserva inexistente |
| `RESOURCE_NOT_FOUND` | 404 | Rota inexistente |
| `INSUFFICIENT_CAPACITY` | 409 | Não há assentos suficientes para a reserva |
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | Chave de idempotência reusada com outro payload |
| `INVALID_RESERVATION_STATE` | 409 | A operação não vale para o estado atual da reserva |
| `RESERVATION_EXPIRED` | 409 | Confirmação depois do prazo, com a reserva ainda `PENDING` |
| `EVENT_NOT_OPEN_FOR_RESERVATION` | 409 | O evento não está aceitando reservas |
| `METHOD_NOT_ALLOWED` | 405 | Método HTTP não suportado pela rota |
| `NOT_ACCEPTABLE` | 406 | `Accept` incompatível com a representação disponível |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | `Content-Type` não suportado |
| `INTERNAL_ERROR` | 500 | Defeito da aplicação — registrado no log, sem detalhe na resposta |

Falhas de protocolo detectadas pelo Spring MVC antes do controller (rota, método, mídia)
respondem com o mesmo envelope das falhas de domínio — mesmo formato, mesmo `code`, mesmo
`timestamp`.

## Idempotência

Header `Idempotency-Key` opcional na criação da reserva, com `UNIQUE (event_id,
idempotency_key)` no banco. **A unicidade é garantida pelo PostgreSQL, nunca por uma
checagem prévia** — "consultar e então inserir" é exatamente a corrida que se quer evitar.

| Situação | Resposta |
|---|---|
| Chave nova | `201 Created` |
| Chave conhecida, mesmo payload | `200 OK` com a reserva original (replay, nada é criado) |
| Chave conhecida, payload diferente | `409 IDEMPOTENCY_KEY_CONFLICT` |
| **Duas requisições simultâneas com a mesma chave** | Uma cria, a outra recebe a mesma reserva |

O último caso é o interessante: as duas consultam, nada encontram e inserem. O PostgreSQL faz
a segunda **esperar no índice único** e só então lança a violação — quando ela é capturada, a
vencedora já está commitada e visível. O perdedor devolve a reserva da vencedora, e seu
rollback libera os assentos que havia comprometido.

Idempotência também existe onde não há header: cancelar duas vezes devolve o assento uma vez
só (`UPDATE ... WHERE status = 'PENDING'`), reprocessar a varredura de expiração não devolve
assento duas vezes, e o consumidor Kafka deduplica por `processed_events`.

📖 [ARCHITECTURE.md § 12.5](ARCHITECTURE.md) · [ADR-0011](docs/adr/0011-idempotencia-por-constraint-unica.md)

## Expiração

Uma reserva nasce `PENDING` com `expiresAt` (TTL de 15 min, configurável). Uma varredura
agendada roda **em todas as instâncias**, a cada 30s:

```sql
SELECT id FROM reservations
 WHERE status = 'PENDING' AND expires_at <= now()
 ORDER BY expires_at
 FOR UPDATE SKIP LOCKED
 LIMIT :batchSize
```

`SKIP LOCKED` faz cada instância levar um lote **disjunto**: sem eleição de líder, sem
ShedLock, sem Redis, sem ponto único de falha. A transição `PENDING → EXPIRED` e a devolução
dos assentos acontecem no **mesmo commit**.

**Cancelar × expirar:** as duas transições saem de `PENDING` e as duas são guardadas por ele,
então exatamente uma se aplica e o assento volta uma vez só. Vence quem travar a linha
primeiro; o perdedor recebe `409` (se a reserva expirou) ou sucesso (se ela já estava
cancelada) — de forma determinística, não conforme o instante da leitura.

**Confirmar × expirar:** a confirmação também parte de `PENDING`, mas carrega uma guarda a
mais — `expires_at > now()`. Ela existe porque a varredura roda em intervalo, e não no instante
do vencimento: sem essa condição, uma confirmação que chegasse nessa janela tiraria a reserva
de `PENDING`, a varredura nunca mais a encontraria, e o TTL teria sido atravessado. Com ela, a
confirmação fora do prazo responde `409 RESERVATION_EXPIRED`, a reserva continua `PENDING` e a
varredura segue dona dela. Confirmada dentro do prazo, a reserva sai do alcance da varredura e
os assentos ficam vendidos — é a única transição que **não** os devolve.

📖 [ARCHITECTURE.md § 12.7](ARCHITECTURE.md) · [ADR-0012](docs/adr/0012-expiracao-com-skip-locked.md)

## Resiliência

Resilience4j aplicado a **um único ponto**: a chamada ao serviço externo de notificação —
a única dependência remota do sistema. **Não há circuit breaker no PostgreSQL**: sem banco
não há venda, e mascarar essa falha trocaria erro explícito por corrupção silenciosa.

| Mecanismo | Configuração | Papel |
|---|---|---|
| **Timeout** | `connect 500ms` / `read 1s` | Sem ele, nada mais funciona: a thread não volta para ser protegida |
| **Retry** | 3 tentativas, backoff exponencial + jitter | Só falhas **transitórias** (timeout, `5xx`, `429`); nunca `4xx` |
| **Circuit breaker** | Abre com 50% de falha em janela de 20 (mín. 10) | `CLOSED → OPEN → HALF_OPEN → CLOSED`, com transição automática |
| **Fallback** | Log e descarte | A venda já aconteceu; um aviso não entregue não a invalida |

**Onde retry é proibido:** em qualquer operação de reserva. "Timeout" e "não sei" são
indistinguíveis para o cliente, e repetir uma escrita não idempotente cria a segunda reserva.
Quem decide repetir é o cliente — e a `Idempotency-Key` torna a repetição segura.

📖 [ARCHITECTURE.md § 13](ARCHITECTURE.md) · [ADR-0013](docs/adr/0013-resiliencia-apenas-na-borda-externa.md)

## Escalabilidade

| Componente | Escala horizontal? |
|---|---|
| API (instâncias) | ✅ Linear — stateless de verdade: nenhum estado de negócio em memória |
| Varredura de expiração e relay do outbox | ✅ Linear — `FOR UPDATE SKIP LOCKED` reparte lotes disjuntos |
| Consumidores Kafka | ✅ Até o número de partições |
| PostgreSQL (leitura) | ✅ Com réplicas |
| PostgreSQL (escrita) | ❌ Um primário |
| **A linha de inventário de um evento** | ❌ **É o gargalo** |

Em uma frase: **tudo escala somando réplicas, menos a linha de inventário do evento em
disputa.** O teto por evento é `1 / latência_de_commit`, e ele **não muda** com o número de
instâncias da API — é o preço de um contador exato. A próxima alavanca, se um único evento
exigir mais, é particionar o inventário em *buckets*.

O comportamento analisado em 10 → 100 → 1.000 → 10.000 → 100.000 requisições concorrentes,
com o que limita em cada patamar, está em
[ARCHITECTURE.md § 15](ARCHITECTURE.md) — análise arquitetural, sem benchmark inventado.

## Decisões arquiteturais

Dezesseis decisões registradas em [docs/adr/](docs/adr/), com contexto e consequências. As
que mais moldam o sistema:

| # | Decisão | Por quê |
|---|---|---|
| [0001](docs/adr/0001-arquitetura-mvc-em-camadas.md) | MVC em camadas, não Clean/Hexagonal | Dois agregados; indireção sem ganho |
| [0004](docs/adr/0004-postgres-como-ponto-de-coordenacao.md) | PostgreSQL como **único** ponto de coordenação | Um mecanismo de exclusão, não dois |
| [0008](docs/adr/0008-disponibilidade-derivada.md) | Disponibilidade derivada, nunca persistida | Duas fontes da verdade é como se produz oversell |
| [0010](docs/adr/0010-alocacao-por-update-condicional.md) | Alocação por `UPDATE` condicional atômico | A decisão acontece no banco, sob o lock da linha |
| [0011](docs/adr/0011-idempotencia-por-constraint-unica.md) | Idempotência por constraint única | O banco arbitra a corrida, não a aplicação |
| [0012](docs/adr/0012-expiracao-com-skip-locked.md) | Expiração com `FOR UPDATE SKIP LOCKED` | Job distribuído sem líder e sem dependência nova |
| [0013](docs/adr/0013-resiliencia-apenas-na-borda-externa.md) | Resiliência só na borda externa | Circuit breaker no banco troca erro por corrupção |
| [0014](docs/adr/0014-transactional-outbox-para-eventos.md) | Transactional Outbox | Elimina o dual write banco + Kafka |
| [0015](docs/adr/0015-indices-e-retencao.md) | Índices do caminho crítico e retenção | Consultas de job que crescem com o histórico |
| [0016](docs/adr/0016-observabilidade-minima.md) | Observabilidade mínima | Só o que responde a uma pergunta de operação |

## Trade-offs

Cada escolha tem um custo, e ele é declarado:

| Escolha | Custo aceito |
|---|---|
| Contador exato em uma linha | Essa linha é o gargalo do evento: `1 / latência_de_commit` |
| SQL explícito fora do JPA | Menos portável entre bancos — mas a semântica do comando *é* a solução |
| Sem Redis | Uma leitura a mais no banco; em troca, uma fonte da verdade só |
| Outbox | Uma escrita a mais na transação, e entrega **at-least-once** (duplicata possível, perda não) |
| Expiração por varredura | A reserva vencida ocupa assento até a próxima passada (latência = intervalo) |
| Notificação *best-effort* | Com o serviço externo fora por muito tempo, avisos são perdidos (ficam no log) |
| Duas classes de serviço na criação | Imposto pelo comportamento transacional do PostgreSQL, não por gosto por camadas |
| Testes com Testcontainers | Suíte mais lenta e exige Docker — em troca, a concorrência é testada onde ela acontece |
| Ordem só aproximada no relay | Nenhum consumidor atual depende de ordem; garanti-la custaria serializar o relay |

## Frontend

Existe uma interface de demonstração para este backend, em um projeto separado:
**[`../flash-booking-front-end`](https://github.com/allysonacs/flash-booking-front-end)**.

Ela consome a API **real** em tempo de execução — não há mock em nenhuma tela — e existe para
demonstrar ao vivo, em poucos cliques, o que este README descreve em texto: ausência de
oversell sob concorrência, idempotência de reserva, expiração automática e disponibilidade
derivada do inventário.

### Stack

React 19 + TypeScript + Vite 8, CSS puro com custom properties, Vitest e oxlint.
**Dependências de runtime: apenas `react` e `react-dom`** — nenhuma biblioteca de componentes,
de HTTP, de formulário, de estado ou de rotas foi adicionada (o roteamento por hash tem ~60
linhas próprias, adequado a cinco telas sem rota aninhada).

### Como instalar e executar

O `./setup.sh` da raiz do backend já faz tudo isto (clone à parte). Manualmente, com o
repositório clonado como irmão deste — ver [Layout de diretórios](#layout-de-diretórios) — e
o backend já no ar em `:8080` (ver [Como executar a aplicação](#como-executar-a-aplicação)):

```bash
git clone https://github.com/allysonacs/flash-booking-front-end.git ../flash-booking-front-end
cd ../flash-booking-front-end
npm install
npm run dev          # http://localhost:5173
```

O indicador no cabeçalho consulta `/actuator/health` e mostra **API conectada** quando o
backend responde.

| Comando | O que faz |
|---|---|
| `npm run dev` | Dev server em `:5173`, com proxy para o backend |
| `npm run build` | Type-check + build de produção em `dist/` |
| `npm run preview` | Serve o build em `:4173`, com o mesmo proxy |
| `npm run test` | Testes unitários (Vitest) |
| `npm run lint` | oxlint |

### `VITE_API_BASE_URL` e por que o padrão é vazio

| Variável | Padrão | Função |
|---|---|---|
| `VITE_API_BASE_URL` | *(vazio)* | Base URL da API. Vazio = caminhos relativos, encaminhados pelo proxy do Vite |
| `BACKEND_URL` | `http://localhost:8080` | Alvo do proxy do Vite (`dev` e `preview`) |
| `VITE_API_TIMEOUT_MS` | `10000` | Teto de espera por resposta |

Este backend **não configura CORS**, e isso é deliberado: uma API que não conhece nenhum
frontend não tem por que liberar origens. Chamar `localhost:8080` direto de `localhost:5173`
seria cross-origin e o navegador bloquearia.

A interface resolve isso **sem alterar o backend**: o dev server do Vite encaminha `/events`,
`/reservations` e `/actuator` para `BACKEND_URL`, de modo que, para o navegador, frontend e API
estão na mesma origem. Servindo o `dist/` em outro servidor, as opções são encaminhar esses
prefixos no próprio servidor web (nginx) ou apontar `VITE_API_BASE_URL` para o backend — nesse
segundo caso, e só nesse, seria necessário liberar a origem no backend.

### Integração com cada endpoint

| Método | Rota | Uso na interface |
|---|---|---|
| `POST` | `/events` | Formulário de criar evento; `errors[]` de `VALIDATION_ERROR` exibido campo por campo |
| `GET` | `/events/{id}` | Cards da lista, tela do evento, leituras antes/depois do simulador |
| `POST` | `/events/{eventId}/reservations` | Reserva, sempre com `Idempotency-Key`; distingue `201` (criou) de `200` (repetiu) |
| `GET` | `/reservations/{id}` | Detalhes da reserva e reconciliação do prazo vencido |
| `POST` | `/reservations/{id}/confirmation` | Confirmação; trata `200`, `409 RESERVATION_EXPIRED` e `409 INVALID_RESERVATION_STATE` |
| `DELETE` | `/reservations/{id}` | Cancelamento, com modal de confirmação; trata `204`, `404` e `409 INVALID_RESERVATION_STATE` |
| `GET` | `/actuator/health` | Indicador de conexão no cabeçalho |

### Telas

| Rota | Tela |
|---|---|
| `#/` | Dashboard: criar evento, lista de eventos com disponibilidade, totais agregados |
| `#/events/{id}` | Detalhes do evento: barra de disponibilidade, *Reserve Tickets*, confirmação com countdown |
| `#/reservations` | Consulta de reserva por Reservation ID |
| `#/reservations/{id}` | *Reservation Details*: todos os campos da API, countdown, confirmação e cancelamento |
| `#/flash-sale` | *Flash Sale Simulator*: dispara 10/50/100 reservas concorrentes e tabula os desfechos |

### Principais fluxos

1. **Criar evento** → a tela navega para o evento criado e ele entra na lista, sem reload.
2. **Reservar** → confirmação com Reservation ID, quantidade, evento, `status` (`PENDING`),
   `createdAt`, `expiresAt` e countdown; a disponibilidade do evento é reconsultada.
3. **Consultar reserva** → todos os campos devolvidos pela API, mais o evento e sua
   disponibilidade atual.
4. **Confirmar** → após o `200`, reserva `CONFIRMED`, countdown substituído por "Confirmada
   até" e **disponibilidade inalterada** — os assentos já estavam descontados desde a reserva.
   Confirmar depois do prazo devolve `409 RESERVATION_EXPIRED`, com mensagem própria: "o prazo
   venceu" não é a mesma história que "esta reserva foi cancelada".
5. **Cancelar** → modal de confirmação → após o `204`, reserva `CANCELLED` e disponibilidade
   de volta.
6. **Expiração** → countdown até `00:00`, a UI consulta a API até ela informar o estado real, e
   então exibe `EXPIRED` com os ingressos devolvidos. Para demonstrar em segundos:
   `RESERVATION_TTL=25s RESERVATION_EXPIRATION_INTERVAL=PT5S ./gradlew bootRun`.
7. **Concorrência** → o simulador mostra quantas requisições receberam `201`, quantas
   `409 INSUFFICIENT_CAPACITY`, a disponibilidade antes e depois, e o veredito de que o total
   reservado não passou da capacidade. Com capacidade 120 e 100 requisições paralelas: 100
   reservas em ~480 ms, 20 ingressos restantes, zero oversell.

## Frontend Architecture

### Componentização

Componentes existem por reuso ou por responsabilidade, não por contagem. As primitivas do
design system (`Button`, `Card`, `Field`, `Modal`) são separadas dos componentes de domínio
(`StatusBadge`, `AvailabilityBar`, `EventCard`, `ReservationPanel`, `ExpirationCountdown`).
`ReservationPanel` é usado tanto pela confirmação de reserva quanto pela tela de detalhes: uma
reserva tem a mesma aparência e o mesmo comportamento nos dois lugares, e as duas transições
que ela aceita — confirmar e cancelar, com todos os seus desfechos — existem em um único ponto
do código.

### Cliente de API

Nenhum componente chama `fetch`. Todo tráfego passa por `services/httpClient.ts`, que
centraliza base URL, headers, timeout por `AbortController`, interpretação do corpo
`application/problem+json` e normalização de qualquer falha em uma classe `ApiError`
(`status` + `code` + `errors[]`). O status HTTP é preservado até a UI, porque `201` e `200`
significam coisas diferentes na criação de reserva. `services/api.ts` expõe um método por
endpoint **que existe** — não há função para rota inexistente.

### State management

Não há biblioteca de estado global, e a razão é de domínio: **a API é a fonte da verdade** de
disponibilidade, status e prazo. Um cache global criaria uma segunda verdade capaz de divergir
dela — exatamente o que a [ADR-0008](docs/adr/0008-disponibilidade-derivada.md) evita ao
derivar a disponibilidade a cada leitura. O estado de cada tela vive na tela (hook
`useAsyncResource`, com abort e recarga silenciosa); o que atravessa telas são os avisos
efêmeros e um índice local de ids no `localStorage`, que guarda **só ids**, nunca dados.

A atualização da disponibilidade é por reconsulta pontual, disparada por um evento concreto
(reserva criada, cancelamento, fim de countdown, botão de atualizar). **Não há polling
contínuo** nem WebSocket: o backend não oferece canal em tempo real, e introduzir um para
refletir o clique do próprio usuário seria sofisticação sem função.

### Tratamento de erros

Uma única camada (`services/errorMessages.ts`) traduz falha em mensagem, e decide pelo **`code`**
do contrato ([ADR-0009](docs/adr/0009-contrato-de-erros-rfc7807.md)) — nunca pelo `detail`, que
é texto técnico voltado a quem integra a API. `INSUFFICIENT_CAPACITY` vira *"Não há ingressos
suficientes disponíveis para este evento."*; `IDEMPOTENCY_KEY_CONFLICT`,
`EVENT_NOT_OPEN_FOR_RESERVATION`, `INVALID_RESERVATION_STATE`, os 404, `VALIDATION_ERROR`
(com cada violação no seu campo), `INVALID_PARAMETER`, `INTERNAL_ERROR`, timeout e falha de
rede têm cada um sua mensagem; um código não mapeado cai na mensagem da família de status.
Stack trace, mensagem de exceção e `detail` **nunca** chegam à tela — o código do erro aparece
apenas como legenda discreta, útil durante o code review.

### Idempotência

A UI envia `Idempotency-Key` em toda reserva, e a chave identifica uma **tentativa lógica**, não
um clique:

- clique repetido durante o processamento é ignorado — nenhuma requisição nova, nenhuma chave
  nova;
- falha de rede ou timeout e nova tentativa reenviam a **mesma** chave: se a primeira chegou a
  criar a reserva, a segunda devolve aquela reserva (`200`) em vez de criar outra;
- mudar a quantidade gera chave nova, porque outro payload é outra intenção — reusar a chave
  produziria `IDEMPOTENCY_KEY_CONFLICT`;
- reserva criada descarta a chave: a próxima é outra intenção.

Quando a resposta é `200`, a tela diz explicitamente que a tentativa já havia sido processada e
que nada novo foi criado. No simulador de flash sale cada requisição leva chave **própria** —
são N compradores simultâneos, não uma retentativa.

### Loading

Toda chamada assíncrona tem estado de carregamento com rótulo do que está acontecendo
(*Criando evento…*, *Reservando ingressos…*, *Cancelando reserva…*). O componente `Button`
desabilita a si mesmo enquanto a operação corre, e as operações de escrita têm guarda explícita
contra reentrada: um duplo clique não gera duas operações.

### Expiração

O relógio do navegador não expira nada. Ao chegar a `00:00`, a UI exibe "Prazo vencido"
mantendo o `status` que a API devolveu, e passa a consultar `GET /reservations/{id}` a cada 5
segundos **enquanto a API ainda disser `PENDING`**, com teto de tentativas — a janela existe
porque a varredura do backend roda em intervalo (30s por padrão), e uma única consulta no
instante do zero cairia antes dela. Quando a API informa `EXPIRED`, a tela reflete o estado
real e a disponibilidade do evento é reconsultada.

### Limitação registrada: não há listagem

A API expõe `GET /events/{id}` e `GET /reservations/{id}`, e nenhuma listagem. **O backend não
foi alterado por causa da UI.** A lista de eventos é montada a partir de um índice local de ids
no `localStorage`, buscando cada evento por id — o índice guarda apenas ids, e todo dado
exibido vem da API. Consequências assumidas: a lista é por navegador, um evento criado em outra
máquina só aparece se o id for colado no campo próprio para isso, e os totais do dashboard
somam apenas os eventos carregados na lista — com o rótulo dizendo exatamente isso, em vez de
apresentar uma métrica global que o backend não fornece.

## Limitações conhecidas

Declaradas de propósito — o que não existe é tão importante quanto o que existe:

| Limitação | Situação |
|---|---|
| **Sem autenticação/autorização** | Nenhum requisito de identidade foi dado; qualquer um cancela qualquer reserva |
| **Confirmação não emite evento de integração** | `POST /reservations/{id}/confirmation` muda o estado, mas não grava no outbox: só a criação publica em Kafka hoje |
| **Sem rate limiting / backpressure na borda** | Pertence ao nginx/ingress; hoje uma rajada vira fila no banco |
| **Sem DLQ** | Mensagem que falha 3× é registrada e o offset avança; o evento original continua no outbox |
| **Notificação de reserva já cancelada** | O consumidor reage a um fato passado: se a reserva foi cancelada antes do consumo, o aviso ainda sai |
| **Evento fechado em corrida** | O status do evento é lido sem lock; fechar a venda no exato instante de uma reserva pode deixá-la passar |
| **Sem exportador de métricas** | Ficam em `/actuator/metrics`; Prometheus/OTLP é uma dependência e uma propriedade |
| **Sem projeção de disponibilidade** | Deliberado: seria uma segunda fonte da verdade (ADR-0008) |
| **Relógio da aplicação define `expires_at`** | Desvio de relógio desloca o instante da expiração, nunca a correção |
| **Testes de concorrência em uma JVM** | Mais fraco *na forma*; o mecanismo testado é o do banco, idêntico entre processos |

## Roadmap

| Fase | Entrega | Status |
|---|---|---|
| 0 | Fundação: build, aplicação, health check, testes, documentação | ✅ concluída |
| 1 | PostgreSQL + Flyway + `Event`/`EventInventory` + endpoints de evento | ✅ concluída |
| 2 | `Reservation` + estratégia anti-oversell + testes de concorrência | ✅ concluída |
| 3 | Idempotência (`Idempotency-Key`) | ✅ concluída |
| 4 | Expiração automática de reservas pendentes | ✅ concluída |
| 5 | Eventos assíncronos (Kafka + Transactional Outbox) e consistência eventual | ✅ concluída |
| 6 | Resiliência (Resilience4j) no cliente externo de notificação | ✅ concluída |
| 7 | Observabilidade (correlação, métricas, sondas), índices do caminho crítico, retenção | ✅ concluída |
| 8 | Confirmação de reserva (`PENDING → CONFIRMED`), guardada por estado e por prazo | ✅ concluída |

Fora do escopo entregue, e conscientemente: autenticação, evento de integração da confirmação,
DLQ, rate limiting na borda e exportador de métricas — ver
[Limitações conhecidas](#limitações-conhecidas).

## Documentação

- [../flash-booking-front-end/README.md](https://github.com/allysonacs/flash-booking-front-end) — interface de
  demonstração em React: stack, como executar, integração endpoint por endpoint, idempotência
  na UI, countdown de expiração, tratamento de erros, design system e limitações assumidas.
- [ARCHITECTURE.md](ARCHITECTURE.md) — arquitetura, responsabilidades de cada camada,
  persistência, riscos técnicos e as estratégias de concorrência, overselling, idempotência,
  expiração, resiliência (§13) e mensageria com consistência eventual (§14) — incluindo por
  que **o Kafka não garante a correção do inventário**; mais a análise de escala e performance
  (§15, com o comportamento em 10 → 100.000 requisições concorrentes e por que não há Redis) e
  observabilidade e produção (§16).
- [docs/postman/](docs/postman/) — coleção do Postman pronta para importar, com o fluxo na
  ordem e os ids encadeados automaticamente.
- [docs/adr/](docs/adr/) — decisões arquiteturais registradas, com contexto e consequências.
