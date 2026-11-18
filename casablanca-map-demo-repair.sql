BEGIN;
SELECT pg_advisory_xact_lock(hashtext('kulha-map-demo-v1'));

DO $$
BEGIN
    IF current_setting('kulha.allow_map_demo_seed', true) IS DISTINCT FROM 'true' THEN
        RAISE EXCEPTION 'Refusing demo repair. Set PGOPTIONS=-c kulha.allow_map_demo_seed=true for this psql session.';
    END IF;
    IF current_database() !~* '(^|[_-])(dev|test|local)([_-]|$)' AND current_database() !~* '^kulha$' THEN
        RAISE EXCEPTION 'Refusing demo repair in database %. Use a development/test/local database.', current_database();
    END IF;
END $$;

UPDATE merchant.merchants
SET status = 'APPROVED',
    decided_at = COALESCE(decided_at, now()),
    updated_at = now()
WHERE name LIKE '%[KULHA MAP DEMO]%'
  AND city = 'Casablanca'
  AND latitude IS NOT NULL
  AND longitude IS NOT NULL;

UPDATE offer.offers
SET status = 'PUBLISHED',
    quantity_reserved = 0,
    reservation_started_at = NULL,
    pickup_start = now() + interval '15 minutes',
    pickup_end = now() + interval '7 days',
    published_at = COALESCE(published_at, now()),
    version = version + 1,
    updated_at = now()
WHERE id IN (
    SELECT md5('kulha-map-demo-v1:offer:' || n::text)::uuid
    FROM generate_series(1, 16) AS n
)
AND merchant_id IN (
    SELECT id
    FROM merchant.merchants
    WHERE name LIKE '%[KULHA MAP DEMO]%'
      AND city = 'Casablanca'
);

DO $$
DECLARE
    merchant_count integer;
    offer_count integer;
BEGIN
    SELECT count(*) INTO merchant_count
    FROM merchant.merchants
    WHERE name LIKE '%[KULHA MAP DEMO]%'
      AND city = 'Casablanca'
      AND status = 'APPROVED'
      AND latitude IS NOT NULL
      AND longitude IS NOT NULL;

    SELECT count(*) INTO offer_count
    FROM offer.offers
    WHERE id IN (
        SELECT md5('kulha-map-demo-v1:offer:' || n::text)::uuid
        FROM generate_series(1, 16) AS n
    )
      AND status = 'PUBLISHED'
      AND pickup_end > now() + interval '30 minutes'
      AND quantity_total > quantity_reserved;

    IF merchant_count <> 8 OR offer_count <> 16 THEN
        RAISE EXCEPTION 'Demo repair incomplete: found % approved merchants and % reservable offers; expected 8 and 16.', merchant_count, offer_count;
    END IF;
END $$;

COMMIT;

SELECT
    (SELECT count(*) FROM merchant.merchants
     WHERE name LIKE '%[KULHA MAP DEMO]%' AND city='Casablanca'
       AND status='APPROVED' AND latitude IS NOT NULL AND longitude IS NOT NULL) AS stores,
    (SELECT count(*) FROM offer.offers
     WHERE id IN (SELECT md5('kulha-map-demo-v1:offer:' || n::text)::uuid FROM generate_series(1,16) n)
       AND status='PUBLISHED' AND pickup_end > now() + interval '30 minutes'
       AND quantity_total > quantity_reserved) AS reservable_offers;
