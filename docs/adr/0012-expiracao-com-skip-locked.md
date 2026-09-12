# ADR-0012 — Expiração por varredura com `FOR UPDATE SKIP LOCKED`

- **Status:** Aceita
- **Data:** 2026-09-11

## Contexto

Reservas `PENDING` prendem assentos. Se ninguém as expira, um carrinho abandonado retira
ingressos da venda para sempre — numa flash sale, isso é perda direta de receita.

O trabalho precisa acontecer com N instâncias da aplicação rodando. As saídas usuais são:
eleger uma instância para rodar o job (e conviver com o ponto único de falha), usar um lock
distribuído como ShedLock ou Redis (e operar mais um componente), ou deixar todas rodarem e
resolver a disputa onde o estado já é único.

## Decisão

**Todas as instâncias rodam a varredura, o tempo todo.** A disputa é resolvida no PostgreSQL:

```sql
SELECT id FROM reservations
 WHERE status = 'PENDING' AND expires_at <= now()
 ORDER BY expires_at
 FOR UPDATE SKIP LOCKED
 LIMIT :batchSize
```

`FOR UPDATE` tranca as linhas até o commit; `SKIP LOCKED` faz o PostgreSQL **pular** o que
outra transação já travou, em vez de esperar. Cada instância leva um lote disjunto, sem
combinar nada com ninguém.

Cada lote é uma transação: reivindicar, aplicar `PENDING → EXPIRED` (com a mesma condição de
estado no `WHERE`) e devolver os assentos ao inventário acontecem juntos ou não acontecem.
O agendamento é `@Scheduled(fixedDelay)`, e a execução drena no máximo
`maxBatchesPerRun` lotes, deixando o resto para a próxima passada.

O corte de tempo é o `now()` do banco — com várias instâncias, é o único relógio comum.

## Consequências

- ✅ Sem eleição de líder, sem ShedLock, sem Redis: nenhuma dependência nova para operar.
- ✅ Sem ponto único de falha e sem instância ociosa — todas trabalham, em paralelo, em
  lotes que não se sobrepõem.
- ✅ Idempotente por construção: a reivindicação só enxerga `PENDING` e a transição é
  guardada pelo mesmo estado. Rodar duas vezes devolve os assentos uma vez.
- ✅ A corrida com o cancelamento se resolve sozinha, porque as duas operações partem de
  `PENDING` e ambas são guardadas por ele.
- ⚠️ Todas as instâncias consultam o banco a cada intervalo, mesmo sem nada a expirar. É uma
  consulta indexável e barata; o custo cresce com o número de instâncias, não com o volume.
- ⚠️ A expiração é *eventual*: uma reserva vence e continua ocupando assento até a próxima
  varredura. O intervalo é a latência máxima, e é configurável.
- ⚠️ `expires_at` é calculado com o relógio da aplicação e comparado com o do banco. Um
  desvio entre eles desloca o instante da expiração pelo tamanho do desvio — nunca a
  correção, que depende da transição de estado, não do relógio.
- ⚠️ ~~Sem índice dedicado em `(status, expires_at)` por ora~~ — **revisto pelo
  [ADR-0015](0015-indices-e-retencao.md)**: a revisão de escalabilidade mostrou que esta
  consulta roda em todas as instâncias a cada intervalo e cresce com o histórico. O índice
  parcial `ix_reservations_due` foi adotado na migração V4.
