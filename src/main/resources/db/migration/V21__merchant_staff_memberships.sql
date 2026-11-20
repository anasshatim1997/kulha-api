CREATE TABLE merchant.merchant_memberships (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL REFERENCES merchant.merchants(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    membership_role VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    invited_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_merchant_membership UNIQUE (merchant_id, user_id),
    CONSTRAINT ck_merchant_membership_role CHECK (membership_role IN ('PICKUP_STAFF')),
    CONSTRAINT ck_merchant_membership_status CHECK (status IN ('ACTIVE', 'REVOKED'))
);

CREATE UNIQUE INDEX ux_merchant_active_staff_user
    ON merchant.merchant_memberships (user_id)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_merchant_memberships_merchant
    ON merchant.merchant_memberships (merchant_id, status, created_at);
