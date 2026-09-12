-- ---------------------------------------------------------------------------
-- V3 — Publicação assíncrona de eventos de integração.
--
-- Duas tabelas, uma para cada ponta do problema de entrega:
--
--   outbox_messages   — resolve o dual write no PRODUTOR. O evento é gravado na
--                       mesma transação que a reserva; um relay o publica depois.
--                       Sem isso, "salvar e publicar" são duas escritas em dois
--                       sistemas sem transação comum: ou o evento some, ou ele
--                       anuncia uma reserva que o rollback desfez.
--
--   processed_events  — resolve a entrega repetida no CONSUMIDOR. Kafka entrega
--                       pelo menos uma vez, e o outbox reforça isso (publicar e
--                       commitar não são atômicos). Quem consome precisa saber
--                       dizer "essa mensagem eu já processei".
-- ---------------------------------------------------------------------------

CREATE TABLE outbox_messages (
    id             UUID         NOT NULL,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   UUID         NOT NULL,
    -- Chave de particionamento no Kafka. É o id do EVENTO (show), não o da reserva:
    -- é ele que define a ordem que importa — a das reservas de um mesmo show.
    partition_key  VARCHAR(100) NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        JSONB        NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    -- Quantas vezes o relay já tentou. Não limita a retentativa: serve para
    -- enxergar uma mensagem travada antes que ela vire um incidente.
    attempts       INTEGER      NOT NULL DEFAULT 0,
    last_error     TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,

    CONSTRAINT pk_outbox_messages PRIMARY KEY (id),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED')),
    CONSTRAINT ck_outbox_published_at
        CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
);

-- Índice parcial: o relay só pergunta pelo que está pendente, e a fila pendente é
-- minúscula perto do histórico. Indexar a tabela inteira seria pagar por linhas
-- que nunca mais serão consultadas.
CREATE INDEX ix_outbox_messages_pending
    ON outbox_messages (created_at)
    WHERE status = 'PENDING';

CREATE TABLE processed_events (
    message_id     UUID         NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    processed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- A chave é (mensagem, grupo), e não só a mensagem: grupos diferentes são
    -- consumidores diferentes, e cada um precisa processar a mensagem uma vez.
    CONSTRAINT pk_processed_events PRIMARY KEY (message_id, consumer_group)
);

COMMENT ON TABLE outbox_messages IS
    'Eventos de integração gravados na mesma transação do agregado; publicados por um relay';
COMMENT ON TABLE processed_events IS
    'Marcas de idempotência do consumidor: entrega at-least-once processada exactly-once';
