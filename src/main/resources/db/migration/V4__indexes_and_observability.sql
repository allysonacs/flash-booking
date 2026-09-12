-- ---------------------------------------------------------------------------
-- V4 — Índices do caminho crítico e rastreabilidade ponta a ponta.
--
-- Até aqui o schema tinha só os índices que as constraints criam sozinhas
-- (chaves primárias e a unicidade da idempotência). Isso basta enquanto as
-- tabelas cabem em memória: o PostgreSQL varre tudo e ninguém percebe. Os
-- índices abaixo existem para as consultas que crescem com o volume — e cada
-- um deles corresponde a uma consulta concreta do sistema, não a uma coluna
-- que "parecia útil indexar".
--
-- Nota de produção: em uma tabela grande e em uso, estes CREATE INDEX devem
-- ser executados com CONCURRENTLY, fora de transação (no Flyway, um script
-- anotado com `-- lock:none` / executeInTransaction=false). Aqui são criados
-- de forma simples porque as tabelas são novas.
-- ---------------------------------------------------------------------------

-- 1) reservations (event_id, status)
--
-- Cobre a chave estrangeira — que o PostgreSQL NÃO indexa automaticamente — e
-- as consultas "reservas deste evento", "quantas pendentes deste evento".
-- Sem ele, todo DELETE de evento e toda consulta por evento viram seq scan na
-- tabela que mais cresce no sistema.
CREATE INDEX ix_reservations_event_status
    ON reservations (event_id, status);

-- 2) reservations (expires_at) WHERE status = 'PENDING'
--
-- É a consulta da varredura de expiração, executada por TODAS as instâncias a
-- cada intervalo:
--     WHERE status = 'PENDING' AND expires_at <= now() ORDER BY expires_at
--
-- Parcial de propósito: reservas confirmadas, canceladas e expiradas nunca
-- aparecem nessa busca e formam a esmagadora maioria das linhas com o tempo.
-- O índice fica pequeno (só a fila viva), e o ORDER BY sai dele já ordenado.
-- O ADR-0012 havia adiado este índice conscientemente; o ADR-0015 o adota.
CREATE INDEX ix_reservations_due
    ON reservations (expires_at)
    WHERE status = 'PENDING';

-- 3) outbox_messages (published_at) WHERE status = 'PUBLISHED'
--
-- O índice de pendentes (V3) atende ao relay. Este atende ao lado oposto: a
-- limpeza periódica do histórico publicado. Sem ele, cada passada de retenção
-- varreria a tabela inteira — justamente a tabela que a retenção existe para
-- impedir de crescer sem limite.
CREATE INDEX ix_outbox_messages_published
    ON outbox_messages (published_at)
    WHERE status = 'PUBLISHED';

-- 4) processed_events (processed_at)
--
-- A chave primária (message_id, consumer_group) atende à deduplicação. A
-- retenção consulta por data, que a PK não ajuda a ordenar.
CREATE INDEX ix_processed_events_processed_at
    ON processed_events (processed_at);

-- 5) Correlação ponta a ponta
--
-- O id de correlação da requisição que originou o evento viaja com ele até o
-- consumidor. É o que permite pegar um log de erro no consumidor e chegar à
-- requisição HTTP que o causou, atravessando um commit, um relay e um broker.
ALTER TABLE outbox_messages
    ADD COLUMN correlation_id VARCHAR(64);

COMMENT ON COLUMN outbox_messages.correlation_id IS
    'Id de correlação da requisição que produziu o evento; propagado como header Kafka';
