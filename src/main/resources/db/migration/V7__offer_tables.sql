CREATE SCHEMA IF NOT EXISTS offer;

CREATE TABLE offer.offers (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL,
    title varchar(120) NOT NULL,
    description varchar(1000) NOT NULL,
    category varchar(30) NOT NULL,
    price_minor integer NOT NULL,
    original_price_minor integer,
    quantity_total integer NOT NULL,
    quantity_reserved integer NOT NULL DEFAULT 0,
    pickup_start timestamptz,
    pickup_end timestamptz,
    allergens_status varchar(10) NOT NULL DEFAULT 'UNKNOWN',
    allergens varchar(20)[] NOT NULL DEFAULT '{}',
    food_notes varchar(500),
    status varchar(20) NOT NULL DEFAULT 'DRAFT',
    published_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_offers_status CHECK (status IN ('DRAFT','PUBLISHED','UNPUBLISHED','SUSPENDED','EXPIRED','CANCELLED')),
    CONSTRAINT ck_offers_category CHECK (category IN ('BREAD_PASTRY','PREPARED_MEALS','GROCERIES','FRUIT_VEG','DRINKS','SURPRISE_BASKET','OTHER')),
    CONSTRAINT ck_offers_qty CHECK (quantity_total BETWEEN 1 AND 500 AND quantity_reserved BETWEEN 0 AND quantity_total),
    CONSTRAINT ck_offers_price CHECK (price_minor BETWEEN 100 AND 200000 AND (original_price_minor IS NULL OR original_price_minor >= price_minor)),
    CONSTRAINT ck_offers_window CHECK ((pickup_start IS NULL AND pickup_end IS NULL) OR pickup_end > pickup_start),
    CONSTRAINT ck_offers_allergens CHECK (allergens_status IN ('UNKNOWN','DECLARED') AND (allergens_status = 'DECLARED' OR cardinality(allergens) = 0))
);
CREATE INDEX ix_offers_merchant ON offer.offers (merchant_id, status, created_at DESC, id);
CREATE INDEX ix_offers_published_window ON offer.offers (status, pickup_end) WHERE status = 'PUBLISHED';
