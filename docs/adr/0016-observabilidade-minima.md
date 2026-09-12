# ADR-0016 — Observabilidade mínima: correlação, métricas de negócio e sondas separadas

- **Status:** Aceita
- **Data:** 2026-09-12

## Contexto

O sistema roda com N instâncias atrás de um balanceador, e uma única venda deixa rastro em
três processos diferentes: o que atendeu o HTTP, o que drenou o outbox e o que consumiu a
mensagem. Sem identificador comum, investigar um incidente vira busca por horário aproximado.

Ao mesmo tempo, há um risco oposto: instalar rastreamento distribuído, log de auditoria e
dezenas de métricas produz a aparência de rigor e nenhum leitor. Observabilidade que ninguém
consulta é custo.

## Decisão

Instrumentar **apenas** o que responde a uma pergunta operacional concreta.

1. **Id de correlação** por requisição (`X-Correlation-Id`/`X-Request-Id`, reaproveitado se
   vier de fora, devolvido na resposta), mantido no `MDC` e limpo ao final. Propagado além do
   processo: gravado na linha do outbox, enviado como header Kafka e restaurado no `MDC` do
   consumidor. Jobs de fundo recebem id próprio por execução.
2. **Logs estruturados nativos do Spring Boot**: texto legível em desenvolvimento, JSON (ECS)
   em container, controlado por variável de ambiente. Sem dependência adicional.
3. **Métricas de negócio** que a plataforma não tem como inferir: reservas criadas, recusadas
   por motivo, canceladas, expiradas, assentos devolvidos por causa, e a fila do outbox como
   *gauge*. Latência, pool, JVM e circuit breaker vêm prontos e não são reimplementados.
4. **Sondas separadas**: `liveness` sem o banco; `readiness` com o banco e **sem o Kafka**.
5. **Superfície do Actuator restrita** a `health`, `info` e `metrics`, com teste que falha se
   um endpoint administrativo for exposto.

As duas exclusões do item 4 são o núcleo da decisão. Banco no liveness faria uma queda do
PostgreSQL reiniciar a frota inteira, atrasando a recuperação. Kafka no readiness faria uma
falha de integração tirar a aplicação de vendas do ar — o oposto do que o outbox garante.

## Consequências

- ✅ Um incidente é investigável ponta a ponta por um identificador, atravessando commit,
  broker e instâncias.
- ✅ "Esgotou" e "quebrou" deixam de ser indistinguíveis no painel, embora ambos sejam `409`.
- ✅ Uma integração parada é visível (fila do outbox crescendo) mesmo com a venda saudável.
- ✅ Nenhuma dependência nova: tudo vem de Actuator, Micrometer e do log estruturado nativo.
- ⚠️ As métricas ficam em memória; publicá-las exige um registro (Prometheus/OTLP) quando
  houver coletor.
- ⚠️ O id de correlação vem de fora e é higienizado antes de entrar no log — id com quebra de
  linha injetaria entradas falsas.
- ⚠️ Não há rastreamento distribuído com *spans* nem log de auditoria; se houver requisito de
  conformidade, ambos entram como trabalho próprio.
