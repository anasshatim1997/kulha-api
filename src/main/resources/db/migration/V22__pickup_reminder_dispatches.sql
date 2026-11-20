CREATE TABLE notification.pickup_reminder_dispatches (
    reservation_id UUID PRIMARY KEY,
    notification_id UUID NOT NULL UNIQUE,
    dispatched_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
