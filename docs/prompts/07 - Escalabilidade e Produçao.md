Continue o projeto existente.

Agora faça uma revisão técnica completa com foco em:

* escalabilidade;
* performance;
* observabilidade;
* produção;
* múltiplas instâncias.

NÃO reescreva o projeto.

Faça uma análise primeiro.

## ESCALABILIDADE

Avalie:

* stateless API;
* múltiplas instâncias;
* connection pool;
* PostgreSQL;
* índices;
* locks;
* contenção;
* Kafka;
* consumer groups;
* gargalos.

Explique quais componentes escalam horizontalmente e quais podem virar bottleneck.

## DATABASE

Analise os índices necessários.

Principalmente:

* event.id;
* reservation.id;
* reservation.event_id;
* reservation.status;
* reservation.expires_at;
* idempotency_key;
* outbox status.

Verifique queries importantes.

Evite N+1.

Analise transações.

## OBSERVABILIDADE

Adicionar:

* logs estruturados;
* correlation/request ID;
* métricas relevantes;
* health checks;
* readiness;
* liveness.

Não adicionar observabilidade excessivamente complexa.

## ACTUATOR

Adicionar Spring Boot Actuator.

Endpoints apropriados:

health;
metrics;
info.

Não expor endpoints administrativos perigosos.

## DOCKER

Avaliar Docker Compose completo:

* PostgreSQL;
* Kafka;
* Kafka UI;
* aplicação, se apropriado.

Adicionar healthchecks quando fizer sentido.

## CONFIGURAÇÃO

Garantir que:

* URLs;
* credentials;
* timeouts;
* retry;
* circuit breaker;
* Kafka;
* datasource

sejam configuráveis por environment variables.

Não utilizar secrets hardcoded.

## PERFORMANCE

Analise especialmente o fluxo:

POST /events/{id}/reservations

porque é o caminho crítico do flash sale.

Documente:

* lock;
* transaction;
* contention;
* database bottleneck;
* horizontal scaling.

Não tente resolver tudo adicionando Redis.

Se Redis não for necessário para o requisito atual, documente por que não foi utilizado.

## DOCUMENTAÇÃO

Criar seção:

"Scalability & Performance"

explicando como o sistema se comportaria com:

10;
100;
1.000;
10.000;
100.000

requisições concorrentes.

Não invente benchmarks.

Faça análise arquitetural.

Execute todos os testes.
