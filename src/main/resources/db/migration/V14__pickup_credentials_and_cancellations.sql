-- Pickup QR credentials are opaque, hashed in storage, and invalidated transactionally on collection/cancellation/expiry.
ALTER TABLE reservation.reservations
    ADD COLUMN pickup_credential_hash char(64);

CREATE UNIQUE INDEX ux_reservation_pickup_credential_hash
    ON reservation.reservations (pickup_credential_hash)
    WHERE pickup_credential_hash IS NOT NULL;

-- Append-only cancellation feedback with reservation-time snapshots for future analytics.
CREATE TABLE reservation.cancellation_events (
    id uuid PRIMARY KEY,
    reservation_id uuid NOT NULL UNIQUE REFERENCES reservation.reservations(id),
    customer_id uuid NOT NULL,
    merchant_id uuid NOT NULL,
    offer_id uuid NOT NULL,
    offer_category varchar(30) NOT NULL,
    reason_code varchar(40) NOT NULL,
    comment varchar(500),
    actor_type varchar(20) NOT NULL DEFAULT 'CUSTOMER',
    pickup_start timestamptz NOT NULL,
    minutes_before_pickup bigint NOT NULL CHECK (minutes_before_pickup >= 0),
    quantity integer NOT NULL CHECK (quantity > 0),
    total_price_minor integer NOT NULL CHECK (total_price_minor > 0),
    cancelled_at timestamptz NOT NULL,
    CONSTRAINT ck_reservation_cancellation_reason CHECK
        (reason_code IN ('PICKUP_TIME','TOO_FAR','CHANGED_MIND','FOUND_ALTERNATIVE',
                         'OFFER_NOT_AS_EXPECTED','RESERVED_BY_MISTAKE','OTHER','NOT_PROVIDED')),
    CONSTRAINT ck_reservation_cancellation_actor CHECK (actor_type IN ('CUSTOMER','MERCHANT','SYSTEM'))
);

CREATE INDEX ix_reservation_cancellation_reason_time
    ON reservation.cancellation_events (reason_code, cancelled_at DESC);
CREATE INDEX ix_reservation_cancellation_segment
    ON reservation.cancellation_events (merchant_id, offer_category, cancelled_at DESC);
