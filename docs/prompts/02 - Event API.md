Continue o projeto existente.

Antes de implementar:

* leia README.md;
* leia ARCHITECTURE.md;
* leia os ADRs;
* examine as entidades e repositories existentes;
* preserve as decisões anteriores.

Agora implemente a funcionalidade de eventos.

## ENDPOINTS

Implementar:

POST /events

GET /events/{id}

## POST /events

Deve permitir criar um evento.

Request:

* name
* capacity

Validações:

* name obrigatório;
* capacity > 0;
* valores inválidos devem gerar erro explícito.

Response deve possuir estrutura adequada para API REST.

Não exponha diretamente a entidade JPA como contrato da API.

Use DTOs.

## GET /events/{id}

Deve retornar:

* id;
* nome;
* capacidade total;
* disponibilidade;
* status;
* informações relevantes.

Se o evento não existir:

HTTP 404.

## CAMADAS

Controller:

* somente HTTP;
* validação de entrada;
* delegação.

Service:

* regras de negócio;
* criação;
* consulta.

Repository:

* persistência.

Não colocar regra de negócio no Controller.

## EXCEÇÕES

Criar tratamento global utilizando:

@RestControllerAdvice

Padronizar respostas de erro.

A API deve diferenciar pelo menos:

* recurso não encontrado;
* validação;
* erro de negócio;
* erro inesperado.

Evite retornar stack trace para o cliente.

## HTTP

Utilize códigos HTTP semanticamente corretos.

Por exemplo:

201 para criação;
200 para consulta;
400 para request inválido;
404 para recurso inexistente.

## TESTES

Criar:

* unit tests do service;
* controller tests;
* integration test com PostgreSQL.

Testar:

* criação válida;
* capacidade inválida;
* evento inexistente;
* consulta existente;
* validação de campos.

## QUALIDADE

Não criar abstrações desnecessárias.

Manter MVC.

SOLID.

Código preparado para evolução.

## DOCUMENTAÇÃO

Atualize README e ARCHITECTURE.md.

Documente:

* endpoints;
* exemplos de request/response;
* decisões importantes.

Não implementar ainda reservas.

Não implementar Kafka.

Não implementar resiliência.

Não implementar idempotência.

Não implementar expiração.

No final execute:

./gradlew clean test

e confirme que todos os testes passam.
