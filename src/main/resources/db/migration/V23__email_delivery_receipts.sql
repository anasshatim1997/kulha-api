CREATE TABLE notification.email_delivery_receipts (
    event_id UUID PRIMARY KEY,
    delivered_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
