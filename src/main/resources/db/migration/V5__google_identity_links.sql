-- Stable external identity links; email addresses are not used as provider identifiers.
CREATE TABLE identity.external_identities (
    id            uuid         PRIMARY KEY,
    user_id       uuid         NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    provider      varchar(30)  NOT NULL,
    subject       varchar(255) NOT NULL,
    email         varchar(254) NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ux_external_identity_provider_subject UNIQUE (provider, subject),
    CONSTRAINT ux_external_identity_user_provider UNIQUE (user_id, provider),
    CONSTRAINT ck_external_identity_provider CHECK (provider IN ('GOOGLE'))
);
CREATE INDEX ix_external_identity_user ON identity.external_identities (user_id);
