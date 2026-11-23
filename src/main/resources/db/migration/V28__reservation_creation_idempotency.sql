-- V28 is intentionally guarded independently of Flyway's version ordering.
-- Flyway can run a later version when an intermediate version is absent from its
-- configured locations, so require the durable success marker written by V27.
DO $migration_guard$
BEGIN
    IF to_regclass('admin.migration_prerequisites') IS NULL THEN
        RAISE EXCEPTION
            'Cannot apply V28: V27 prerequisite marker table admin.migration_prerequisites is missing'
            USING ERRCODE = '55000';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM admin.migration_prerequisites
         WHERE migration_version = 27
    ) THEN
        RAISE EXCEPTION
            'Cannot apply V28: successful V27 migration marker (version 27) is missing'
            USING ERRCODE = '55000';
    END IF;
END
$migration_guard$;

-- A reservation creation key is unique per authenticated customer.
-- The reservation, pickup credential, notification outbox event and exact HTTP
-- response snapshot are committed in the same transaction by ReservationService.
CREATE TABLE reservation.idempotency_keys (
    -- Identity is a separate module; validate the authenticated customer in the application layer.
    -- ADR 001 forbids a cross-module FK here.
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
