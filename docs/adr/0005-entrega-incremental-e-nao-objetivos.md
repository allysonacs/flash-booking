# ADR-0005 — Entrega incremental e não-objetivos

- **Status:** Aceita
- **Data:** 2026-09-11

## Contexto

Todos os requisitos são conhecidos desde o início, e a tentação é montar a stack inteira na
primeira hora — banco, Kafka, circuit breaker, cache. Dependência declarada e não usada,
porém, é custo de build, superfície de CVE e ruído para quem revisa: sugere que o projeto
faz coisas que não faz.

## Decisão

Entregar em fases, **cada uma com seus próprios testes**, adicionando uma dependência
somente na fase em que ela passa a ser efetivamente usada. A Fase 0 (esta) contém apenas
build, aplicação, health check, testes e documentação — sem banco, sem JPA, sem Kafka.

Ficam deliberadamente **fora do escopo**, salvo se um requisito novo os justificar:

- **Redis** — o lock distribuído já é o Postgres; cache de disponibilidade só se pagaria em
  um volume de leitura que este sistema não tem.
- **Clean/Hexagonal Architecture, CQRS, Event Sourcing** — indireção sem ganho em um domínio
  de dois agregados (ver ADR-0001).
- **Saga** — não há transação distribuída a coordenar: o commit é um só.
- **ShedLock** — `SKIP LOCKED` resolve o agendamento distribuído sem dependência extra.
- **Schema Registry, service mesh** — fora da proporção do problema.

## Consequências

- ✅ Cada fase é revisável isoladamente e tem critério objetivo de "pronto".
- ✅ O `build.gradle.kts` descreve com honestidade o que o sistema realmente faz.
- ✅ Saber dizer "não" a uma tecnologia é parte da entrega, e fica registrado.
- ⚠️ Fases intermediárias entregam um sistema incompleto — esperado e sinalizado no README.
- ⚠️ Reverter um não-objetivo exige um novo ADR, e não uma decisão silenciosa no meio de um
  commit.
