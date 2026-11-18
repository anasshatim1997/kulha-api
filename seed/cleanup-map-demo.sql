BEGIN;
SELECT pg_advisory_xact_lock(hashtext('kulha-map-demo-v1'));
DO $$
BEGIN
    IF current_setting('kulha.allow_map_demo_seed', true) IS DISTINCT FROM 'true' THEN
        RAISE EXCEPTION 'Refusing map demo cleanup. Set PGOPTIONS=-c kulha.allow_map_demo_seed=true for this psql session.';
    END IF;
END $$;

WITH demo_merchants AS (
    SELECT id FROM merchant.merchants
    WHERE name LIKE '%[KULHA MAP DEMO]%'
), demo_offer_ids AS (
    SELECT md5('kulha-map-demo-v1:offer:' || n::text)::uuid AS id
    FROM generate_series(1,16) n
)
DELETE FROM offer.offers o
USING demo_offer_ids d, demo_merchants m
WHERE o.id=d.id AND o.merchant_id=m.id;

DELETE FROM merchant.merchants m
WHERE m.name LIKE '%[KULHA MAP DEMO]%'
  AND m.id IN (SELECT md5('kulha-map-demo-v1:merchant:' || n::text)::uuid FROM generate_series(1,8) n)
  AND NOT EXISTS (SELECT 1 FROM offer.offers o WHERE o.merchant_id=m.id);

COMMIT;
