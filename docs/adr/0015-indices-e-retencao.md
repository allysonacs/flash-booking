# ADR-0015 — Índices do caminho crítico e retenção das tabelas de mensageria

- **Status:** Aceita
- **Data:** 2026-09-12
- **Relacionada:** revisa a decisão de adiamento registrada no ADR-0012

## Contexto

Até aqui o schema tinha apenas os índices criados pelas constraints: chaves primárias e a
unicidade da idempotência. Isso é suficiente enquanto as tabelas cabem em memória — o
PostgreSQL varre tudo e ninguém percebe. Três consultas, porém, crescem com o volume e são
executadas **por todas as instâncias, o tempo todo**:

- a varredura de expiração (`status='PENDING' AND expires_at <= now()`), a cada 30s;
- o relay do outbox (`status='PENDING'`), a cada 1s;
- as consultas por `event_id` em `reservations` — cuja chave estrangeira o PostgreSQL, ao
  contrário de outros bancos, **não indexa automaticamente**.

Há ainda um problema de ciclo de vida: `outbox_messages` e `processed_events` só crescem.
São dados operacionais dentro do mesmo banco que decide as vendas — índices maiores,
autovacuum mais caro, backup mais lento. É dívida silenciosa: não quebra hoje e é impossível
de pagar com pressa depois.

## Decisão

**Índices (migração V4), cada um ligado a uma consulta concreta:**

| Índice | Consulta | Forma |
|---|---|---|
| `ix_reservations_event_status` | reservas por evento; a FK | `(event_id, status)` |
| `ix_reservations_due` | varredura de expiração | `(expires_at)` **parcial** em `status='PENDING'` |
| `ix_outbox_messages_published` | retenção do outbox | `(published_at)` **parcial** em `status='PUBLISHED'` |
| `ix_processed_events_processed_at` | retenção das marcas | `(processed_at)` |

Os índices de fila são **parciais** de propósito: indexam a fila viva, que é uma fração
minúscula do histórico. O índice fica pequeno, cabe em memória e o `ORDER BY` sai dele já
ordenado.

O ADR-0012 havia adiado conscientemente o índice de expiração ("entra quando a varredura
aparecer nas consultas lentas"). Esta ADR o adota: a revisão de escalabilidade mostrou que
essa consulta roda em todas as instâncias e cresce com o histórico, que é justamente o perfil
em que esperar pela evidência custa caro.

**Não indexados, também de propósito:** `reservations.status` isolado (baixa seletividade; o
índice parcial cobre o caso que importa) e `events.status` (tabela pequena, acesso por PK).
Índice encarece toda escrita na tabela.

**Retenção:** um job periódico apaga, em lotes limitados, mensagens publicadas há mais de 7
dias e marcas de idempotência há mais de 14. A janela das marcas é maior que a retenção do
tópico de propósito: enquanto o Kafka puder reentregar a mensagem, a marca precisa existir.

## Consequências

- ✅ As consultas dos jobs deixam de degradar com o crescimento do histórico.
- ✅ A FK de `reservations` passa a ter índice, o que também protege operações sobre eventos.
- ✅ As tabelas de mensageria passam a ter tamanho proporcional ao trabalho, não ao tempo de
  vida do sistema.
- ⚠️ Quatro índices a mais encarecem cada escrita em `reservations` e no outbox. O desenho
  parcial limita o efeito à fila viva.
- ⚠️ Em tabela grande e em uso, estes índices precisam ser criados com `CREATE INDEX
  CONCURRENTLY`, fora de transação: um `CREATE INDEX` comum segura lock de escrita, o que
  durante uma flash sale é indistinguível de indisponibilidade.
- ⚠️ A retenção apaga evidência. Sete dias é o compromisso entre auditoria e tamanho; quem
  precisar de histórico longo deve arquivá-lo fora do banco transacional.
