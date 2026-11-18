-- Remove only records written by seed/seed-1000-offers.sql.
-- Each offer deletion requires both its deterministic generated UUID and exact KULHA seed marker.

BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';
SELECT pg_advisory_xact_lock(hashtext('kulha:seed:1000-offers:v1'));

DO $$
DECLARE
    deleted_offers integer;
    deleted_merchants integer;
BEGIN
    -- Match both the deterministic offer UUID and its exact marker. This is
    -- narrower than deleting every row that merely shares the marker prefix.
    WITH generated_offers AS (
        SELECT n,
               format('%s-%s-%s-%s-%s',
                   substr(h,1,8), substr(h,9,4), substr(h,13,4),
                   substr(h,17,4), substr(h,21,12))::uuid AS id,
               'KULHA_LOADTEST_1000_V1:' || lpad(n::text, 4, '0') AS marker
        FROM (
            SELECT n, md5('kulha-loadtest-v1:offer:' || n::text) AS h
            FROM generate_series(1,1000) AS n
        ) hashes
    )
    DELETE FROM offer.offers o
    USING generated_offers g
    WHERE o.id = g.id
      AND o.food_notes = g.marker;
    GET DIAGNOSTICS deleted_offers = ROW_COUNT;

    WITH seed_merchants AS (
        SELECT format('%s-%s-%s-%s-%s',
            substr(h,1,8), substr(h,9,4), substr(h,13,4), substr(h,17,4), substr(h,21,12))::uuid AS id
        FROM (
            SELECT md5('kulha-loadtest-v1:merchant:' || n::text) AS h
            FROM generate_series(1,40) AS n
        ) hashes
    )
    DELETE FROM merchant.merchants m
    USING seed_merchants s
    WHERE m.id = s.id
      AND right(m.name, length(' [KULHA-LOADTEST]')) = ' [KULHA-LOADTEST]'
      AND NOT EXISTS (SELECT 1 FROM offer.offers o WHERE o.merchant_id = m.id);
    GET DIAGNOSTICS deleted_merchants = ROW_COUNT;

    RAISE NOTICE 'Removed % tagged test offers and % isolated test merchants.', deleted_offers, deleted_merchants;
END $$;

COMMIT;
