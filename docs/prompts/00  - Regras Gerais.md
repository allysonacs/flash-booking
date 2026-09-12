Você é um Staff/Senior Backend Engineer especialista em Java, Spring Boot, sistemas distribuídos, alta concorrência, sistemas de reservas/flash sale e preparação para entrevistas técnicas.

Vamos construir, de forma INCREMENTAL, um desafio técnico de Backend para uma posição de Engenheiro de Software Sênior / Especialista.

IMPORTANTE:

* NÃO implemente o projeto inteiro de uma vez.
* Nesta etapa você deve apenas preparar a fundação do projeto e documentar as decisões.
* Nas próximas etapas eu fornecerei novos prompts.
* Não antecipe funcionalidades que pertencem às próximas etapas.
* Não altere decisões arquiteturais já documentadas sem antes explicar o motivo.
* Não invente requisitos.
* Não adicione complexidade desnecessária apenas para parecer sofisticado.
* O projeto deve demonstrar maturidade de engenharia, mas manter uma arquitetura simples e compreensível.

## DESAFIO

Sistema de Reserva de Ingressos — Flash Booking.

O sistema possui eventos com capacidade limitada e funciona em modelo de flash sale.

Endpoints obrigatórios:

POST /events
GET /events/{id}
POST /events/{id}/reservations
GET /reservations/{id}
DELETE /reservations/{id}

Requisitos não funcionais:

* múltiplas instâncias simultâneas da API;
* nunca permitir oversell;
* expiração automática de reservas pendentes;
* idempotência;
* consistência eventual para disponibilidade;
* tratamento explícito de erros.

Restrições:

* linguagem/framework/banco livres, mas vamos utilizar Java 21 + Spring Boot;
* Gradle Wrapper;
* JPA/Hibernate;
* PostgreSQL;
* Docker Compose;
* testes automatizados;
* README obrigatório.

## DECISÕES BASE

Utilizaremos:

* Java 21
* Spring Boot
* Gradle Wrapper
* Spring Web / MVC
* Spring Data JPA
* Hibernate
* PostgreSQL
* Docker Compose
* JUnit 5
* Mockito
* Testcontainers quando fizer sentido
* Resilience4j posteriormente
* Kafka posteriormente
* Kafka UI posteriormente

A arquitetura principal deve ser MVC, porém organizada de forma limpa e respeitando SOLID.

Não quero inicialmente Clean Architecture, Hexagonal Architecture ou DDD excessivamente sofisticado.

A estrutura deve ser simples o suficiente para uma entrevista técnica, mas suficientemente madura para demonstrar conhecimento de engenharia.

## LOCAL DO PROJETO

O projeto deve ser criado exatamente em:

~/Documents/Projetos

Crie um novo diretório para o projeto.

Escolha um nome adequado, por exemplo:

flash-booking

Antes de criar qualquer arquivo, verifique se o diretório já existe.

Se existir, NÃO apague nada automaticamente.

## OBJETIVO DESTA ETAPA

Nesta primeira etapa:

1. Criar o projeto base.
2. Configurar Java 21.
3. Configurar Gradle Wrapper.
4. Configurar Spring Boot.
5. Configurar estrutura inicial MVC.
6. Configurar dependências básicas necessárias.
7. Criar README inicial.
8. Criar ARCHITECTURE.md.
9. Criar ADR inicial explicando as principais decisões.
10. Criar uma aplicação Spring Boot que inicialize corretamente.
11. Criar pelo menos um teste básico verificando que o contexto Spring sobe.

Não implementar ainda:

* Event
* Reservation
* PostgreSQL
* JPA entities
* Kafka
* Resilience4j
* idempotência
* expiração
* concorrência de reserva
* circuit breaker
* retry
* eventos assíncronos

Esses itens serão implementados em etapas posteriores.

## ESTRUTURA

Quero uma estrutura MVC clara e simples.

Use algo semelhante a:

src/main/java/.../
controller/
service/
repository/
entity/
dto/
exception/
config/

Não crie classes vazias apenas para preencher diretórios.

## SOLID

Desde o início, mantenha:

* Single Responsibility Principle
* Open/Closed Principle
* Dependency Inversion
* interfaces quando realmente agregarem valor
* controllers finos
* regras de negócio no service
* persistência no repository
* DTOs separados das entidades quando necessário

Evite abstrações artificiais.

## QUALIDADE

O projeto deve ter:

* código legível;
* nomes claros;
* package structure coerente;
* configuração externalizada;
* sem secrets hardcoded;
* sem código morto;
* sem TODO desnecessário;
* sem dependências que ainda não serão utilizadas.

## DOCUMENTAÇÃO

Crie ARCHITECTURE.md contendo:

1. objetivo do sistema;
2. requisitos funcionais;
3. requisitos não funcionais;
4. stack;
5. arquitetura MVC;
6. responsabilidades de cada camada;
7. decisões que ainda serão implementadas;
8. riscos técnicos conhecidos;
9. estratégia planejada para:

    * concorrência;
    * overselling;
    * idempotência;
    * expiração;
    * consistência eventual;
    * eventos;
    * resiliência;
    * escalabilidade.

IMPORTANTE:

Não implemente essas estratégias ainda.

Apenas documente a estratégia planejada.

## VALIDAÇÃO

Ao terminar:

1. execute o Gradle Wrapper;
2. compile;
3. execute os testes;
4. execute a aplicação;
5. confirme que o Spring Boot sobe corretamente.

Se houver erro, corrija-o antes de finalizar.

No final, me informe:

* estrutura criada;
* arquivos principais;
* dependências adicionadas;
* comandos executados;
* resultado dos testes;
* possíveis próximos passos.

NÃO prossiga para PostgreSQL ou JPA nesta etapa.
