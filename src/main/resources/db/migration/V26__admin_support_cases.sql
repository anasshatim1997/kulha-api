CREATE TABLE admin.support_cases (
    id UUID PRIMARY KEY,
    reference VARCHAR(32) NOT NULL UNIQUE,
    category VARCHAR(30) NOT NULL,
    subject VARCHAR(180) NOT NULL,
    description VARCHAR(3000) NOT NULL,
    priority VARCHAR(12) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    customer_user_id UUID NOT NULL REFERENCES identity.users(id),
    merchant_id UUID REFERENCES merchant.merchants(id),
    offer_id UUID REFERENCES offer.offers(id),
    reservation_reference VARCHAR(40),
    assigned_to UUID REFERENCES identity.users(id),
    hidden_offer BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    CONSTRAINT ck_support_case_category CHECK (category IN ('FOOD_SAFETY','ORDER_ISSUE','ACCOUNT','PRIVACY')),
    CONSTRAINT ck_support_case_priority CHECK (priority IN ('HIGH','MEDIUM','LOW')),
    CONSTRAINT ck_support_case_status CHECK (status IN ('OPEN','WAITING_REPLY','ESCALATED','RESOLVED'))
);
CREATE INDEX ix_support_cases_status_priority ON admin.support_cases(status, priority, created_at DESC);
CREATE INDEX ix_support_cases_customer ON admin.support_cases(customer_user_id, created_at DESC);
CREATE INDEX ix_support_cases_offer ON admin.support_cases(offer_id, status);

CREATE TABLE admin.support_case_timeline (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES admin.support_cases(id) ON DELETE CASCADE,
    author_type VARCHAR(16) NOT NULL,
    author_user_id UUID REFERENCES identity.users(id),
    author_label VARCHAR(120) NOT NULL,
    visibility VARCHAR(16) NOT NULL,
    message VARCHAR(3000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_support_timeline_author CHECK (author_type IN ('CUSTOMER','ADMIN','SHOP','SYSTEM')),
    CONSTRAINT ck_support_timeline_visibility CHECK (visibility IN ('CUSTOMER','INTERNAL'))
);
CREATE INDEX ix_support_timeline_case ON admin.support_case_timeline(case_id, created_at, id);
