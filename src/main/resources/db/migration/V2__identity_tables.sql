-- M2a: identity tables. Foreign keys stay inside the identity schema.

CREATE TABLE identity.users (
    id                uuid         PRIMARY KEY,
    email             varchar(254) NOT NULL,
    password_hash     varchar(255) NOT NULL,
    full_name         varchar(120) NOT NULL,
    phone             varchar(30),
    status            varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    email_verified_at timestamptz,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ux_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_lower CHECK (email = lower(email)),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE identity.user_roles (
    user_id uuid        NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    role    varchar(20) NOT NULL,
    PRIMARY KEY (user_id, role),
    CONSTRAINT ck_user_roles_role CHECK (role IN ('CUSTOMER', 'MERCHANT', 'ADMIN'))
);

-- Single-use tokens sent by email. Only the SHA-256 hash of the token is stored.
CREATE TABLE identity.email_tokens (
    id         uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    type       varchar(20) NOT NULL,
    token_hash char(64)    NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_email_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_email_tokens_type CHECK (type IN ('VERIFY', 'RESET'))
);
CREATE INDEX ix_email_tokens_user ON identity.email_tokens (user_id, type);

-- Rotating refresh tokens. All tokens of one login share a family_id.
-- Only the SHA-256 hash of the token is stored.
CREATE TABLE identity.refresh_tokens (
    id          uuid        PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    family_id   uuid        NOT NULL,
    token_hash  char(64)    NOT NULL,
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,
    replaced_by uuid,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_refresh_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX ix_refresh_tokens_family ON identity.refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_user ON identity.refresh_tokens (user_id);
