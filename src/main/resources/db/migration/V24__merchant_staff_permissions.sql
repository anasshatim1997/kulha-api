ALTER TABLE merchant.merchant_memberships
    ADD COLUMN can_validate_pickups BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN can_view_orders BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN can_manage_offers BOOLEAN NOT NULL DEFAULT FALSE;
