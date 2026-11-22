-- A reservation creation key is unique per authenticated customer.
-- The reservation, pickup credential, notification outbox event and exact HTTP
-- response snapshot are committed in the same transaction by ReservationService.
CREATE TABLE reservation.idempotency_keys (
    customer_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    reservation_id UUID NOT NULL REFERENCES reservation.reservations(id),
    response_snapshot JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_reservation_idempotency PRIMARY KEY (customer_id, idempotency_key),
    CONSTRAINT uq_reservation_idempotency_reservation UNIQUE (reservation_id),
    CONSTRAINT ck_reservation_idempotency_key_nonempty CHECK (length(btrim(idempotency_key)) > 0)
);

CREATE INDEX ix_reservation_idempotency_created_at
    ON reservation.idempotency_keys (created_at);
