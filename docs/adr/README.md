# Architecture Decision Records

Registro curto e datado das decisões que moldam o projeto: o **contexto** em que foram
tomadas, a **decisão** e as **consequências** aceitas. Uma decisão revista não é apagada —
ganha um novo ADR que a supersede.

| ADR | Título | Status | Data |
|---|---|---|---|
| [0001](0001-arquitetura-mvc-em-camadas.md) | Arquitetura MVC em camadas | Aceita | 2026-09-11 |
| [0002](0002-java-21-e-spring-boot-4.md) | Java 21 + Spring Boot 4.1.1 | Aceita | 2026-09-11 |
| [0003](0003-gradle-wrapper-kotlin-dsl.md) | Gradle Wrapper com Kotlin DSL e toolchain Java 21 | Aceita | 2026-09-11 |
| [0004](0004-postgres-como-ponto-de-coordenacao.md) | PostgreSQL como único ponto de coordenação | Aceita | 2026-09-11 |
| [0005](0005-entrega-incremental-e-nao-objetivos.md) | Entrega incremental e não-objetivos | Aceita | 2026-09-11 |
| [0006](0006-jpa-e-flyway-para-persistencia.md) | JPA/Hibernate para CRUD e Flyway para o schema | Aceita | 2026-09-11 |
| [0007](0007-identificadores-uuid.md) | Identificadores UUID gerados pela aplicação | Aceita | 2026-09-11 |
| [0008](0008-disponibilidade-derivada.md) | Disponibilidade derivada, não persistida | Aceita | 2026-09-11 |
| [0009](0009-contrato-de-erros-rfc7807.md) | Contrato de erros com RFC 7807 e códigos estáveis | Aceita | 2026-09-11 |
| [0010](0010-alocacao-por-update-condicional.md) | Alocação de assentos por `UPDATE` condicional atômico | Aceita | 2026-09-11 |
| [0011](0011-idempotencia-por-constraint-unica.md) | Idempotência por constraint única, resolvida no banco | Aceita | 2026-09-11 |
| [0012](0012-expiracao-com-skip-locked.md) | Expiração por varredura com `FOR UPDATE SKIP LOCKED` | Aceita | 2026-09-11 |
| [0013](0013-resiliencia-apenas-na-borda-externa.md) | Resiliência apenas na borda externa | Aceita | 2026-09-12 |
| [0014](0014-transactional-outbox-para-eventos.md) | Transactional Outbox para publicar eventos no Kafka | Aceita | 2026-09-12 |
| [0015](0015-indices-e-retencao.md) | Índices do caminho crítico e retenção das tabelas de mensageria | Aceita | 2026-09-12 |
| [0016](0016-observabilidade-minima.md) | Observabilidade mínima: correlação, métricas de negócio e sondas separadas | Aceita | 2026-09-12 |
