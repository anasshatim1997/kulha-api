CREATE TABLE notification.notifications (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
    type VARCHAR(48) NOT NULL,
    title VARCHAR(180) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    action_label VARCHAR(80),
    action_url VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at TIMESTAMPTZ,
    CONSTRAINT ck_notification_type CHECK (type IN (
        'RESERVATION_CONFIRMED', 'PICKUP_REMINDER', 'RESERVATION_CANCELLED',
        'RESERVATION_COLLECTED', 'RESERVATION_EXPIRED', 'GENERAL'
    ))
);

CREATE INDEX ix_notifications_user_created
    ON notification.notifications (user_id, created_at DESC, id DESC);
CREATE INDEX ix_notifications_user_unread
    ON notification.notifications (user_id, created_at DESC)
    WHERE read_at IS NULL;

CREATE TABLE notification.notification_preferences (
    user_id UUID PRIMARY KEY REFERENCES identity.users(id) ON DELETE CASCADE,
    reservation_confirmations BOOLEAN NOT NULL DEFAULT TRUE,
    pickup_reminders BOOLEAN NOT NULL DEFAULT TRUE,
    cancellations_and_status_changes BOOLEAN NOT NULL DEFAULT TRUE,
    nearby_baskets BOOLEAN NOT NULL DEFAULT FALSE,
    reminder_minutes_before INTEGER NOT NULL DEFAULT 60,
    email_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sms_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_notification_reminder_minutes CHECK (reminder_minutes_before IN (15, 30, 60, 120, 1440))
);
