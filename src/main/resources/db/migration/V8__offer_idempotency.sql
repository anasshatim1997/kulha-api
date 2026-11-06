CREATE TABLE offer.idempotency_keys (
    actor_user_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    response_snapshot jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (actor_user_id, idempotency_key)
);

CREATE INDEX ix_offer_idempotency_created_at ON offer.idempotency_keys (created_at);

ALTER TABLE offer.offers ADD COLUMN reservation_started_at timestamptz;
UPDATE offer.offers SET reservation_started_at = updated_at WHERE quantity_reserved > 0;
ALTER TABLE offer.offers ADD CONSTRAINT ck_offer_reservation_started
    CHECK (quantity_reserved = 0 OR reservation_started_at IS NOT NULL);

ALTER TABLE offer.offers DROP CONSTRAINT IF EXISTS ck_offers_window;
ALTER TABLE offer.offers ADD CONSTRAINT ck_offers_window CHECK (
    (pickup_start IS NULL AND pickup_end IS NULL)
    OR (pickup_start IS NOT NULL AND pickup_end IS NOT NULL AND pickup_end > pickup_start)
);
