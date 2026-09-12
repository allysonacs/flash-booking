Agora faça uma AUDITORIA FINAL completa do desafio técnico.

Não implemente novas funcionalidades automaticamente.

Primeiro faça uma análise completa.

O projeto precisa atender:

## FUNCIONAL

POST /events

GET /events/{id}

POST /events/{id}/reservations

GET /reservations/{id}

DELETE /reservations/{id}

## NÃO FUNCIONAL

* múltiplas instâncias;
* zero overselling;
* expiração automática;
* idempotência;
* consistência eventual;
* erros explícitos;
* resiliência;
* escalabilidade.

## STACK

* Java 21;
* Spring Boot;
* Gradle Wrapper;
* MVC;
* SOLID;
* JPA/Hibernate;
* PostgreSQL;
* Flyway;
* Docker Compose;
* Kafka;
* Kafka UI;
* Resilience4j;
* JUnit 5;
* Mockito;
* Testcontainers quando apropriado.

## AUDITORIA

Verifique:

### Arquitetura

* MVC realmente está sendo utilizado?
* Controllers estão finos?
* Services possuem regras de negócio?
* repositories cuidam apenas da persistência?
* SOLID está sendo respeitado?
* Existem abstrações desnecessárias?

### Concorrência

Verifique profundamente:

* overselling;
* locks;
* transactions;
* isolation;
* race conditions;
* idempotency race;
* cancellation vs expiration;
* multiple instances.

Tente encontrar cenários que poderiam quebrar a implementação.

### Banco

Verifique:

* migrations;
* constraints;
* unique indexes;
* índices;
* foreign keys;
* queries;
* N+1;
* connection pool.

### Kafka

Verifique:

* producer;
* consumer;
* idempotência;
* retries;
* outbox;
* consumer groups;
* eventual consistency;
* duplicação.

### Resiliência

Verifique:

* timeout;
* retry;
* circuit breaker;
* fallback;
* configuração.

### Testes

Verifique cobertura dos principais cenários.

Especialmente:

* concorrência;
* overselling;
* idempotência;
* expiração;
* cancelamento;
* Kafka;
* resiliência.

Não crie testes apenas para aumentar cobertura percentual.

Priorize comportamento crítico.

## TESTE DE ESTRESSE

Se for possível sem introduzir dependências desnecessárias, crie um teste de concorrência representativo do flash sale.

Exemplo:

capacidade = 100

1.000 requisições concorrentes

quantidade = 1

Resultado:

successful reservations <= 100

available capacity >= 0

successful reservations + available capacity == total capacity

Não invente métricas de performance.

## README

O README final deve conter:

1. descrição;
2. arquitetura;
3. stack;
4. requisitos;
5. como executar;
6. Docker Compose;
7. PostgreSQL;
8. Kafka;
9. Kafka UI;
10. endpoints;
11. exemplos de requests;
12. testes;
13. estratégia de concorrência;
14. estratégia de idempotência;
15. expiração;
16. resiliência;
17. escalabilidade;
18. decisões arquiteturais;
19. trade-offs;
20. limitações conhecidas.

## FINAL

Execute:

./gradlew clean test

Depois verifique se:

docker compose config

é válido.

Não finalize com testes quebrados.

Se encontrar problemas:

1. explique;
2. corrija;
3. execute novamente;
4. somente finalize quando tudo estiver consistente.

No final apresente:

1. arquitetura final;
2. principais decisões;
3. principais trade-offs;
4. pontos fortes;
5. pontos que um entrevistador provavelmente questionará;
6. possíveis melhorias futuras;
7. comandos para executar o projeto do zero.
