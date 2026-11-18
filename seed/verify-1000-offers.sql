-- KULHA! seed verification and load-test distribution report.

\echo '=== Exact seed count and high-level health ==='
SELECT count(*) AS tagged_offers,
       count(DISTINCT food_notes) AS unique_offer_markers,
       (count(*) = 1000 AND count(DISTINCT food_notes) = 1000) AS exactly_1000_unique_test_offers,
       count(*) FILTER (WHERE status = 'PUBLISHED') AS published,
       count(*) FILTER (WHERE status = 'EXPIRED') AS expired,
       count(*) FILTER (WHERE status = 'UNPUBLISHED') AS unpublished,
       count(*) FILTER (WHERE status = 'SUSPENDED') AS suspended,
       count(*) FILTER (WHERE status = 'DRAFT') AS drafts,
       count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled
FROM offer.offers
WHERE left(COALESCE(food_notes, ''), length('KULHA_LOADTEST_1000_V1:')) = 'KULHA_LOADTEST_1000_V1:';

\echo '=== Counts by status ==='
SELECT status, count(*) AS offer_count
FROM offer.offers
WHERE left(COALESCE(food_notes, ''), length('KULHA_LOADTEST_1000_V1:')) = 'KULHA_LOADTEST_1000_V1:'
GROUP BY status
ORDER BY status;

\echo '=== Counts by supported category ==='
SELECT category, count(*) AS offer_count
FROM offer.offers
WHERE left(COALESCE(food_notes, ''), length('KULHA_LOADTEST_1000_V1:')) = 'KULHA_LOADTEST_1000_V1:'
GROUP BY category
ORDER BY category;

\echo '=== Counts by city ==='
SELECT m.city, count(*) AS offer_count
FROM offer.offers o
JOIN merchant.merchants m ON m.id = o.merchant_id
WHERE left(COALESCE(o.food_notes, ''), length('KULHA_LOADTEST_1000_V1:')) = 'KULHA_LOADTEST_1000_V1:'
GROUP BY m.city
ORDER BY m.city;

\echo '=== Counts by stock level (all lifecycle states) ==='
SELECT CASE
           WHEN o.quantity_total - o.quantity_reserved = 0 THEN 'SOLD_OUT'
           WHEN o.quantity_total - o.quantity_reserved BETWEEN 1 AND 3 THEN 'LOW_STOCK_1_TO_3'
           ELSE 'IN_STOCK_4_PLUS'
       END AS stock_level,
       count(*) AS offer_count
FROM offer.offers o
WHERE left(COALESCE(o.food_notes, ''), length('KULHA_LOADTEST_1000_V1:')) = 'KULHA_LOADTEST_1000_V1:'
GROUP BY stock_level
ORDER BY stock_level;

\echo '=== Published/search-eligible stock breakdown ==='
SELECT count(*) FILTER (
           WHERE o.status = 'PUBLISHED'
             AND o.pickup_end > CURRENT_TIMESTAMP + INTERVAL '30 minutes'
             AND m.status = 'APPROVED'
       ) AS indexed_candidate_count,
       count(*) FILTER (
           WHERE o.status = 'PUBLISHED'
             AND o.pickup_end > CURRENT_TIMESTAMP + INTERVAL '30 minutes'
             AND m.status = 'APPROVED'
             AND o.quantity_total > o.quantity_reserved
       ) AS default_available_only_count,
       count(*) FILTER (
           WHERE o.status = 'PUBLISHED'
             AND o.pickup_end > CURRENT_TIMESTAMP + INTERVAL '30 minutes'
             AND m.status = 'APPROVED'
             AND o.quantity_total = o.quantity_reserved
       ) AS searchable_sold_out_count
FROM offer.offers o
JOIN merchant.merchants m ON m.id = o.merchant_id
WHERE left(COALESCE(o.food_notes, ''), length('KULHA_LOADTEST_1000_V1:')) = 'KULHA_LOADTEST_1000_V1:';

\echo '=== Constraint/lifecycle sanity checks (expected invalid_count = 0) ==='
SELECT count(*) AS invalid_count
FROM offer.offers o
WHERE left(COALESCE(o.food_notes, ''), length('KULHA_LOADTEST_1000_V1:')) = 'KULHA_LOADTEST_1000_V1:'
  AND (
       o.price_minor NOT BETWEEN 100 AND 200000
       OR (o.original_price_minor IS NOT NULL AND o.original_price_minor < o.price_minor)
       OR o.quantity_total NOT BETWEEN 1 AND 500
       OR o.quantity_reserved NOT BETWEEN 0 AND o.quantity_total
       OR (o.quantity_reserved > 0 AND o.reservation_started_at IS NULL)
       OR ((o.pickup_start IS NULL) <> (o.pickup_end IS NULL))
       OR (o.pickup_start IS NOT NULL AND o.pickup_end <= o.pickup_start)
       OR (o.allergens_status = 'UNKNOWN' AND cardinality(o.allergens) <> 0)
       OR (o.status = 'PUBLISHED' AND (o.published_at IS NULL OR o.pickup_start IS NULL OR o.pickup_end IS NULL
                                      OR o.pickup_start <= CURRENT_TIMESTAMP
                                      OR o.pickup_end > CURRENT_TIMESTAMP + INTERVAL '7 days'
                                      OR o.pickup_end - o.pickup_start > INTERVAL '24 hours'))
       OR (o.status IN ('UNPUBLISHED','SUSPENDED','EXPIRED') AND o.published_at IS NULL)
       OR (o.status = 'EXPIRED' AND o.pickup_end >= CURRENT_TIMESTAMP)
       OR (o.status IN ('DRAFT','CANCELLED') AND o.published_at IS NOT NULL)
  );

\echo '=== Merchant seed count ==='
SELECT count(*) AS tagged_approved_test_merchants,
       count(DISTINCT city) AS city_count
FROM merchant.merchants
WHERE right(name, length(' [KULHA-LOADTEST]')) = ' [KULHA-LOADTEST]'
  AND status = 'APPROVED';
