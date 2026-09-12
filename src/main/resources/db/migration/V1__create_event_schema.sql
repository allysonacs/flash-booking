-- ---------------------------------------------------------------------------
-- V1 — Schema inicial do domínio de eventos.
--
-- O inventário vive em uma tabela separada de propósito: é a única linha que
-- sofre contenção durante a flash sale. Isolá-la mantém o registro "quente"
-- pequeno (menos WAL e menos bloat sob muitos UPDATE) e evita que o lock da
-- venda bloqueie leitura e atualização dos metadados do evento.
-- ---------------------------------------------------------------------------

CREATE TABLE events (
    id             UUID         NOT NULL,
    name           VARCHAR(200) NOT NULL,
    total_capacity INTEGER      NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_events PRIMARY KEY (id),
    CONSTRAINT ck_events_total_capacity_positive CHECK (total_capacity > 0),
    CONSTRAINT ck_events_status CHECK (status IN ('ON_SALE', 'CLOSED', 'CANCELLED'))
);

CREATE TABLE event_inventory (
    event_id       UUID        NOT NULL,
    total_capacity INTEGER     NOT NULL,
    reserved_count INTEGER     NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_event_inventory PRIMARY KEY (event_id),
    CONSTRAINT fk_event_inventory_event FOREIGN KEY (event_id)
        REFERENCES events (id) ON DELETE CASCADE,
    CONSTRAINT ck_event_inventory_total_capacity_positive CHECK (total_capacity > 0),
    -- Rede de segurança contra oversell: nenhum bug de aplicação consegue
    -- persistir um estado inválido, mesmo que a lógica de reserva falhe.
    CONSTRAINT ck_event_inventory_no_oversell
        CHECK (reserved_count >= 0 AND reserved_count <= total_capacity)
);

COMMENT ON TABLE  event_inventory                IS 'Linha quente de inventário, isolada dos metadados do evento';
COMMENT ON COLUMN event_inventory.reserved_count IS 'Assentos já comprometidos; disponibilidade = total_capacity - reserved_count';
