-- Pickup QR credentials are random one-use secrets. Only their SHA-256 hashes are stored.
CREATE TABLE reservation.pickup_credentials (
    id uuid PRIMARY KEY,
    reservation_id uuid NOT NULL REFERENCES reservation.reservations(id) ON DELETE CASCADE,
    token_hash char(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_pickup_credentials_hash UNIQUE (token_hash)
);
CREATE INDEX ix_pickup_credentials_reservation
    ON reservation.pickup_credentials (reservation_id, expires_at DESC);
CREATE INDEX ix_pickup_credentials_active
    ON reservation.pickup_credentials (token_hash, expires_at)
    WHERE used_at IS NULL;

-- Immutable cancellation event/outbox record. Analytics consumers can process this
-- table asynchronously without coupling cancellation to a model or analytics service.
CREATE TABLE reservation.cancellation_events (
    event_id uuid PRIMARY KEY,
    reservation_id uuid NOT NULL REFERENCES reservation.reservations(id),
    reference varchar(40) NOT NULL,
    customer_id uuid NOT NULL,
    merchant_id uuid NOT NULL,
    offer_id uuid NOT NULL,
    offer_category varchar(30) NOT NULL,
    city varchar(80) NOT NULL,
    quantity integer NOT NULL,
    total_price_minor integer NOT NULL,
    pickup_start timestamptz NOT NULL,
    pickup_end timestamptz NOT NULL,
    seconds_before_pickup bigint NOT NULL,
    reason_code varchar(40) NOT NULL,
    reason_comment varchar(500),
    actor_type varchar(20) NOT NULL,
    actor_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    schema_version integer NOT NULL DEFAULT 1,
    processed_at timestamptz,
    processing_attempts integer NOT NULL DEFAULT 0,
    CONSTRAINT ux_cancellation_event_reservation UNIQUE (reservation_id),
    CONSTRAINT ck_cancellation_reason CHECK (reason_code IN (
        'NOT_SPECIFIED','PICKUP_TIME','TOO_FAR','CHANGED_MIND','FOUND_ALTERNATIVE',
        'OFFER_EXPECTATION','BOOKED_BY_MISTAKE','OTHER'
    ))
);
CREATE INDEX ix_cancellation_events_unprocessed
    ON reservation.cancellation_events (occurred_at)
    WHERE processed_at IS NULL;
CREATE INDEX ix_cancellation_events_analytics
    ON reservation.cancellation_events (occurred_at, city, offer_category, reason_code);
