CREATE TABLE notification.pickup_reminder_dispatches (
    reservation_id UUID PRIMARY KEY,
    dispatched_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
