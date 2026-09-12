# Arquitetura — Flash Booking

> Documento vivo. Descreve o que já existe e, explicitamente, o que **ainda não foi
> implementado**. Estratégias marcadas como *planejadas* estão documentadas aqui para que
> a decisão seja discutida antes do código — não são promessas de que o código já exista.

---

## 1. Objetivo do sistema

Permitir a venda de ingressos de eventos com capacidade limitada em regime de *flash sale*:
picos curtos e intensos de demanda, muito mais requisições do que assentos, várias
instâncias da API atendendo em paralelo.

O sistema é avaliado por uma invariante central:

> **`reserved_count` de um evento nunca pode ultrapassar `total_capacity`** — sob qualquer
> nível de concorrência, com qualquer número de instâncias, mesmo com falhas parciais.

Tudo o mais (latência, disponibilidade da leitura, riqueza do modelo) é negociável. Essa
invariante não é.

## 2. Requisitos funcionais

| Método | Rota | Descrição | Estado |
|---|---|---|---|
| `POST` | `/events` | Cria um evento com capacidade total | ✅ implementado |
| `GET` | `/events/{id}` | Consulta um evento e sua disponibilidade | ✅ implementado |
| `POST` | `/events/{id}/reservations` | Cria uma reserva de N ingressos | ✅ implementado |
| `GET` | `/reservations/{id}` | Consulta uma reserva | ✅ implementado |
| `POST` | `/reservations/{id}/confirmation` | Confirma uma reserva dentro do prazo | ✅ implementado |
| `DELETE` | `/reservations/{id}` | Cancela uma reserva e devolve os assentos | ✅ implementado |

Ciclo de vida da reserva: `PENDING → CONFIRMED | CANCELLED | EXPIRED`. As três transições
saem de `PENDING` por `UPDATE` guardado por estado, e apenas `CANCELLED` e `EXPIRED` devolvem
assentos — a confirmação não mexe no inventário, porque os assentos já foram comprometidos na
criação. A confirmação carrega uma guarda adicional, `expires_at > now()`, sem a qual poderia
atravessar um TTL vencido na janela entre o vencimento e a varredura de expiração.

## 3. Requisitos não funcionais

| # | Requisito | Como será endereçado (resumo) |
|---|---|---|
| RNF-1 | Múltiplas instâncias simultâneas | Aplicação stateless; toda coordenação no banco |
| RNF-2 | Nunca permitir oversell | ✅ `UPDATE` condicional atômico + `CHECK` constraint |
| RNF-3 | Expiração automática de reservas pendentes | ✅ Varredura agendada com `FOR UPDATE SKIP LOCKED`, em todas as instâncias |
| RNF-4 | Idempotência | ✅ `Idempotency-Key` + índice único no banco |
| RNF-5 | Consistência eventual para disponibilidade | Projeção de leitura alimentada por eventos |
| RNF-6 | Tratamento explícito de erros | Catálogo de erros de domínio → RFC 7807 |
| RNF-7 | Testes automatizados | Unitários, de integração e **de concorrência** |

## 4. Stack

**Em uso:** Java 21, Spring Boot 4.1.1 (Spring MVC), Gradle Wrapper 9.7.1 (Kotlin DSL),
Spring Boot Actuator, Spring Data JPA/Hibernate, PostgreSQL 16, Flyway, HikariCP, Bean
Validation, Resilience4j, Apache Kafka (KRaft) + Kafka UI, Docker Compose,
JUnit 5 + Mockito + Spring Boot Test + Testcontainers.

**Planejada:** nenhuma dependência nova prevista — o que falta é escala horizontal e
observabilidade, que são configuração e operação, não biblioteca.

As decisões de persistência estão detalhadas na [seção 10](#10-persistência).

O motivo de a stack entrar por fase, e não de uma vez, está em
[ADR-0005](docs/adr/0005-entrega-incremental-e-nao-objetivos.md): dependência que não é
usada é custo de build, superfície de CVE e ruído de revisão.

## 5. Arquitetura MVC

Monolito Spring Boot em **camadas clássicas (MVC)**, stateless, replicável horizontalmente.
Não há Clean Architecture, Hexagonal nem DDD tático elaborado — a justificativa está em
[ADR-0001](docs/adr/0001-arquitetura-mvc-em-camadas.md).

```
        cliente HTTP
             │
   ┌─────────▼──────────┐
   │    Controller      │  HTTP → DTO → chamada de serviço
   └─────────┬──────────┘
             │ DTOs
   ┌─────────▼──────────┐
   │      Service       │  regra de negócio + fronteira transacional
   └─────────┬──────────┘
             │ entidades
   ┌─────────▼──────────┐
   │    Repository      │  acesso a dados
   └─────────┬──────────┘
             │ SQL
        ┌────▼────┐
        │ Postgres│  ← ponto único de coordenação entre instâncias
        └─────────┘
```

Regra de dependência: as setas apontam sempre para baixo. O `Repository` não conhece o
`Service`; o `Service` não conhece HTTP; o `Controller` não conhece SQL.

## 6. Responsabilidades de cada camada

| Pacote | Responsabilidade | Não faz |
|---|---|---|
| `controller` | Mapear rotas, validar formato de entrada, montar resposta e status | Regra de negócio, transação, acesso a dados |
| `service` | Casos de uso, invariantes, `@Transactional`, publicação de eventos | Serializar JSON, conhecer `HttpServletRequest` |
| `repository` | Consultas e comandos, incluindo SQL nativo quando a concorrência exigir | Decidir regra de negócio |
| `entity` | Estado persistente e transições de estado válidas | Ser exposto na API |
| `dto` | Contrato público da API (`record`s imutáveis), com a conversão entidade→DTO em fábrica estática | Carregar regra de negócio |
| `exception` | Exceções de domínio + `@RestControllerAdvice` | Ser lançada por camada de infraestrutura |
| `config` | Beans de infraestrutura, `@ConfigurationProperties` | Regra de negócio |

**SOLID aplicado onde paga o próprio custo:** controllers finos (SRP); `SeatAllocator`
como interface com implementações alternativas (OCP/DIP, Fase 2 — e é ela que permite o
teste que *prova* o oversell trocando a implementação); serviços dependendo de abstrações
de repositório (DIP). Não se cria interface para classe com uma única implementação e sem
ponto de variação previsto.

## 7. Decisões que ainda serão implementadas

| Fase | Entrega | Depende de |
|---|---|---|
| 1 | ✅ **Concluída** — PostgreSQL, Flyway `V1`, `Event` + `EventInventory`, Docker Compose, Testcontainers, `POST`/`GET /events`, `GlobalExceptionHandler` | — |
| 2 | ✅ **Concluída** — `Reservation`, `SeatAllocator`, anti-oversell, endpoints de reserva, testes de concorrência | Fase 1 |
| 3 | ✅ **Concluída** — idempotência via `Idempotency-Key` | Fase 2 |
| 4 | ✅ **Concluída** — `Clock` bean, `expires_at`, varredura de expiração (`PENDING → EXPIRED` + devolução dos assentos) e confirmação da reserva (`PENDING → CONFIRMED`, guardada também pelo prazo) | Fase 2 |
| 5 | ✅ **Concluída** — Kafka, `outbox_messages`, relay, consumidor idempotente. Projeção de disponibilidade **descartada** por contrariar o ADR-0008 (§14.8) | Fase 4 |
| 6 | ✅ **Concluída** — Resilience4j (timeout, retry, circuit breaker, fallback) no cliente de notificação · ⏳ DLT | Fase 2 |
| 7 | nginx + réplicas, métricas de negócio, logs correlacionados | Fase 5 |

## 8. Riscos técnicos conhecidos

| Risco | Impacto | Mitigação planejada |
|---|---|---|
| **Hot row**: toda venda de um evento disputa a mesma linha de inventário | Throughput por evento limitado pela latência de commit | Linha de inventário isolada em tabela própria e mínima; evolução para *buckets* de inventário se necessário |
| Oversell por race condition | Violação da invariante central | Decisão no banco (`UPDATE` condicional), não na aplicação, com `CHECK` como rede de segurança |
| Dual write (banco + Kafka) | Evento publicado sem commit, ou commit sem evento | ✅ Transactional Outbox: evento gravado na mesma transação e publicado por um relay (§14.3) |
| Reservas pendentes que nunca expiram | Assentos presos indefinidamente | ✅ Varredura idempotente com `FOR UPDATE SKIP LOCKED`, rodando em todas as instâncias |
| Corrida entre cancelar e expirar a mesma reserva | Reserva `CANCELLED` e `EXPIRED` ao mesmo tempo, assento devolvido duas vezes | ✅ Ambas as transições guardadas por `status = 'PENDING'` no `UPDATE`: exatamente uma se aplica (§12.7) |
| Relógio das instâncias fora de sincronia | Expiração cedo ou tarde demais | ✅ O corte da varredura é o `now()` do banco — o único relógio comum a todas as instâncias |
| Entrega repetida de mensagem | Efeito duplicado (comprador notificado duas vezes) | ✅ Consumidor idempotente por `processed_events`, na mesma transação do efeito (§14.7) |
| Kafka fora do ar | Integração parada | ✅ A venda não depende dele: o outbox acumula e drena quando o broker volta |
| Retry do cliente gerando reserva duplicada | Cliente cobrado duas vezes | ✅ Idempotência com chave; nenhum retry automático sobre escrita de reserva (§13.6) |
| Serviço externo de notificação lento ou fora do ar | Threads presas, latência da venda contaminada | ✅ Timeout no socket, retry limitado, circuit breaker e fallback (§13); a chamada nem parte da thread da venda, e sim de um consumidor Kafka (§14) |

## 9. Estratégias

> As seções 9.1 a 9.7 descrevem o que **já está implementado**. O detalhamento completo, para
> code review, está na [seção 12](#12-concorrência-em-profundidade-guia-de-code-review)
> (concorrência), na [seção 13](#13-resilience-strategy) (resiliência) e na
> [seção 14](#14-mensageria-kafka-outbox-e-consistência-eventual) (mensageria). A 9.8 é o
> caminho de escala, ainda não exercitado.

### 9.1 Concorrência

A aplicação não decide se há assento disponível; ela **pede ao PostgreSQL que decida**. A
coordenação entre instâncias vive inteiramente no banco — sem lock distribuído, sem Redis,
sem estado em memória, e isso vale também para o trabalho em segundo plano: a varredura de
expiração é repartida entre as instâncias por `FOR UPDATE SKIP LOCKED`, não por eleição de
líder. Isolamento `READ COMMITTED` (padrão do Postgres): em um `UPDATE`
condicional, quem perde a corrida pela linha reavalia o predicado sobre a versão já
commitada, e não sobre a versão que leu.

### 9.2 Overselling

Três camadas de defesa, da mais barata para a mais definitiva:

1. **`UPDATE` condicional atômico** — incrementa `reserved_count` *somente se* o resultado
   couber na capacidade; zero linhas afetadas significa "esgotado", e não erro de sistema.
2. **`CHECK (reserved_count BETWEEN 0 AND total_capacity)`** — rede de segurança: mesmo um
   bug futuro na aplicação não consegue persistir um estado inválido.
3. **Teste de concorrência como regressão permanente** — N threads disputando M assentos
   devem resultar em exatamente M vendidos; e a implementação ingênua do alocador deve
   **falhar** esse mesmo teste, provando que a proteção é real e não coincidência.

### 9.3 Idempotência

Header `Idempotency-Key` na criação de reserva, com `UNIQUE (event_id, idempotency_key)`.
Mesma chave + mesmo payload ⇒ replay da resposta original. Mesma chave + payload diferente
⇒ `409 Conflict` (comparação por fingerprint SHA-256 do payload canônico). A unicidade é
garantida pelo banco, nunca por checagem prévia na aplicação — "consultar e então inserir"
é exatamente a corrida que se quer evitar.

### 9.4 Expiração

Reserva nasce `PENDING` com `expires_at` calculado a partir de um `Clock` injetável e de um
TTL externalizado (`flash-booking.reservation.ttl`, padrão 15 minutos). Uma varredura
agendada (`@Scheduled`, `fixedDelay` de 30 s por padrão) coleta as reservas vencidas com
`SELECT ... FOR UPDATE SKIP LOCKED`, aplica `PENDING → EXPIRED` e devolve os assentos ao
inventário **no mesmo commit** que muda o estado da reserva.

Todas as instâncias rodam a varredura ao mesmo tempo, de propósito: `SKIP LOCKED` faz cada
uma levar um lote disjunto, sem eleição de líder, sem ShedLock e sem Redis. O processo é
idempotente — reprocessar não devolve assento duas vezes, porque tanto a reivindicação
quanto a transição só alcançam quem ainda está `PENDING`. O mecanismo completo está em
**§12.7**.

### 9.5 Consistência eventual

Contrato explícito com o cliente:

- **Forte:** a criação da reserva, o estado dela e a disponibilidade exibida em
  `GET /events/{id}` — todas lidas da fonte da verdade, na transação. A decisão de vender
  nunca depende de mensagem.
- **Eventual:** tudo o que *reage* à venda — hoje a notificação ao comprador, amanhã painéis,
  antifraude ou parceiros. O fato é publicado depois do commit e consumido em seguida.

A fronteira entre as duas é a mesma fronteira entre o que está dentro e o que está fora da
transação. Detalhamento em **§14.8**.

### 9.6 Eventos

**Transactional Outbox**: o evento de integração é gravado em `outbox_messages` na mesma
transação que cria a reserva; um relay agendado publica no Kafka e marca como enviado.
Elimina o dual write — não existe reserva sem evento nem evento sem reserva. O consumidor é
idempotente por `processed_events (message_id, consumer_group)`, porque a entrega é
*at-least-once* e nenhuma configuração de broker muda isso para efeitos que saem do Kafka.
O detalhamento, com o risco da versão ingênua e os trade-offs aceitos, está na **§14**.

### 9.7 Resiliência

Resilience4j aplicado **apenas onde há dependência externa que pode degradar**: o cliente de
notificação da reserva, que é a única chamada remota do sistema. Timeout explícito de
conexão e de leitura, retry com backoff exponencial e jitter restrito a falhas transitórias,
circuit breaker por instância e fallback que descarta o aviso sem derrubar a venda — tudo
externalizado em configuração. Não se aplica circuit breaker ao próprio banco: sem banco não
há venda, e mascarar essa falha só transforma erro explícito em corrupção silenciosa. O
detalhamento está na **§13**.

Quando o Kafka entrar, o mesmo princípio vale: indisponibilidade dele **não** pode impedir
vendas — o outbox acumula e drena quando ele volta, e o readiness probe não derruba a
instância por causa disso.

### 9.8 Escalabilidade

A aplicação é stateless: escalar é adicionar réplicas atrás de um load balancer. O gargalo
real é conhecido e declarado — a linha de inventário do evento em disputa, cujo teto é
`1 / latência_de_commit` por evento. O caminho de evolução, se e quando o teto for
atingido, é particionar o inventário em *buckets* por evento, mantendo a mesma invariante.
Isso não será feito preventivamente.

---

## 10. Persistência

### 10.1 Por que PostgreSQL

Porque a propriedade central do sistema — não vender o mesmo assento duas vezes — é um
problema de **concorrência transacional**, e o PostgreSQL resolve isso sem nenhum
componente adicional: MVCC maduro, `UPDATE` condicional atômico, `CHECK` constraints como
rede de segurança e `FOR UPDATE SKIP LOCKED` para agendamento distribuído (ver
[ADR-0004](docs/adr/0004-postgres-como-ponto-de-coordenacao.md)). Escolher o banco que já é
o árbitro natural da corrida elimina a necessidade de um lock distribuído em Redis ou de
uma fila serializando as vendas.

Além disso, `TIMESTAMPTZ`, tipo `UUID` nativo e `JSONB` (para o outbox, na Fase 5) cobrem o
modelo inteiro sem extensões.

### 10.2 Por que JPA/Hibernate

Para o CRUD do domínio — criar evento, carregar reserva, transicionar estado — o JPA elimina
código repetitivo e, com `ddl-auto=validate`, ainda funciona como uma checagem de que o
mapeamento e o schema não divergiram.

O que o JPA **não** vai fazer é decidir a venda. O caminho crítico do anti-oversell será SQL
explícito (`UPDATE ... WHERE reserved_count + :q <= total_capacity`), porque ali a semântica
exata do comando é a solução, e escondê-la atrás de um `save()` é justamente como se produz
oversell: ler o objeto, verificar em memória e gravar. JPA para o que é conveniência, SQL
para o que é invariante.

Cuidados já adotados: `open-in-view: false` (a sessão não vaza para a serialização da
resposta), associação `Event`↔`EventInventory` **não mapeada** como relacionamento (são
agregados carregados de forma independente, e carregar metadados não deve tocar a linha
quente) e nenhuma disponibilidade denormalizada.

### 10.3 Por que Flyway e como as migrations evoluem

`ddl-auto=create`/`update` é conveniente no primeiro dia e insustentável depois: não versiona
nada, não é revisável, não roda igual em produção e não sabe migrar dados. O schema é
artefato de código como qualquer outro.

Estratégia adotada:

- migrations versionadas e **imutáveis** em `src/main/resources/db/migration`, no padrão
  `V<n>__<descrição>.sql`; uma migration já aplicada nunca é editada — corrige-se com a
  próxima;
- SQL puro, sem DSL: é o que será executado em produção, e é o que o revisor consegue ler;
- `ddl-auto=validate` em **todos** os perfis, inclusive testes — o Hibernate confere na
  subida que cada entidade corresponde ao schema migrado, e a aplicação recusa iniciar se
  divergir;
- constraints de integridade (`CHECK`, `FOREIGN KEY`, `UNIQUE`) vivem na migration, não
  apenas na entidade: são a última linha de defesa, e precisam valer mesmo para quem
  escrever no banco sem passar pela aplicação;
- as migrations rodam na subida da aplicação. Com múltiplas instâncias subindo ao mesmo
  tempo, o Flyway serializa via lock no próprio banco.

Schema atual (`V1__create_event_schema.sql`, `V2__create_reservation_schema.sql`):

| Tabela | Colunas | Papel |
|---|---|---|
| `events` | `id`, `name`, `total_capacity`, `status`, `created_at` | Metadados estáveis do evento |
| `event_inventory` | `event_id`, `total_capacity`, `reserved_count`, `updated_at` | Linha quente do inventário, com `CHECK` anti-oversell |
| `reservations` | `id`, `event_id`, `quantity`, `status`, `idempotency_key`, `expires_at`, `created_at`, `updated_at` | Compromisso do cliente, com índice único de idempotência |

### 10.4 Conexão entre aplicação e banco

```
Spring Data JPA → Hibernate → HikariCP → driver JDBC → PostgreSQL
```

- **Configuração por perfil:** `application.yml` traz o que não muda (JPA, Flyway,
  Actuator); `application-local.yml` aponta para o PostgreSQL do Compose; `application-test.yml`
  não declara datasource algum — quem o fornece é o Testcontainers.
- **Sem credencial no código:** todos os valores sensíveis vêm de variáveis de ambiente, com
  padrões de desenvolvimento apenas no perfil `local`.
- **Pool explícito:** o HikariCP é o limite real de concorrência contra o banco. Ele é
  dimensionado e declarado, com `connection-timeout` curto: durante a flash sale é melhor
  recusar depressa do que enfileirar uma requisição que já perdeu a corrida.
- **Health check:** `/actuator/health` inclui o indicador `db`, então `UP` significa também
  "há conexão válida com o PostgreSQL".
- **Ordem de inicialização:** no Compose, a aplicação declara `depends_on: service_healthy`
  contra o `pg_isready` do banco — ela não tenta subir antes de o PostgreSQL aceitar conexões.

### 10.5 Estratégia de testes de integração

O princípio é simples: **teste de persistência que não toca o banco de produção não prova
nada**. Um H2 em memória aceita SQL que o PostgreSQL recusa, ignora `CHECK` constraints
escritas em dialeto específico e tem semântica de lock diferente — exatamente as
características de que este sistema depende.

Por isso:

- todo teste de integração sobe um **PostgreSQL real via Testcontainers**, na mesma imagem
  (`postgres:16-alpine`) usada no `docker-compose.yml`;
- o container é um **singleton por execução da JVM** (`AbstractIntegrationTest`): subir o
  banco uma vez por classe dominaria o tempo da suíte, e a URL estável permite que o Spring
  reaproveite o contexto entre classes;
- o schema dos testes é criado **pelo Flyway**, não pelo Hibernate: o teste exercita a mesma
  migration que vai para produção;
- as asserções leem as linhas de volta **por SQL puro**, fora da sessão do Hibernate — um
  teste que confia no cache de primeiro nível pode passar sem que nada tenha chegado ao banco;
- há um teste que ataca o banco diretamente para confirmar que a `CHECK` anti-oversell
  recusa um estado inválido mesmo quando a aplicação é contornada.

Mocks continuam válidos para isolar regra de negócio (Fase 2 em diante), nunca para simular
persistência.

**Custo aceito:** a suíte exige Docker em execução e leva alguns segundos a mais. É o preço
de um teste que significa alguma coisa.

---

## 11. Contrato de erros

Toda falha responde em **RFC 7807** (`application/problem+json`), produzida em um único
`@RestControllerAdvice`. O corpo traz `code` — identificador estável, feito para o cliente
programar em cima — além de `title`, `detail`, `instance` e `timestamp`. Erros de validação
acrescentam `errors[]` com campo e motivo. O raciocínio completo está em
[ADR-0009](docs/adr/0009-contrato-de-erros-rfc7807.md).

| `code` | HTTP | Significado | O que o cliente faz |
|---|---|---|---|
| `VALIDATION_ERROR` | 400 | Campo ausente ou fora das restrições | Corrige o payload; `errors[]` diz qual campo |
| `MALFORMED_REQUEST` | 400 | Corpo ilegível ou JSON inválido | Corrige a serialização |
| `INVALID_PARAMETER` | 400 | Parâmetro de rota com formato inválido | Corrige o identificador |
| `EVENT_NOT_FOUND` | 404 | Evento inexistente | Não repete a requisição |
| `RESOURCE_NOT_FOUND` | 404 | Rota inexistente | Corrige a URL |
| `METHOD_NOT_ALLOWED` | 405 | Método não suportado pela rota | Corrige o verbo HTTP |
| `NOT_ACCEPTABLE` | 406 | `Accept` incompatível | Aceita `application/json` |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | `Content-Type` não suportado | Envia `application/json` |
| `RESERVATION_NOT_FOUND` | 404 | Reserva inexistente | Não repete a requisição |
| `INSUFFICIENT_CAPACITY` | 409 | Não há assentos suficientes | Desiste ou tenta quantidade menor |
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | Chave reusada com payload diferente | Usa uma chave nova |
| `INVALID_RESERVATION_STATE` | 409 | A operação não vale para o estado atual | Consulta a reserva antes de insistir |
| `EVENT_NOT_OPEN_FOR_RESERVATION` | 409 | O evento não está vendendo | Não repete a requisição |
| `INTERNAL_ERROR` | 500 | Defeito da aplicação | Pode tentar novamente mais tarde |

O handler estende `ResponseEntityExceptionHandler`: as exceções que o próprio Spring MVC
lança para rota inexistente, método não suportado ou mídia incompatível já têm semântica
HTTP correta, e herdar esse tratamento garante os status certos sem enumerar exceção por
exceção — o handler genérico de `Exception` volta a significar apenas "defeito". Essa foi a
correção de um erro real: antes, qualquer falha de protocolo virava 500.

Três propriedades que esse desenho garante:

- **nenhum stack trace ou mensagem interna vaza** — o que chega ao handler genérico é
  registrado com stack trace no log e respondido de forma genérica;
- **controllers não têm `try/catch`** — a tradução de falha em resposta acontece em um lugar
  só;
- **o domínio não conhece HTTP** — `DomainException` carrega apenas um código estável, e o
  mapeamento para status vive no handler. Uma regra nova de negócio herda de
  `DomainException` e já sai correta, sem tocar no handler.

---

## 12. Concorrência em profundidade (guia de code review)

Esta seção existe para ser lida em voz alta. É o núcleo do desafio.

### 12.1 Como o overselling é evitado

Em uma frase: **a aplicação nunca decide se há ingresso disponível — ela pede ao PostgreSQL
que incremente o contador apenas se o resultado couber na capacidade, e confere quantas
linhas mudaram.**

```sql
UPDATE event_inventory
   SET reserved_count = reserved_count + :quantity,
       updated_at = now()
 WHERE event_id = :eventId
   AND reserved_count + :quantity <= total_capacity
```

`1` linha afetada = vendido. `0` = esgotado — resultado normal de uma disputa, não defeito.

O que **não** existe em lugar nenhum do código, e é o ponto principal da revisão:

```java
// isto nunca aparece no projeto — é como se produz oversell
if (inventory.getAvailableCapacity() >= quantity) {   // ① leitura
    inventory.setReservedCount(...);                  // ② decisão em memória
    repository.save(inventory);                       // ③ escrita
}
```

Entre ① e ③ cabe outra transação — possivelmente em outra instância — vendendo o mesmo
lugar. Nenhum nível de isolamento padrão impede isso, porque a decisão foi tomada fora do
banco. Mover a condição para dentro do `UPDATE` remove a janela: não há mais "entre".

Três camadas de defesa, da mais barata para a mais definitiva:

1. **`UPDATE` condicional atômico** — a estratégia;
2. **`CHECK (reserved_count >= 0 AND reserved_count <= total_capacity)`** — rede de
   segurança: nem um bug futuro, nem um `psql` aberto por engano, conseguem gravar um estado
   inválido;
3. **testes de concorrência como regressão permanente** — inclusive o que **prova** o
   oversell quando a estratégia é trocada.

### 12.2 Isolation level

`READ COMMITTED`, o padrão do PostgreSQL. Não foi elevado, e isso é deliberado.

`SERIALIZABLE` protegeria contra anomalias de leitura — mas a decisão de vender **não depende
de nenhuma leitura feita pela aplicação**. Ela é uma única escrita condicional avaliada pelo
banco. Elevar o isolamento só acrescentaria `serialization_failure` sob contenção e
obrigaria a aplicação inteira a implementar retry, para garantir algo que já está garantido.

### 12.3 Como funciona o lock

No caminho da venda não há lock explícito — nenhum `synchronized`, nenhum lock distribuído,
nenhum `FOR UPDATE`. O lock é o **row lock** que o próprio `UPDATE` adquire:

```
T1: UPDATE ... WHERE reserved_count + 1 <= 100   → trava a linha, reserved_count 99 → 100
T2: UPDATE ... WHERE reserved_count + 1 <= 100   → BLOQUEIA no lock da linha
T1: COMMIT                                        → libera
T2: acorda, REAVALIA o predicado contra reserved_count = 100 (já commitado)
    100 + 1 <= 100 é falso → 0 linhas afetadas → "esgotado"
```

O passo decisivo é o último: sob `READ COMMITTED`, quando um `UPDATE` é desbloqueado, o
PostgreSQL **reavalia a cláusula `WHERE` contra a versão atualizada da linha**. T2 não
enxerga o valor antigo que leu — não leu nada. É por isso que o resultado é correto sem
qualquer coordenação entre as instâncias.

O único `FOR UPDATE` do projeto está na varredura de expiração, e por um motivo diferente:
ali não se trata de decidir quem fica com o assento, e sim de **repartir trabalho** entre as
instâncias. Por isso ele vem com `SKIP LOCKED` — quem encontra uma linha travada não espera,
pega a próxima (§12.7).

### 12.4 Como funciona a transação

Uma reserva é uma transação com duas escritas, nesta ordem:

```java
@Transactional
public Reservation createReservation(...) {
    // 1. valida o evento (existe? está vendendo?)
    // 2. INSERT da reserva + flush   → aqui a chave de idempotência é verificada pelo banco
    // 3. UPDATE condicional do inventário → aqui a venda é decidida
}                                      // commit: ou as duas escritas valem, ou nenhuma vale
```

A ordem é escolhida, não acidental:

- inserir e *flushar* a reserva primeiro faz a violação de idempotência acontecer **antes**
  de tocar a linha quente;
- deixar o `UPDATE` do inventário por último minimiza o tempo em que o lock da linha quente
  fica retido — e esse tempo é o que limita o throughput do evento.

Se não houver disponibilidade, a exceção desfaz também o `INSERT`: não sobra reserva sem
assento. Se a reserva violar a chave, o rollback devolve os assentos que ela havia
comprometido. **Não existe estado intermediário observável.**

No cancelamento, a devolução é *guardada* pela transição de estado:

```sql
UPDATE reservations SET status = 'CANCELLED' WHERE id = :id AND status = 'PENDING'
```

Só quem afeta 1 linha devolve os assentos. Dois `DELETE` simultâneos disputam essa transição
no banco; o perdedor afeta 0 linhas e não devolve nada. Sem essa guarda, o mesmo assento
voltaria duas vezes ao inventário — e **capacidade criada do nada é a outra face do
oversell**.

### 12.5 Como funciona a idempotência

Índice único parcial em `(event_id, idempotency_key)`. Três caminhos:

| Situação | Resultado |
|---|---|
| Chave já conhecida, mesmo payload | `200 OK` com a reserva original |
| Chave já conhecida, payload diferente | `409 IDEMPOTENCY_KEY_CONFLICT` |
| Chave nova | `201 Created` |

O caso que merece a atenção do revisor é o quarto, que não cabe na tabela: **duas requisições
simultâneas com a mesma chave**. As duas consultam, nada encontram, as duas inserem. O
PostgreSQL faz a segunda *aguardar no índice único* até a primeira terminar — e só então
lança a violação. Quando a violação é capturada, a vencedora **já está commitada e visível**;
a perdedora lê e devolve a reserva da vencedora, enquanto seu próprio rollback libera os
assentos que havia comprometido.

Isso explica um detalhe de desenho que sempre é perguntado: `ReservationService.create()`
**não é `@Transactional`**. Uma violação de constraint aborta a transação no PostgreSQL —
nenhum comando é aceito depois dela. Quem captura a violação precisa estar fora da transação
que falhou; por isso a transação vive em `ReservationTxService`, em outra classe (uma chamada
interna não passaria pelo proxy do Spring).

### 12.6 Por que funciona com múltiplas instâncias

Porque **nenhuma parte da solução vive na aplicação**:

| Preocupação | Onde é resolvida |
|---|---|
| Quem fica com o último assento | Row lock + predicado no `UPDATE` |
| Duas requisições com a mesma chave | Índice único |
| Dois cancelamentos da mesma reserva | `UPDATE` guardado por estado |
| Cancelar e expirar a mesma reserva | `UPDATE` guardado por estado: só uma transição se aplica |
| Qual instância expira qual reserva | `FOR UPDATE SKIP LOCKED`: lotes disjuntos, sem eleição de líder |
| Estado inválido por qualquer caminho | `CHECK` constraint |

Não há cache, sessão, contador em memória, `synchronized` ou lock de JVM em nenhum caminho de
escrita — nem sequer no agendamento, que roda igual em todas as instâncias. Subir dez
instâncias não muda o raciocínio: muda quantos clientes disputam a mesma linha, e quantos
workers drenam a fila de vencidas.

Os testes rodam com threads em uma única JVM, o que é mais fraco *na forma*, não no
mecanismo: cada thread abre sua própria transação e disputa a mesma linha exatamente como
processos distintos fariam.

### 12.7 Como funciona a expiração

Uma reserva `PENDING` prende assentos. Se ninguém a expira, um carrinho abandonado tira
ingressos da venda para sempre — numa flash sale, isso é perda direta de receita. A
expiração é, portanto, tão crítica quanto a venda, e sofre exatamente os mesmos problemas de
concorrência.

**Estratégia do scheduler.** `@Scheduled(fixedDelay)` em `ReservationExpirationJob`, com
intervalo configurável (`flash-booking.reservation.expiration.interval`, padrão 30 s).
`fixedDelay` e não `fixedRate`: o intervalo conta a partir do *fim* da execução anterior, de
modo que uma varredura lenta não empilha execuções sobrepostas. Cada execução drena até
`maxBatchesPerRun` lotes de `batchSize` reservas e devolve o resto para a próxima passada —
um acúmulo grande é consumido aos poucos, sem que uma execução monopolize o banco.

**Estratégia de locking.** A reivindicação do lote é o núcleo da solução:

```sql
SELECT id FROM reservations
 WHERE status = 'PENDING' AND expires_at <= now()
 ORDER BY expires_at
 FOR UPDATE SKIP LOCKED
 LIMIT :batchSize
```

`FOR UPDATE` tranca as linhas selecionadas até o commit. `SKIP LOCKED` é o que muda o
desenho: em vez de esperar pelas linhas que outra transação já travou, o PostgreSQL
**pula** essas linhas e devolve as próximas disponíveis. Duas instâncias que varrem ao mesmo
tempo recebem conjuntos disjuntos — não há espera, não há deadlock e não há sobreposição.
O corte de tempo é o `now()` do banco, e não o relógio da JVM: com N instâncias, é o único
relógio comum a todas elas.

**Comportamento com múltiplas instâncias.** Todas rodam a varredura, o tempo todo. Não há
eleição de líder, `ShedLock`, lock distribuído, Redis nem instância "dona do job" — e,
portanto, não há ponto único de falha nem instância ociosa esperando outra falhar. Dez
instâncias simplesmente drenam a fila dez vezes mais rápido, cada uma em seu próprio lote. O
custo é uma consulta barata por instância por intervalo, mesmo quando não há nada a expirar:
o custo cresce com o número de instâncias, não com o volume de reservas.

**Transação.** Um lote é **uma** transação em `ReservationExpirationService`:

```java
@Transactional
public int expireDueReservations(int batchSize) {
    // 1. SELECT ... FOR UPDATE SKIP LOCKED   → reivindica e tranca o lote
    // 2. UPDATE ... SET status = 'EXPIRED' WHERE ... AND status = 'PENDING'
    // 3. UPDATE event_inventory SET reserved_count = reserved_count - N
}                                            // commit: os três passos valem, ou nenhum vale
```

Mudar o estado e devolver os assentos acontecem juntos ou não acontecem. Se a devolução
falhar, a reserva continua `PENDING` e a varredura seguinte tenta de novo — **nunca fica um
assento preso por uma reserva morta, nem um assento devolvido por uma reserva que continuou
viva**. Lotes pequenos mantêm a transação curta, o que importa porque o `UPDATE` do
inventário disputa a mesma linha quente que as vendas em andamento; a devolução é agrupada
por evento, de modo que duas reservas do mesmo evento viram um único comando.

**Transições de estado.** Todas as saídas de `PENDING` são `UPDATE`s guardados pelo próprio
estado de origem:

| Transição | Quem aplica | Guarda |
|---|---|---|
| `PENDING → CANCELLED` | `DELETE /reservations/{id}` | `AND status = 'PENDING'` |
| `PENDING → EXPIRED` | varredura agendada | `AND status = 'PENDING'` |

Como as duas partem do mesmo estado e as duas carregam a mesma guarda, **exatamente uma se
aplica** — o banco torna aritmeticamente impossível uma reserva ser `CANCELLED` e `EXPIRED`
ao mesmo tempo, e impossível o mesmo assento voltar duas vezes ao inventário.

**Idempotência do processamento.** Em dois níveis, sobrepostos de propósito:

1. a reivindicação só enxerga `PENDING`, então uma reserva já expirada não reaparece em
   nenhuma varredura futura;
2. a transição repete a condição no `WHERE`, então aplicá-la de novo afeta **zero** linhas —
   e a devolução dos assentos só acontece para as linhas que de fato mudaram.

Rodar a varredura duas vezes seguidas, em instâncias diferentes, no mesmo instante, devolve
os assentos uma única vez. O `CHECK (reserved_count >= 0 AND reserved_count <=
total_capacity)` continua atrás de tudo como rede de segurança: se algum caminho futuro
errar a conta, o banco recusa a escrita em vez de registrar um estado impossível.

**Quem vence a corrida entre cancelar e expirar.** Não há vencedor predefinido — **vence
quem travar a linha primeiro**, e o resultado é consistente nos dois casos:

```
Cancelamento primeiro: reserva vira CANCELLED; a varredura não a encontra mais (não é PENDING).
                       Assento devolvido uma vez, pelo cancelamento → 204.
Varredura primeiro:    a varredura trava a linha; o cancelamento BLOQUEIA no row lock até o
                       commit, então reavalia a guarda contra EXPIRED → afeta 0 linhas e não
                       devolve nada. Assento devolvido uma vez, pela varredura → 409.
```

Escolher arbitrariamente um vencedor ("cancelamento sempre ganha") exigiria coordenação
entre a API e o job — isto é, exatamente o lock distribuído que a solução dispensa. O que
importa para a invariante não é *qual* transição vence, e sim que **só uma vence e só uma
devolve**.

A resposta do perdedor, porém, **é** determinística. Um `UPDATE` que afeta zero linhas não
diz *por quê*, então o cancelamento relê o estado atual antes de responder: `CANCELLED` é
sucesso (o cliente pediu um estado que já é o atual), e qualquer outro estado é `409`. Sem
essa releitura, cancelar uma reserva que acabou de expirar responderia `204` ou `409`
conforme o instante em que a primeira leitura caiu — o mesmo pedido, duas respostas, por
acaso de agendamento.

**O que deliberadamente não entrou.** Nem Redis, nem ShedLock, nem Quartz, nem fila de
mensagens: o PostgreSQL já é o ponto de coordenação do sistema (ADR-0004), e `SKIP LOCKED`
resolve o problema com uma cláusula SQL. Detalhes e consequências aceitas em
[ADR-0012](docs/adr/0012-expiracao-com-skip-locked.md).

### 12.8 Trade-offs assumidos

| Trade-off | Por que é aceitável |
|---|---|
| **A linha de inventário é o gargalo** — o teto por evento é `1 / latência_de_commit` | É o preço de um contador exato. Evolução prevista: particionar o inventário em *buckets* por evento, mantendo a invariante |
| **SQL explícito fora do JPA** | Onde a semântica do comando *é* a solução, escondê-la seria o defeito |
| **O banco é ponto único de falha da venda** | Sem banco não há venda; mascarar isso com circuit breaker trocaria erro explícito por corrupção silenciosa |
| **`409` para toda regra de negócio** | Simplificação consciente; o mapeamento por tipo de exceção acomoda semânticas diferentes sem reescrita |
| **A corrida de idempotência custa um rollback** | É o caminho raro, e o preço de não ter lock nem coordenação |
| **Duas classes de serviço para a criação** | Imposto pelo comportamento transacional do PostgreSQL, não por gosto por camadas |
| **A expiração é eventual** | Uma reserva vencida ocupa assento até a próxima varredura; a latência máxima é o intervalo, e é configurável |
| **Todas as instâncias consultam o banco a cada intervalo** | A consulta é barata e o custo cresce com o número de instâncias, não com o volume — o preço de não ter eleição de líder nem lock distribuído |
| **Sem índice em `(status, expires_at)` por ora** | O volume atual não justifica, e um índice a mais encarece toda escrita na tabela; entra quando a varredura aparecer nas consultas lentas |

### 12.9 Os testes que sustentam tudo isso

| Teste | O que prova |
|---|---|
| 100 simultâneas / 100 lugares | Vende exatamente a capacidade, sem perder venda |
| 200 simultâneas / 100 lugares | 100 sucessos e 100 recusas — nunca 101 |
| 1000 simultâneas / 100 lugares | O resultado não degrada com contenção |
| Reservas de 3 em 100 lugares | Para em 33 reservas (99 lugares): não vende quantidade parcial |
| 50 simultâneas com a mesma chave | Exatamente 1 reserva, 1 criação e 49 replays |
| 20 cancelamentos simultâneos da mesma reserva | Assentos devolvidos uma única vez |
| Reserva vencida sob a varredura | `PENDING → EXPIRED` e os assentos voltam à disponibilidade |
| Reserva cancelada, depois vencida | A varredura não a alcança: o assento não volta duas vezes |
| Varredura repetida sobre a mesma reserva | A segunda passada processa 0: o processamento é idempotente |
| 20 varreduras simultâneas / 60 reservas vencidas | Cada reserva processada exatamente uma vez — `SKIP LOCKED` entrega lotes disjuntos |
| 20 workers disputando **uma** reserva vencida | Apenas um a expira; os outros não encontram nada |
| Cancelamento e expiração em paralelo | Um vence, o assento volta uma vez, o perdedor recebe `409` |
| Vender, expirar e revender sob carga mista | A disponibilidade nunca sai de `[0, capacidade]` |
| Varredura agendada, sem ninguém chamá-la | O agendamento está de fato ligado — pega fiação quebrada |
| **Estratégia ingênua sob a mesma carga** | **O oversell aparece** — a prova de que é a estratégia que salva, e não sorte |

O último é o mais importante do conjunto: sem ele, os outros provariam apenas que o sistema
passou, não *por quê*.

## 13. Resilience Strategy

Resiliência aqui tem um endereço só: **a chamada ao serviço externo de notificação**. Vale
começar pelo que *não* foi protegido, porque é a parte que costuma revelar se a decisão foi
pensada ou decorada.

### 13.1 Onde a resiliência se aplica — e onde não

| Operação | Protegida? | Por quê |
|---|---|---|
| `UPDATE` do inventário, `INSERT` da reserva, varredura de expiração | **Não** | São locais e transacionais. Sem banco não há venda: um circuit breaker aqui trocaria um erro explícito e alarmável por uma resposta inventada, e um retry cego sobre uma escrita reexecutaria uma reserva que talvez já tenha sido gravada |
| Notificação da reserva criada | **Sim** | É remota, pode degradar, e sua falha **não invalida a venda** — a reserva já está commitada quando ela acontece |

O critério, em três perguntas: *é remota?*, *a operação principal continua válida se ela
falhar?*, *repetir é seguro?* Três "sim" ⇒ vale proteger. Qualquer "não" ⇒ proteger é
esconder.

Isso obrigou a criar a integração externa que ainda não existia. Ela é pequena de propósito:
um `POST` com o id da reserva, o evento, a quantidade e o prazo. O domínio não mudou — a
reserva não sabe que existe notificação; quem sabe é o serviço de aplicação, **depois** do
commit.

### 13.2 Timeout

**Por que existe.** É a proteção sem a qual nenhuma outra funciona. Um cliente HTTP sem
timeout explícito herda o padrão da plataforma, que costuma ser "espere para sempre": basta o
outro lado aceitar a conexão e nunca responder para a thread ficar presa. Retry e circuit
breaker só agem sobre uma chamada que **termina** — e é o timeout que faz uma chamada
terminar.

**Valores escolhidos** (`flash-booking.notification.*`, sobrescrevíveis por ambiente):

| Timeout | Padrão | Raciocínio |
|---|---|---|
| `connect-timeout` | `500ms` | Em rede saudável, abrir conexão leva milissegundos. O que demora está quebrado, e esperar não conserta |
| `read-timeout` | `1s` | Dimensionado pelo pior caso *aceitável* da notificação, não pela média. É um aviso, não um pagamento: acima disso, desistir vale mais do que ocupar a thread |

**O que acontece no timeout.** A chamada falha com `NotificationUnavailableException` —
tratada como falha transitória. Ela é repetida pelo retry, contada pelo circuit breaker e,
esgotadas as tentativas, absorvida pelo fallback: log e descarte. O comprador não percebe
nada, porque nunca esteve esperando por isso — quem faz a chamada é o consumidor do evento
`ReservationCreated`, e não a requisição de venda (§14).

O pior caso é, portanto, limitado e calculável: `3 × (500ms + 1s)` mais o backoff, tudo em
threads do consumidor Kafka. Um serviço externo pendurado atrasa o consumo do tópico e nada
além disso — a venda já terminou muito antes.

**Por que não `TimeLimiter`.** O `TimeLimiter` do Resilience4j age sobre chamadas
assíncronas, cancelando o `CompletableFuture`. Sobre uma chamada bloqueante, ele abandonaria
a espera sem fechar o socket — a thread continuaria presa, e o número anunciado seria mentira.
O timeout que interrompe de verdade é o do socket, e é lá que ele está.

### 13.3 Retry

**Só o que é transitório, e só o que é idempotente.** As duas condições precisam valer ao
mesmo tempo.

| Falha | Repete? | Por quê |
|---|---|---|
| Timeout de conexão ou de leitura | ✅ | O serviço pode estar apenas congestionado |
| Conexão recusada | ✅ | Pod reiniciando, balanceador trocando destino |
| `5xx` | ✅ | Falha do lado deles, tipicamente momentânea |
| `429 Too Many Requests` | ✅ | É literalmente um pedido para tentar mais tarde |
| `4xx` (exceto `429`) | ❌ | O destinatário entendeu e recusou. Repetir produz a mesma recusa, só que N vezes |
| Erro de serialização, configuração ou payload | ❌ | Defeito nosso; retry só adia o diagnóstico |
| **Qualquer operação de reserva** | ❌ | Ver §13.6 |

**Tentativas e backoff:** no máximo **3** (1 chamada + 2 repetições), com backoff
exponencial `200ms → 400ms` e *jitter* de até 50%. O teto é baixo de propósito: se três
tentativas em menos de um segundo não passaram, o problema não é um pacote perdido, e insistir
vira carga sobre um serviço que já está mal. O jitter existe porque N instâncias com backoff
fixo repetem no mesmo instante e batem juntas no serviço no momento em que ele tenta subir.

A tradução do erro é explícita no gateway: `NotificationUnavailableException` (transitória,
repetível) ou `NotificationRejectedException` (permanente, ignorada pelo retry). As duas
categorias existem como tipos porque é delas que sai o comportamento — não de um `if` perdido.

### 13.4 Circuit breaker

Um retry resolve o soluço; ele não resolve a queda. Com o serviço fora, cada notificação
ainda gastaria três tentativas e três timeouts antes de desistir. O circuit breaker é o que
percebe o padrão e **para de tentar**.

| Estado | O que significa | O que acontece na chamada |
|---|---|---|
| `CLOSED` | Operação normal | A chamada passa; sucesso e falha alimentam a janela deslizante |
| `OPEN` | O serviço está fora | A chamada **falha na hora**, com `CallNotPermittedException`, sem sair da JVM: sem socket, sem thread parada, sem carga sobre quem já caiu. Vai direto ao fallback |
| `HALF_OPEN` | Prova de vida | Um número limitado de chamadas passa. Todas bem ⇒ `CLOSED`; falhou ⇒ `OPEN` de novo, e o relógio recomeça |

**Configuração** (`resilience4j.circuitbreaker.instances.notification`): janela de 20
chamadas, mínimo de 10 antes de qualquer decisão, abre com 50% de falha, espera 30s em
`OPEN`, deixa passar 3 provas em `HALF_OPEN`, e a transição `OPEN → HALF_OPEN` é
**automática** — com envio assíncrono pode não haver ninguém chamando para provocá-la.

O mínimo de chamadas importa: sem ele, duas falhas seguidas em uma madrugada de baixo tráfego
abririam o circuito. Taxa de falha sobre amostra pequena é ruído, não sinal.

**Falha que não conta:** `4xx` é ignorado pelo circuito. Payload inválido é defeito nosso, e
abrir o circuito por causa dele cortaria as notificações que funcionam para esconder um bug
de contrato.

**Composição com o retry.** A ordem padrão do Resilience4j é
`Retry( CircuitBreaker( chamada ) )`. Cada tentativa é registrada individualmente — o
circuito enxerga a taxa real de falhas — e, quando ele abre no meio de uma rajada, as
tentativas seguintes falham instantaneamente. Daí um detalhe que só aparece quando se testa:
o `fallbackMethod` fica no `@Retry`, o aspecto **mais externo**. No aspecto interno, o
fallback devolveria normalmente, o retry enxergaria sucesso e nunca repetiria nada.

### 13.5 Idempotência

Retry e idempotência são a mesma decisão vista de dois ângulos. "Tentar de novo" significa
"talvez a primeira tenha chegado" — e só é aceitável quando o destinatário sabe reconhecer a
repetição.

Por isso a notificação viaja com `Idempotency-Key: <id da reserva>`. A chave não é decorativa:
é a licença para repetir. Sem ela, três tentativas contra um serviço que recebeu a primeira e
demorou a responder viram três avisos ao mesmo comprador.

O mesmo princípio percorre o sistema inteiro, sempre apoiado no banco e nunca em memória:

| Operação | O que garante a repetição segura |
|---|---|
| Criar reserva | `UNIQUE (event_id, idempotency_key)` — replay devolve a reserva original (§12.5) |
| Cancelar | `UPDATE ... WHERE status = 'PENDING'` — a segunda vez afeta zero linhas (§12.4) |
| Expirar | A mesma guarda, mais `FOR UPDATE SKIP LOCKED` — reprocessar devolve o assento uma vez (§12.7) |
| Notificar | `Idempotency-Key` com o id da reserva — o destinatário deduplica |

### 13.6 Quando **não** usar retry

Vale escrever a regra na forma negativa, porque é assim que ela é violada na prática:

1. **Quando a operação não é idempotente.** Retry sobre "criar reserva" sem chave de
   idempotência cria duas reservas e compromete o dobro de assentos. O timeout é o caso
   traiçoeiro: a requisição pode ter sido processada e só a *resposta* ter se perdido — do
   lado do cliente, "falhou" e "não sei" são indistinguíveis.
2. **Quando a falha é permanente.** `4xx`, payload inválido, regra de negócio violada.
   Repetir produz o mesmo resultado e atrasa o diagnóstico.
3. **Quando a falha é de negócio.** Evento esgotado é uma resposta *correta*: não há nada a
   reexecutar, e insistir é tentar comprar um ingresso que não existe.
4. **Quando o recurso está saturado.** Retry sob sobrecarga é combustível: a carga extra
   adia a recuperação do serviço que se tenta alcançar. É para isso que existem o backoff
   exponencial, o jitter e o circuit breaker.
5. **Dentro de uma transação aberta.** Cada tentativa segura a transação — e os locks dela —
   por mais tempo. Na linha de inventário disputada, isso é derrubar o throughput do evento
   inteiro para salvar uma requisição.

O caso da própria reserva é o mais importante do conjunto: ela **não** tem retry. Se a
escrita falha, o cliente recebe o erro e decide — e, se decidir repetir, a `Idempotency-Key`
que ele enviou garante que a repetição não vira uma segunda reserva. Retry seguro não é o que
o servidor faz sozinho; é o que o contrato torna possível.

### 13.7 Os testes que sustentam isto

| Teste | O que prova |
|---|---|
| Serviço dorme 3s, timeout de 300ms | A chamada termina em ~1s, e não em 9s: o timeout é real, não decorativo |
| Duas falhas `503` e então `200` | O retry insiste e a terceira tentativa fecha o caso |
| Retentativa carrega a mesma `Idempotency-Key` | O destinatário consegue deduplicar: retry sem duplicação |
| `400` permanente | Uma única requisição, e o circuito continua `CLOSED` |
| Falhas sustentadas | O circuito abre e **o servidor para de receber requisições** |
| Serviço volta | `OPEN → HALF_OPEN → CLOSED`, sozinho, sem intervenção |
| Prova falha em `HALF_OPEN` | O circuito reabre em vez de despejar tráfego em quem ainda está mal |
| Venda com o serviço fora do ar | A venda acontece **sem tocar a rede**: o aviso vira um evento no outbox |
| Evento esgotado com o serviço fora do ar | `InsufficientCapacityException` chega inteira: **erro de negócio não é mascarado** |

O último fecha o desenho. O fallback existe para a borda externa, e só para ela — resiliência
que engole erro de domínio é a mesma falha que vender um ingresso inexistente, com outra
roupa.

## 14. Mensageria: Kafka, Outbox e consistência eventual

> ### ⚠️ Kafka is not used to guarantee inventory correctness.
>
> **O Kafka não participa da decisão de vender.** Nenhum consumidor decide se há assento,
> nenhuma mensagem altera `event_inventory`, e nenhuma leitura de tópico entra no caminho de
> uma reserva. A correção do inventário é garantida por uma única coisa — o `UPDATE`
> condicional atômico dentro da transação PostgreSQL (§12.1) — e continuaria garantida com o
> broker desligado.
>
> Um evento no Kafka é o **registro de um fato que já aconteceu e já está commitado**. Se o
> tópico for apagado, o cluster perdido ou os consumidores parados, nenhum ingresso é vendido
> a mais nem a menos. O que se perde é a reação ao fato, não o fato.
>
> A razão é simples: Kafka é um log distribuído com entrega *at-least-once* e leitura
> assíncrona. "Já vendemos 99 dos 100?" é uma pergunta que exige o estado **agora**, sob
> lock, na fonte da verdade — e um consumidor sempre responde sobre um passado recente.
> Decidir a venda a partir dele é a receita de oversell.

### 14.1 Por que Kafka

A venda gera fatos que outros sistemas querem: notificar o comprador, alimentar o antifraude,
atualizar um painel de vendas, avisar o parceiro. Fazer isso com chamadas síncronas dentro da
transação amarra o tempo de venda à disponibilidade de terceiros — e, numa flash sale, o
tempo de transação *é* o throughput do evento.

Kafka resolve três coisas ao mesmo tempo: desacopla o produtor dos consumidores (a venda não
sabe quem escuta), **retém** o fato (um consumidor que caiu retoma de onde parou, em vez de
perder o que passou) e permite adicionar um consumidor novo sem tocar em quem produz. Uma
fila tradicional dá o primeiro ponto; o log particionado e persistente dá os três.

### 14.2 O problema que não dá para ignorar: transação + publicação

A versão ingênua, e por que ela está errada:

```java
reservationRepository.save(reservation);   // ① commit no PostgreSQL
kafkaTemplate.send(topic, event);          // ② publicação no Kafka
```

São **duas escritas em dois sistemas sem transação comum**. As duas ordens possíveis falham,
de formas diferentes:

| Ordem | O que quebra |
|---|---|
| Commit e **depois** publicar | O processo morre entre ① e ②, ou o Kafka está fora: a reserva existe e **o evento nunca existirá**. Ninguém é notificado, nenhum painel registra a venda — e não sobrou nem registro de que havia algo a publicar, então não há retentativa possível |
| Publicar **dentro** da transação | O commit falha depois do envio: o evento anuncia uma reserva **que o rollback desfez**. Consumidores reagem a um fato que não aconteceu — notificam uma compra inexistente, contabilizam uma venda fantasma |

Não existe ordem correta, porque o problema não é de ordem: é a ausência de atomicidade entre
os dois sistemas. XA/2PC resolveria, ao custo de um coordenador e de deixar o commit do banco
refém da disponibilidade do broker — exatamente o acoplamento que se queria evitar.

### 14.3 A solução: Transactional Outbox

Escrever o evento onde **já existe** transação: no próprio PostgreSQL.

```
┌─ transação da venda ──────────────────────────┐
│  INSERT reservations       (a venda)          │   commit único:
│  INSERT outbox_messages    (o evento)         │   ou os dois, ou nenhum
│  UPDATE event_inventory    (os assentos)      │
└───────────────────────────────────────────────┘
            │
            │  relay assíncrono (@Scheduled, FOR UPDATE SKIP LOCKED)
            ▼
      Kafka: flash-booking.reservations.created
            │
            ▼
      consumidor (grupo flash-booking.notification-dispatcher)
            │  INSERT processed_events  ← idempotência
            ▼
      efeito (notificação ao comprador)
```

| Etapa | Onde | Garantia |
|---|---|---|
| Gravar reserva + evento | `ReservationTxService` | Atômica: mesmo commit |
| Publicar | `OutboxRelayService` | Só marca `PUBLISHED` o que o broker **confirmou** |
| Falhar ao publicar | `OutboxRelayService` | A mensagem continua `PENDING`, `attempts++`, e a próxima passada tenta de novo — **o evento não se perde** |
| Consumir | `ReservationEventProcessor` | `INSERT ... ON CONFLICT DO NOTHING` em `processed_events`: efeito único |

O relay roda em **todas as instâncias**, com a mesma reivindicação por
`FOR UPDATE SKIP LOCKED` da varredura de expiração (§12.7): cada uma leva um lote disjunto,
sem eleição de líder e sem publicar a mesma mensagem duas vezes ao mesmo tempo.

**Por que ainda assim é at-least-once.** Publicar no Kafka e marcar como publicado no
PostgreSQL são, de novo, duas escritas em dois sistemas. Se o processo morre entre o `ack` do
broker e o commit da marcação, a mensagem volta a ser publicada. O outbox não elimina a
duplicata — ele **troca a perda pela duplicata**, e essa troca é o ponto: perder um evento é
irreparável, receber duas vezes é tratável com uma chave primária.

### 14.4 Tópico, particionamento e ordenação

| Aspecto | Escolha | Por quê |
|---|---|---|
| Tópico | `flash-booking.reservations.created` | Um tópico por tipo de fato; o nome diz o agregado e o que aconteceu |
| Partições | 3 (configurável) | É o **teto de paralelismo do consumo**: um grupo nunca processa em paralelo mais do que o número de partições. Aumentar depois é fácil; reduzir, não |
| Chave | `eventId` — o id do **show** | A ordem que importa é a das reservas de um mesmo evento. Mesma chave ⇒ mesma partição ⇒ ordem preservada |
| Réplicas | 1 em desenvolvimento | Em produção, menos de 3 significa perder mensagens com a queda de um broker |

**Ordenação, com honestidade.** O Kafka garante ordem **dentro de uma partição**, nunca entre
partições. Com a chave sendo o show, as mensagens de um mesmo show chegam ordenadas entre si —
que é a única ordem de que este domínio precisaria. Mas o relay introduz uma ressalva: com
várias instâncias drenando o outbox em paralelo, duas mensagens do mesmo show podem ser
publicadas por instâncias diferentes quase ao mesmo tempo, e a ordem no tópico pode não ser a
ordem de criação. É uma consequência aceita: **este consumidor não depende de ordem** — ele
reage a fatos independentes e idempotentes. Se um consumidor futuro precisar de ordem estrita,
o caminho é restringir o relay a um lote ordenado por chave, não fingir que a garantia já
existe.

### 14.5 Produtor

`KafkaEventPublisher` publica e **espera a confirmação** (`publish-timeout`, 5s por padrão).
Esperar é o que permite ao relay marcar como publicado apenas o que existe no broker; um
envio "dispare e esqueça" marcaria como entregue algo que o broker recusou — e aí sim o evento
se perderia, com o banco afirmando o contrário.

Configuração relevante, toda em `application.yml`:

- `acks=all` — a confirmação só vem quando as réplicas in-sync têm a mensagem. Com `acks=1`,
  a queda de um broker logo após o `ack` apaga uma mensagem que o outbox já deu como
  publicada.
- `enable.idempotence=true` — a retentativa interna do produtor não duplica a mensagem no
  broker. Isso cobre um caso específico (o cliente reenviando por timeout de rede) e **não
  substitui** a idempotência do consumidor, que cobre o que acontece antes (o relay
  republicando) e depois (rebalance, reentrega).
- `delivery.timeout.ms` e `request.timeout.ms` menores que o `publish-timeout` do relay, para
  que quem desiste primeiro seja o cliente Kafka, com erro claro.

### 14.6 Consumidor e grupo de consumo

`@KafkaListener` no grupo `flash-booking.notification-dispatcher`. O **grupo** é o que define
"o consumidor": todas as instâncias da aplicação usam o mesmo, dividem as partições entre si e
processam cada mensagem uma vez — subir uma segunda instância dobra a vazão, não o trabalho.
Um grupo diferente (uma auditoria, um relatório) receberia a sua própria cópia do fluxo sem
interferir neste.

- **Offset commitado depois do processamento** (`enable-auto-commit: false`, `ack-mode:
  record`). Com auto-commit, um commit por tempo marcaria como consumida uma mensagem ainda
  não processada — e uma falha a perderia silenciosamente.
- **`auto-offset-reset: earliest`** — um grupo novo lê o histórico em vez de ignorá-lo.
- **Falha no processamento:** reentrega local com backoff (3 tentativas, 500 ms). Esgotadas,
  a mensagem é registrada e o offset avança: insistir para sempre pararia a partição inteira
  por causa de uma mensagem — o clássico *poison message*. O evento original continua no
  outbox, que é de onde uma reprocessagem sairia. A DLT é o próximo passo natural.
- **Payload ilegível** não é retentado: o que não desserializa agora não vai desserializar em
  500 ms.

### 14.7 Idempotência do consumidor

```sql
INSERT INTO processed_events (message_id, consumer_group)
VALUES (:messageId, :group)
ON CONFLICT DO NOTHING
```

Zero linhas afetadas ⇒ a mensagem já foi processada por este grupo ⇒ o consumidor sai sem
fazer nada. É o mesmo padrão do resto do sistema: quem decide sob concorrência é o banco, não
um `if`. "Consultar e, se não achar, processar" teria entre as duas etapas espaço para outra
instância do mesmo grupo processar a mesma mensagem.

A marca e o efeito ficam na **mesma transação**. Se o trabalho falha, a marca é desfeita junto
e a reentrega processa de verdade — em vez de a mensagem constar como feita sem ter sido.

**Por que não confiar em "exactly-once".** O Kafka oferece semântica transacional entre
tópicos, mas o efeito deste consumidor sai do Kafka: é uma chamada HTTP. Nenhuma configuração
de broker torna isso atômico. Soma-se a isso o relay, que pode republicar, e o rebalance, que
pode reentregar. Entrega repetida não é uma hipótese remota a mitigar — é o comportamento
normal do sistema, e a idempotência é a resposta a ela.

### 14.8 Onde entra a consistência eventual

| O quê | Consistência | Onde vive |
|---|---|---|
| **A decisão de vender** | **Forte** | `UPDATE` condicional na transação da reserva. Nunca depende de mensagem |
| **A disponibilidade lida em `GET /events/{id}`** | **Forte** | Derivada de `event_inventory` na hora da leitura (ADR-0008) |
| **O estado da reserva** | **Forte** | Lido da tabela, na transação |
| A notificação ao comprador | **Eventual** | Outbox → Kafka → consumidor. Atraso típico: o intervalo do relay (1s) mais o consumo |
| Qualquer consumidor futuro (painel, antifraude, parceiro) | **Eventual** | Reage ao fato depois dele |

A consistência eventual entra, portanto, **depois da venda e fora dela**: no que *reage* ao
fato, nunca no que o *decide*. Uma notificação alguns segundos atrasada é aceitável; vender
um assento inexistente não é — e é por isso que a fronteira entre as duas colunas é a mesma
fronteira entre o que está dentro e o que está fora da transação.

Vale registrar o que **não** foi feito: não existe projeção de disponibilidade alimentada por
eventos. Seria uma segunda fonte da verdade para um número que o PostgreSQL devolve exato e
barato, e contrariaria o ADR-0008. Se um dia o volume de leitura justificar, ela entra como
*cache* explicitamente carimbado com o instante a que se refere — nunca como resposta à
pergunta "posso vender?".

### 14.9 Trade-offs assumidos

| Trade-off | Por que é aceitável |
|---|---|
| **Entrega at-least-once, não exactly-once** | É a única garantia honesta com efeitos fora do broker. O custo é uma chave primária no consumidor |
| **O evento chega com atraso** (intervalo do relay + consumo) | Nada que dependa dele é síncrono com a venda. O intervalo é configurável |
| **Uma escrita a mais na transação da venda** | Um `INSERT` em tabela sem contenção, dentro de uma transação que já existe. É o preço de não perder eventos |
| **O outbox cresce** | Mensagens publicadas viram histórico; a limpeza periódica é trabalho previsto e trivial (`DELETE` por `published_at`) |
| **Ordem só aproximada entre instâncias do relay** | Nenhum consumidor atual depende de ordem; a garantia estrita custaria serializar o relay |
| **Mais infraestrutura para operar** | Kafka é a única forma de desacoplar de verdade os consumidores da venda — e ele já era requisito do sistema |
| **Kafka fora do ar** | A venda continua funcionando: o outbox acumula e drena quando o broker volta. Nenhuma reserva é recusada por causa disso |

### 14.10 Os testes que sustentam isto

| Teste | O que prova |
|---|---|
| Reserva criada | O evento é gravado `PENDING` na mesma transação; **nada é publicado antes do commit** |
| Reserva desfeita por falta de assento | Nenhum evento sobra: o rollback leva os dois |
| Relay executado | A mensagem chega ao tópico com a chave do show, e a linha vira `PUBLISHED` |
| Relay executado de novo | Não republica: a transição é guardada por estado |
| Broker recusando | A mensagem fica `PENDING` com `attempts=1` e é publicada na passada seguinte — **evento não se perde** |
| Fluxo completo | Venda → outbox → Kafka → consumidor → serviço externo, sem intervenção |
| Mensagem republicada | Processada **uma vez**: uma marca, uma notificação |
| Falha temporária no consumidor | Reentrega automática e processamento único |
| Falha persistente no consumidor | A mensagem é descartada e a **partição continua andando** |

## 15. Scalability & Performance

Esta seção é análise arquitetural, não benchmark. Nenhum número aqui é medido: são limites
derivados do desenho, e o objetivo é saber **onde o sistema quebra primeiro** e qual é a
próxima alavanca — não afirmar uma capacidade que não foi verificada em carga real.

### 15.1 O que escala horizontalmente e o que não escala

| Componente | Escala horizontal? | Por quê |
|---|---|---|
| **API (instâncias da aplicação)** | ✅ Linear | Stateless de verdade: nenhum estado de negócio em memória, nenhuma sessão, nenhum cache local, nenhum `synchronized` ou lock de JVM em caminho de escrita. Uma réplica a mais é throughput a mais |
| **Varredura de expiração** | ✅ Linear | Todas as instâncias rodam; `FOR UPDATE SKIP LOCKED` reparte lotes disjuntos, sem líder (§12.7) |
| **Relay do outbox** | ✅ Linear | Mesmo mecanismo (§14.3) |
| **Consumidores Kafka** | ✅ Até o nº de partições | O grupo divide partições entre instâncias. Com 3 partições, a 4ª instância fica ociosa no consumo — o teto é a partição, não a réplica |
| **Kafka (brokers)** | ✅ Por partição | Adicionar partições e brokers distribui a carga; o custo é a ordenação, que só existe dentro da partição |
| **PostgreSQL — leitura** | ✅ Com réplicas | `GET /events/{id}` e `GET /reservations/{id}` poderiam ir para réplicas de leitura. Hoje não vão: não é necessário, e traria *lag* de replicação a um dado que ainda lemos da fonte da verdade |
| **PostgreSQL — escrita** | ❌ **Não** | Um primário aceita escrita. É o limite estrutural do sistema |
| **A linha de inventário de UM evento** | ❌ **Não** | Todas as vendas daquele evento serializam nesta linha. É o gargalo real, e ele é por evento |

Em uma frase: **tudo escala somando réplicas, menos a linha de inventário do evento em
disputa.** Toda a análise de capacidade se resume a quanto essa linha aguenta.

### 15.2 O caminho crítico: `POST /events/{id}/reservations`

```
①  SELECT events WHERE id = ?                   -- índice: PK. Sem lock.
②  INSERT reservations + flush                  -- valida a chave de idempotência (índice único)
③  INSERT outbox_messages                       -- tabela sem contenção
④  UPDATE event_inventory                       -- ⚠️ ADQUIRE O LOCK DA LINHA QUENTE
   COMMIT                                       -- ⬅️ libera o lock
```

**Lock.** Só existe um, e é o row lock que o `UPDATE` de ④ adquire. Não há `SELECT FOR
UPDATE` no caminho de venda, nem lock distribuído, nem lock de aplicação. Duas vendas do mesmo
evento serializam exatamente aqui — e só aqui.

**Transação.** Uma só, `READ COMMITTED`, envolvendo os quatro passos. A ordem é deliberada: o
`UPDATE` da linha quente é o **último** comando antes do commit, o que minimiza a janela em
que o lock fica retido. Tudo o que pode falhar — evento inexistente, evento fechado, chave de
idempotência repetida — falha **antes** de a linha quente ser tocada.

**Contenção.** O tempo de lock por venda é `t_lock ≈ tempo entre ④ e o commit`, que é
essencialmente **um fsync do WAL**. Com `synchronous_commit=on` em disco NVMe, a ordem de
grandeza é de unidades de milissegundos; em disco de rede, mais. Daí sai o teto teórico:

```
vendas por segundo, por evento  ≈  1 / t_lock
```

Esse número **não muda com o número de instâncias da API**. Dobrar réplicas dobra a
capacidade de receber requisições, validar payload e responder "esgotado" — não a de vender
assentos do mesmo evento. É o resultado que mais surpreende em revisão, e é consequência
direta de ter um contador exato.

**Gargalo de banco.** Três limites, nesta ordem de aparição:

1. **a linha de inventário** — serializa por evento (acima);
2. **as conexões** — `maximum-pool-size: 20` por instância. Com 20 instâncias são 400
   conexões, e o PostgreSQL passa a gastar mais em troca de contexto do que em trabalho útil.
   A partir daí, PgBouncer em modo *transaction* é a resposta padrão (não é o caso hoje);
3. **o WAL e o autovacuum** — `UPDATE` concentrado em poucas linhas gera *bloat*; a tabela de
   inventário é pequena e isolada justamente para que o autovacuum dê conta dela.

**Escala horizontal.** A API escala; o evento não. Se um único evento precisar ultrapassar o
teto de `1/t_lock`, o caminho conhecido é **particionar o inventário em N linhas (*buckets*)
por evento**, sortear o bucket na venda e manter a invariante por bucket. Isso multiplica a
concorrência por N ao custo de tornar "quanto resta" uma soma de N linhas, e de uma venda
poder falhar em um bucket cheio com assento livre em outro. Não foi implementado porque o
requisito atual não exige — e implementar antes de precisar seria complexidade sem evidência.

### 15.3 Comportamento por nível de concorrência

Leitura: *requisições concorrentes no mesmo instante*, majoritariamente no mesmo evento (o
pior caso de uma flash sale).

| Carga | O que acontece | O que limita | O que fazer |
|---|---|---|---|
| **10** | Tudo folgado. Pool de 20 conexões ocioso, lock da linha praticamente sem fila | Nada | Nada |
| **100** | Fila curta na linha de inventário. A latência de cada venda passa a incluir a espera pelas que chegaram antes; ninguém falha, todos esperam um pouco | O lock da linha quente | Nada. Uma instância dá conta |
| **1.000** | A fila do lock fica visível: a latência do `POST` cresce de forma linear com a profundidade da fila. O pool de conexões começa a ser o segundo ponto de espera — requisições aguardam conexão antes mesmo de aguardar o lock | Lock da linha + pool | 2–4 instâncias para distribuir validação e I/O; `connection-timeout` curto (3s, já configurado) para **falhar rápido** em vez de enfileirar quem já perdeu a corrida |
| **10.000** | O evento típico já esgotou antes de a fila drenar. A maioria das requisições termina em `409 sold_out`, que é uma resposta **barata**: um `UPDATE` que afeta 0 linhas. O sistema não quebra — ele fica lento no que vende e rápido no que recusa | Lock da linha; conexões agregadas no PostgreSQL | Réplicas + PgBouncer; *rate limit* na borda (nginx) para que a rajada não vire fila dentro do banco |
| **100.000** | Fora do que este desenho atende **para um único evento**. Distribuído entre muitos eventos, escala normalmente — a contenção é por linha, e eventos diferentes são linhas diferentes | A linha de inventário do evento | Aí sim: *buckets* de inventário, fila de admissão na borda (sala de espera), e leitura de disponibilidade servida por cache/projeção. Nenhuma dessas medidas é gratuita, e todas se pagam **só** nesse patamar |

Dois pontos que a tabela não mostra e importam mais do que ela:

- **O sistema não perde a invariante em nenhum patamar.** O que degrada é latência, nunca
  correção: com 100.000 requisições simultâneas, o `CHECK` e o `UPDATE` condicional continuam
  impedindo o oversell. É por isso que a análise de escala pode falar em "ficar lento" em vez
  de "vender errado".
- **Recusar é barato; vender é caro.** Quanto mais o evento esgota, mais o sistema acelera.
  O pior caso de carga é um evento **grande** com procura alta, não um pequeno esgotado.

### 15.4 Por que não há Redis

Redis resolveria três problemas que este sistema **não tem**:

| Uso comum de Redis | Por que não aqui |
|---|---|
| Lock distribuído | O ponto de coordenação já é o PostgreSQL, e a coordenação é uma cláusula `WHERE` (§12.1). Um lock no Redis seria um segundo mecanismo de exclusão, mais fraco (sem transação com a escrita que ele protege) e com modos de falha próprios — *fencing token*, expiração no meio da operação, split-brain |
| Contador de estoque | Seria uma **segunda fonte da verdade** para o número que não pode estar errado. Manter Redis e PostgreSQL de acordo sem transação comum é o mesmo dual write do §14.2, agora no caminho crítico da venda. Um `INCR` rápido e ocasionalmente divergente é exatamente o que produz oversell |
| Cache de disponibilidade | Só se paga com volume de leitura que este sistema não tem. A leitura hoje é uma consulta por PK em uma tabela minúscula — provavelmente já em *shared buffers*. Cache aqui adicionaria invalidação e leitura velha para economizar microssegundos |

A regra que orienta a decisão: **Redis entra quando houver uma leitura cara e repetida que
tolere estar desatualizada.** Nenhum dos três casos acima se encaixa. Se o gargalo de leitura
aparecer, o caminho é cache explícito na borda, com TTL curto e carimbo de "dado de
{instante}" — nunca o estado que decide a venda.

### 15.5 Análise de índices

Cada índice do schema corresponde a uma consulta que o sistema realmente executa. A V4 é a
migração que fechou as lacunas — e o `SchemaIndexTest` é a trava que impede que sumam.

| Consulta | Onde | Índice | Nota |
|---|---|---|---|
| `events` por id | criação de reserva, `GET /events/{id}` | PK (`pk_events`) | Uma linha, por PK |
| `event_inventory` por `event_id` | venda e leitura | PK (`pk_event_inventory`) | A linha quente; sempre acesso direto |
| `reservations` por id | `GET`/`DELETE /reservations/{id}` | PK (`pk_reservations`) | — |
| `(event_id, idempotency_key)` | toda requisição com `Idempotency-Key` | `ux_reservations_event_idempotency_key` (único, **parcial**) | Parcial em `idempotency_key IS NOT NULL`: requisições sem chave não competem e não entram no índice |
| `reservations` por evento | consultas por evento e a FK | **`ix_reservations_event_status` (V4)** | O PostgreSQL **não** indexa chave estrangeira sozinho. Sem ele, seq scan na maior tabela do sistema |
| `status = 'PENDING' AND expires_at <= now()` | varredura de expiração, **todas as instâncias, a cada 30s** | **`ix_reservations_due` (V4, parcial)** | Parcial em `status='PENDING'`: indexa só a fila viva, que é uma fração minúscula do histórico. O `ORDER BY expires_at` sai do índice já ordenado |
| `status = 'PENDING'` no outbox | relay, **todas as instâncias, a cada 1s** | `ix_outbox_messages_pending` (parcial) | Idem: a fila, não o histórico |
| `published_at < corte` | retenção | **`ix_outbox_messages_published` (V4, parcial)** | Sem ele, a limpeza varreria a tabela que ela existe para conter |
| `(message_id, consumer_group)` | deduplicação do consumidor | PK (`pk_processed_events`) | O `ON CONFLICT` usa a própria PK |
| `processed_at < corte` | retenção | **`ix_processed_events_processed_at` (V4)** | — |

**O que deliberadamente não foi indexado:** `reservations.status` sozinho (baixa
seletividade — a maior parte das linhas termina em poucos estados, e o índice parcial já
cobre o caso que importa) e `events.status` (a tabela é pequena e consultada por PK). Índice
não é gratuito: encarece **toda** escrita na tabela e ocupa espaço em memória que a linha
quente disputa.

**Nota operacional:** em tabela grande e em uso, estes índices devem ser criados com
`CREATE INDEX CONCURRENTLY`, fora de transação — um `CREATE INDEX` comum segura um lock que
bloqueia escrita, o que em uma flash sale é indistinguível de indisponibilidade.

### 15.6 Queries, transações e N+1

- **Não há N+1 no sistema, e não por sorte:** não existe nenhuma associação JPA entre
  agregados. `Event`, `EventInventory` e `Reservation` referenciam-se por `UUID`, nunca por
  `@ManyToOne`/`@OneToMany` — não há coleção para o Hibernate carregar preguiçosamente, logo
  não há N+1 possível. `open-in-view` está desligado desde o primeiro commit, então nenhuma
  consulta pode escapar para a serialização da resposta.
- **`GET /events/{id}` faz duas consultas por PK** (evento + inventário), ambas na mesma
  transação de leitura. É uma a mais do que um `JOIN` faria, e é intencional: manter os dois
  agregados independentes é o que permite ler metadados sem tocar a linha quente.
- **As escritas do domínio são SQL explícito** (`UPDATE` condicional, transições guardadas por
  estado), não manipulação de entidade gerenciada. Não há `flush` surpresa nem `SELECT` de
  leitura prévia no caminho quente.
- **Toda transação é curta e tem escopo de serviço.** Nenhuma chamada de rede acontece dentro
  de uma transação de venda — a notificação foi empurrada para fora por meio do outbox (§14).
  A única transação que faz I/O externo é a do relay, e ela é limitada por
  `publish-timeout`.
- **Leituras usam `@Transactional(readOnly = true)`**, o que evita *dirty checking* e permite
  ao driver marcar a transação como somente leitura.

### 15.7 Limites conhecidos e a próxima alavanca

| Limite | Sinal de que chegou | Próximo passo |
|---|---|---|
| Lock da linha de inventário | Latência do `POST` cresce com a concorrência, CPU do banco baixa | *Buckets* de inventário por evento |
| Conexões agregadas | `hikaricp.connections.pending` alto; PostgreSQL com muitos backends ociosos | PgBouncer (*transaction pooling*) |
| Consumo do tópico | *Lag* do grupo cresce de forma sustentada | Mais partições e mais instâncias (nessa ordem) |
| Fila do outbox | `flashbooking.outbox.pending` crescente | Kafka indisponível ou relay parado — a venda continua, a integração não |
| Tamanho das tabelas de mensageria | Crescimento monótono | Já coberto: retenção periódica (§16.4) |

## 16. Observabilidade e produção

O critério aqui foi o mesmo do resto do projeto: **cada item precisa responder a uma pergunta
operacional concreta.** Rastreamento distribuído, log de auditoria, dezenas de métricas e um
painel bonito não entram porque a operação deste sistema não os exige ainda — e observabilidade
que ninguém lê é custo com aparência de rigor.

As quatro perguntas que a operação de uma flash sale realmente faz:

1. *esta instância está viva? pode receber tráfego?* → health, liveness, readiness;
2. *o que aconteceu com a requisição do cliente que está reclamando?* → id de correlação;
3. *está vendendo, ou está recusando?* → métricas de negócio;
4. *a integração parou sem que a venda tenha parado?* → fila do outbox.

### 16.1 Logs estruturados e id de correlação

Com N instâncias, uma única venda deixa rastro em três lugares diferentes: a instância que
atendeu o HTTP, a que drenou o outbox e a que consumiu a mensagem. Sem um identificador
comum, "procurar no log" deixa de ser uma operação possível.

- Todo request recebe um **id de correlação** (`CorrelationIdFilter`). Se o cliente ou o proxy
  já mandou um (`X-Correlation-Id` ou `X-Request-Id`), ele é **reaproveitado** — é assim que o
  rastro atravessa a fronteira entre sistemas em vez de recomeçar a cada salto.
- O id volta no header da resposta. Quando alguém relata "deu erro", o identificador já está
  na mão de quem reclama.
- Ele vive no `MDC`, então **todo** log da thread o carrega sem que nenhuma chamada de log
  precise citá-lo — e é removido no `finally`, porque thread de pool com MDC sujo carimba a
  requisição seguinte com o id da anterior.
- O rastro **atravessa o commit e o broker**: o id é gravado na linha do outbox
  (`correlation_id`), viaja como header Kafka e é restaurado no `MDC` do consumidor. Um erro
  ao notificar, em outra instância, minutos depois, é rastreável até a requisição que o
  originou. É rastreamento distribuído em sua forma mais barata — sem coletor, sem agente,
  sem *sampling*.
- Trabalho de fundo também tem id (`relay-…`, `expiration-…`, `retention-…`): sem isso, os
  logs de várias instâncias drenando ao mesmo tempo formam uma pilha indistinguível.
- **Formato:** legível por humanos em desenvolvimento; `LOG_STRUCTURED_FORMAT=ecs` troca por
  JSON de uma linha por evento, pronto para um coletor. É recurso nativo do Spring Boot — sem
  dependência extra e sem mudar código. Nos containers, já vem ligado.

### 16.2 Métricas

O que a plataforma já dá e **não** foi reimplementado: latência e status por rota
(`http.server.requests`), pool de conexões (`hikaricp.*`), JVM e GC (`jvm.*`), estados do
circuit breaker e tentativas de retry (`resilience4j.*`), métricas do cliente Kafka.

O que só o domínio sabe, e por isso foi instrumentado:

| Métrica | A pergunta que ela responde |
|---|---|
| `flashbooking.reservations.created` | Está vendendo? |
| `flashbooking.reservations.rejected{reason}` | **Esgotou, ou quebrou?** Em HTTP as duas coisas são `409`; sem esta métrica, um painel não distingue sucesso comercial de incidente |
| `flashbooking.reservations.confirmed` · `seats.confirmed` | Quantas reservas viraram venda? (contra `created`, é a taxa de conversão) |
| `flashbooking.reservations.expired` · `seats.released{cause}` | Carrinho abandonado ou funil de confirmação quebrado? |
| `flashbooking.outbox.pending` (gauge) | A integração parou? É o único sinal de que o Kafka caiu — porque a venda **não** para junto |
| `flashbooking.outbox.published` | O relay está drenando no ritmo da produção? |

A tag `reason` vem de um conjunto fechado de valores do código, nunca de entrada do usuário:
tag de cardinalidade aberta é o jeito clássico de derrubar o sistema de métricas com o próprio
tráfego.

O gauge do outbox é amostrado por um agendamento próprio, e não calculado no *scrape*: assim
a frequência da consulta depende da configuração da aplicação, e não de quantos coletores
apontam para ela.

**Exportador:** hoje as métricas ficam em `/actuator/metrics` (registro em memória).
Publicá-las em Prometheus ou OTLP é uma dependência e uma propriedade — deliberadamente fora
do escopo enquanto não houver coletor para consumi-las.

### 16.3 Health, liveness e readiness — e por que a distinção importa

| Sonda | Inclui | Não inclui | Consequência de falhar |
|---|---|---|---|
| `/actuator/health/liveness` | Estado do processo | **O banco** | O orquestrador **reinicia** o container |
| `/actuator/health/readiness` | Estado da aplicação + **`db`** | **O Kafka** | O balanceador **para de mandar tráfego**, sem reiniciar |

As duas exclusões são decisões, não omissões:

- **O banco fora do liveness.** Se o PostgreSQL cai, todas as instâncias falhariam a sonda ao
  mesmo tempo e seriam reiniciadas em massa — transformando uma indisponibilidade temporária
  do banco em um ciclo de restart que **atrasa** a recuperação. Reiniciar não conserta um banco
  fora do ar.
- **O Kafka fora do readiness.** A venda não depende do broker (§14): o outbox acumula e drena
  quando ele volta. Colocar o Kafka na prontidão faria uma falha de *integração* tirar a
  aplicação de vendas do ar — exatamente o acoplamento que o outbox existe para eliminar. O
  Spring Boot não registra indicador de saúde para Kafka por padrão, e nenhum foi adicionado.

O `healthcheck` do Compose aponta para `/actuator/health/readiness`, e não para o health
genérico, pelo mesmo motivo.

### 16.4 Produção: o que está pronto e o que ficaria para a plataforma

**Pronto no repositório:**

| Item | Como |
|---|---|
| Configuração 100% por ambiente | URLs, credenciais, timeouts, retry, circuit breaker, Kafka, datasource e parâmetros de job — todos `${VAR:default}`. Nenhum segredo em código, nenhum valor de rede fixado em classe |
| Imagem sem privilégios | Usuário dedicado, build multi-stage, JRE mínima |
| JVM consciente de container | `MaxRAMPercentage=75`, `ExitOnOutOfMemoryError` (com OOM, morrer é melhor do que degradar: uma JVM em *thrashing* de GC responde devagar sem nunca falhar o health check) |
| Desligamento gracioso | `server.shutdown: graceful` — requisições em voo terminam antes do fim do processo |
| Superfície do Actuator mínima | Apenas `health`, `info` e `metrics`. Fora: `env`, `configprops`, `beans`, `mappings`, `heapdump`, `threaddump`, `loggers`, `shutdown`. Há um teste que falha se alguém expuser qualquer um deles |
| Retenção de dados operacionais | Limpeza periódica do outbox publicado e das marcas de idempotência, em lotes, para que as tabelas não cresçam para sempre dentro do banco que decide as vendas |
| Migrações versionadas | Flyway, com `ddl-auto: validate` — divergência entre código e schema derruba a subida, em vez de aparecer em produção |

**Fora do escopo, e conscientemente:** autenticação/autorização (nenhum requisito de
identidade foi dado), TLS e *rate limiting* (pertencem à borda — nginx, *ingress*, API
gateway), *secret manager* (a aplicação lê variáveis de ambiente; de onde elas vêm é decisão
da plataforma), porta separada para o Actuator (`management.server.port`, uma linha quando
houver segmentação de rede que a justifique) e coletor de métricas/traces.

---

## Decisões registradas (ADRs)

| ADR | Decisão |
|---|---|
| [0001](docs/adr/0001-arquitetura-mvc-em-camadas.md) | Arquitetura MVC em camadas, não Clean/Hexagonal |
| [0002](docs/adr/0002-java-21-e-spring-boot-4.md) | Java 21 + Spring Boot 4.1.1 |
| [0003](docs/adr/0003-gradle-wrapper-kotlin-dsl.md) | Gradle Wrapper com Kotlin DSL e toolchain Java 21 |
| [0004](docs/adr/0004-postgres-como-ponto-de-coordenacao.md) | PostgreSQL como único ponto de coordenação |
| [0005](docs/adr/0005-entrega-incremental-e-nao-objetivos.md) | Entrega incremental e tecnologias deliberadamente não adotadas |
| [0006](docs/adr/0006-jpa-e-flyway-para-persistencia.md) | JPA/Hibernate para CRUD e Flyway para o schema |
| [0007](docs/adr/0007-identificadores-uuid.md) | Identificadores UUID gerados pela aplicação |
| [0008](docs/adr/0008-disponibilidade-derivada.md) | Disponibilidade derivada, não persistida |
| [0009](docs/adr/0009-contrato-de-erros-rfc7807.md) | Contrato de erros com RFC 7807 e códigos estáveis |
| [0010](docs/adr/0010-alocacao-por-update-condicional.md) | Alocação de assentos por `UPDATE` condicional atômico |
| [0011](docs/adr/0011-idempotencia-por-constraint-unica.md) | Idempotência por constraint única, resolvida no banco |
| [0012](docs/adr/0012-expiracao-com-skip-locked.md) | Expiração por varredura com `FOR UPDATE SKIP LOCKED` |
| [0013](docs/adr/0013-resiliencia-apenas-na-borda-externa.md) | Resiliência apenas na borda externa |
| [0014](docs/adr/0014-transactional-outbox-para-eventos.md) | Transactional Outbox para publicar eventos no Kafka |
| [0015](docs/adr/0015-indices-e-retencao.md) | Índices do caminho crítico e retenção das tabelas de mensageria |
| [0016](docs/adr/0016-observabilidade-minima.md) | Observabilidade mínima: correlação, métricas de negócio e sondas separadas |
