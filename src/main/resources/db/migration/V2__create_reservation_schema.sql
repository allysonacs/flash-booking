-- ---------------------------------------------------------------------------
-- V2 — Reservas.
--
-- A tabela guarda o compromisso do cliente; a contagem de assentos continua em
-- event_inventory. As duas escritas acontecem na mesma transação, de modo que
-- não existe reserva sem assento reservado nem assento preso sem reserva.
-- ---------------------------------------------------------------------------

CREATE TABLE reservations (
    id              UUID         NOT NULL,
    event_id        UUID         NOT NULL,
    quantity        INTEGER      NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    idempotency_key VARCHAR(100),
    expires_at      TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_reservations PRIMARY KEY (id),
    CONSTRAINT fk_reservations_event FOREIGN KEY (event_id) REFERENCES events (id),
    CONSTRAINT ck_reservations_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_reservations_status
        CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED'))
);

-- Idempotência garantida pelo banco, e não por verificação prévia na aplicação:
-- "consultar e então inserir" é justamente a corrida que se quer evitar. O índice é
-- parcial porque requisições sem chave não competem entre si e não precisam ser indexadas.
CREATE UNIQUE INDEX ux_reservations_event_idempotency_key
    ON reservations (event_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

COMMENT ON COLUMN reservations.expires_at IS
    'Prazo de validade da reserva PENDING; o job que a aplica chega na fase de expiração';
