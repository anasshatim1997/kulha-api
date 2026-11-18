CREATE INDEX IF NOT EXISTS idx_offer_published_title_prefix
    ON offer.offers (lower(title) text_pattern_ops)
    WHERE status = 'PUBLISHED';

CREATE INDEX IF NOT EXISTS idx_offer_published_pickup_window
    ON offer.offers (pickup_end, merchant_id)
    WHERE status = 'PUBLISHED' AND pickup_end IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_merchant_approved_name_prefix
    ON merchant.merchants (lower(name) text_pattern_ops, id)
    WHERE status = 'APPROVED';

CREATE INDEX IF NOT EXISTS idx_merchant_approved_location_prefix
    ON merchant.merchants (lower(city) text_pattern_ops, lower(COALESCE(district, '')) text_pattern_ops, id)
    WHERE status = 'APPROVED';
