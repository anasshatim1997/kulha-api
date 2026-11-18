-- Offer and merchant values are reservation-time snapshots. Cross-module foreign keys are intentionally avoided.
CREATE TABLE reservation.reservations (
    id uuid PRIMARY KEY,
    reference varchar(40) NOT NULL,
    customer_id uuid NOT NULL,
    offer_id uuid NOT NULL,
    merchant_id uuid NOT NULL,
    offer_title varchar(120) NOT NULL,
    offer_category varchar(30) NOT NULL,
    merchant_name varchar(120) NOT NULL,
    merchant_address varchar(255) NOT NULL,
    merchant_city varchar(80) NOT NULL,
    merchant_pickup_instructions varchar(500),
    pickup_start timestamptz NOT NULL,
    pickup_end timestamptz NOT NULL,
    pickup_code varchar(12) NOT NULL,
    quantity integer NOT NULL,
    unit_price_minor integer NOT NULL,
    total_price_minor integer NOT NULL,
    status varchar(20) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_reservations_reference UNIQUE (reference),
    CONSTRAINT ck_reservations_status CHECK (status IN ('PENDING','CONFIRMED','COMPLETED','CANCELLED','EXPIRED')),
    CONSTRAINT ck_reservations_window CHECK (pickup_end > pickup_start),
    CONSTRAINT ck_reservations_quantity CHECK (quantity BETWEEN 1 AND 500),
    CONSTRAINT ck_reservations_price CHECK (unit_price_minor > 0 AND total_price_minor = unit_price_minor * quantity)
);
CREATE INDEX ix_reservations_customer_upcoming
    ON reservation.reservations (customer_id, pickup_start ASC, created_at ASC)
    WHERE status IN ('PENDING','CONFIRMED');
CREATE INDEX ix_reservations_customer_history
    ON reservation.reservations (customer_id, pickup_start DESC, created_at DESC)
    WHERE status IN ('COMPLETED','EXPIRED','CANCELLED');
CREATE INDEX ix_reservations_merchant
    ON reservation.reservations (merchant_id, pickup_start DESC);
