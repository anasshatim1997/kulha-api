-- M3: merchants. No foreign key to the identity schema: modules only share ids.

CREATE TABLE merchant.merchants (
    id              uuid         PRIMARY KEY,
    owner_user_id   uuid         NOT NULL,
    name            varchar(120) NOT NULL,
    business_type   varchar(20)  NOT NULL,
    address         varchar(255) NOT NULL,
    city            varchar(80)  NOT NULL,
    phone           varchar(30)  NOT NULL,
    opening_hours   varchar(500) NOT NULL,
    status          varchar(20)  NOT NULL DEFAULT 'PENDING',
    decision_reason varchar(500),
    decided_by      uuid,
    decided_at      timestamptz,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ux_merchants_owner UNIQUE (owner_user_id),
    CONSTRAINT ck_merchants_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED')),
    CONSTRAINT ck_merchants_type CHECK (business_type IN ('BAKERY', 'CAFE', 'RESTAURANT', 'SUPERMARKET', 'OTHER'))
);
CREATE INDEX ix_merchants_status ON merchant.merchants (status, created_at);
