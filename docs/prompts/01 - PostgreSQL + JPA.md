Continue o projeto existente.

Antes de alterar qualquer coisa:

1. leia o README.md;
2. leia ARCHITECTURE.md;
3. leia os ADRs existentes;
4. entenda a estrutura atual;
5. não reescreva o projeto.

Agora vamos implementar a camada de persistência.

## OBJETIVO

Adicionar:

* PostgreSQL;
* Docker Compose;
* Spring Data JPA;
* Hibernate;
* configuração de datasource;
* migrations;
* primeira entidade de domínio.

O projeto deve continuar executável localmente.

## DOCKER COMPOSE

Crie:

docker-compose.yml

O PostgreSQL deve rodar via Docker.

Utilize variáveis de ambiente para:

* POSTGRES_DB
* POSTGRES_USER
* POSTGRES_PASSWORD

Não coloque credenciais reais.

Adicione healthcheck do PostgreSQL.

A aplicação deverá conseguir aguardar o banco ficar saudável antes de iniciar quando executada pelo Docker Compose.

## DATABASE

Banco:

PostgreSQL.

Utilize uma versão estável e adequada para desenvolvimento.

Configure:

* datasource;
* connection pool;
* Hibernate;
* JPA.

Não utilize H2 como substituto do PostgreSQL.

O objetivo é que o JPA REALMENTE acesse o PostgreSQL.

## MIGRATIONS

Adicione Flyway.

Não dependa de:

spring.jpa.hibernate.ddl-auto=create

para criar o schema.

O schema deve ser controlado por migrations.

Use:

ddl-auto=validate

quando apropriado.

## PRIMEIRA ENTIDADE

Crie a entidade Event.

Campos mínimos:

* id
* name
* totalCapacity
* availableCapacity
* status
* createdAt

Analise se todos os campos realmente precisam existir.

Utilize UUID como identificador se considerar adequado para um sistema distribuído.

Explique a decisão.

## REPOSITORY

Criar:

EventRepository

utilizando Spring Data JPA.

Neste momento não implementar lógica de reserva concorrente.

Apenas preparar a persistência.

## CONFIGURAÇÃO

Separar configurações por ambiente quando fizer sentido.

Exemplo:

application.yml
application-local.yml
application-test.yml

Não criar configuração excessivamente complexa.

## TESTES

Adicionar testes que comprovem:

1. contexto Spring;
2. repository;
3. persistência real no PostgreSQL.

Utilize Testcontainers se fizer sentido para testes de integração.

Não faça testes falsos utilizando mocks para validar persistência.

O objetivo é demonstrar que:

Java → JPA/Hibernate → PostgreSQL

está realmente funcionando.

## DOCKER

O Docker Compose deve permitir:

docker compose up

e iniciar o PostgreSQL corretamente.

Não é necessário colocar a aplicação no Docker ainda, a menos que isso simplifique a execução.

## DOCUMENTAÇÃO

Atualize ARCHITECTURE.md explicando:

* por que PostgreSQL;
* por que JPA;
* por que Flyway;
* estratégia de migrations;
* estratégia de testes de integração;
* conexão entre aplicação e banco.

Crie/atualize ADR se necessário.

## VALIDAÇÃO

Execute:

./gradlew clean build

Execute os testes.

Suba o PostgreSQL.

Confirme que a aplicação consegue conectar ao banco.

Se algo falhar, corrija antes de finalizar.

Não implemente ainda:

* reservation;
* idempotência;
* Kafka;
* circuit breaker;
* retry;
* expiração;
* lógica de overselling.

No final, apresente:

* arquivos criados;
* arquivos alterados;
* comandos executados;
* resultado dos testes;
* como subir o PostgreSQL;
* como executar a aplicação.
