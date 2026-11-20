CREATE TABLE identity.user_preferences (
    user_id UUID PRIMARY KEY REFERENCES identity.users(id) ON DELETE CASCADE,
    preferred_city VARCHAR(80),
    preferred_district VARCHAR(100),
    locale VARCHAR(2) NOT NULL DEFAULT 'fr',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_user_preferences_locale CHECK (locale IN ('fr', 'en', 'ar'))
);
