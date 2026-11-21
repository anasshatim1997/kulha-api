ALTER TABLE notification.outbox_events
    ADD COLUMN dead_lettered_at TIMESTAMPTZ;

CREATE INDEX ix_notification_outbox_dispatch
    ON notification.outbox_events (available_at, occurred_at)
    WHERE published_at IS NULL AND dead_lettered_at IS NULL;
