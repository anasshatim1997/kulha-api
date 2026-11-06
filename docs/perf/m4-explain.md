# M4 query plan evidence

Status: **NOT RUN**. A seeded 50,000-row database was not available in the preparation environment. Do not interpret the expected indexes below as measured execution-plan evidence.

## Seed and inspect

Run this on the `kulha_test` database after migrations. The seed spreads 50,000 rows across 1,000 synthetic merchant IDs (50 per merchant) and includes both expired and future pickup windows. These synthetic rows bypass merchant workflows and are only for the local performance database.

```sql
WITH seed AS (
    SELECT n,
           ('00000000-0000-0000-0000-' || lpad((((n - 1) % 1000) + 1)::text, 12, '0'))::uuid AS merchant_id,
           CASE WHEN n % 3 = 0 THEN 'PUBLISHED'
                WHEN n % 3 = 1 THEN 'UNPUBLISHED'
                ELSE 'DRAFT' END AS offer_status,
           CASE WHEN n % 10 = 0 THEN now() - interval '1 hour'
                ELSE now() + interval '2 hours' END AS pickup_end
    FROM generate_series(1, 50000) AS n
)
INSERT INTO offer.offers (id, merchant_id, title, description, category, price_minor,
    quantity_total, quantity_reserved, pickup_start, pickup_end, allergens_status,
    allergens, status, published_at, version, created_at, updated_at)
SELECT gen_random_uuid(), merchant_id, 'Perf seeded offer', 'Synthetic performance row', 'OTHER', 500,
       2, 0, pickup_end - interval '1 hour', pickup_end, 'UNKNOWN', '{}', offer_status,
       CASE WHEN offer_status = 'PUBLISHED' THEN now() ELSE NULL END,
       0, now() - (n * interval '1 second'), now()
FROM seed;

ANALYZE offer.offers;
```

Run the plans below. The merchant list plan uses synthetic merchant ID `00000000-0000-0000-0000-000000000001`:

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, title, category, price_minor, quantity_total, quantity_reserved,
       pickup_start, pickup_end, status, created_at, version
FROM offer.offers
WHERE merchant_id = '00000000-0000-0000-0000-000000000001'
  AND status = 'PUBLISHED'
ORDER BY created_at DESC, id
LIMIT 20 OFFSET 0;

EXPLAIN (ANALYZE, BUFFERS)
SELECT id
FROM offer.offers
WHERE status IN ('PUBLISHED','UNPUBLISHED')
  AND pickup_end IS NOT NULL
  AND pickup_end < now()
ORDER BY pickup_end, id
LIMIT 500
FOR UPDATE SKIP LOCKED;
```

Expected index candidates are `ix_offers_merchant (merchant_id,status,created_at DESC,id)` and `ix_offers_published_window (status,pickup_end) WHERE status='PUBLISHED'`. The runtime expiry statement uses this candidate-selection query inside an `UPDATE … RETURNING` CTE and executes it in batches of 500. The query includes `UNPUBLISHED`; the partial published-only index does not cover those rows, so PostgreSQL may reasonably choose a sequential scan depending on data distribution. Capture actual plans instead of asserting an index without measurement.

## Statement count

`OfferListStatementCountIntegrationTest.twentyOfferListAndCountUseTwoOfferQueriesNotNPlusOneQueries` wraps the DataSource connection and asserts exactly two prepared SQL statements for the 20-row offer summary projection plus the count query. The test is present but **NOT RUN** in this preparation environment. Merchant ownership resolution is a separate bounded merchant-API lookup; no per-offer SQL is issued.

## k6

`m4-offers.js` is a one-minute script with 50 list readers and 20 publish/unpublish writers. Run it only with a dedicated seeded test merchant/token/offer; do not target production. Record the real p95 and label it “local machine, not representative of production”.
