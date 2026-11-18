-- KULHA! load-test seed preflight (read-only)
-- Run from psql after Flyway has applied migrations V1 through V11.

\echo '=== PostgreSQL runtime ==='
SELECT current_database() AS database_name,
       current_setting('server_version') AS postgres_version,
       current_setting('server_version_num')::integer AS postgres_version_num;

\echo '=== Required tables ==='
WITH required_objects(object_name) AS (
    VALUES ('merchant.merchants'), ('offer.offers')
)
SELECT object_name,
       to_regclass(object_name) IS NOT NULL AS object_exists
FROM required_objects
ORDER BY object_name;

\echo '=== Required columns ==='
WITH required_columns(table_schema, table_name, column_name) AS (
    VALUES
        ('merchant','merchants','id'),
        ('merchant','merchants','owner_user_id'),
        ('merchant','merchants','name'),
        ('merchant','merchants','business_type'),
        ('merchant','merchants','address'),
        ('merchant','merchants','city'),
        ('merchant','merchants','district'),
        ('merchant','merchants','phone'),
        ('merchant','merchants','opening_hours'),
        ('merchant','merchants','pickup_instructions'),
        ('merchant','merchants','status'),
        ('merchant','merchants','decided_by'),
        ('merchant','merchants','decided_at'),
        ('merchant','merchants','created_at'),
        ('merchant','merchants','updated_at'),
        ('offer','offers','id'),
        ('offer','offers','merchant_id'),
        ('offer','offers','title'),
        ('offer','offers','description'),
        ('offer','offers','category'),
        ('offer','offers','price_minor'),
        ('offer','offers','original_price_minor'),
        ('offer','offers','quantity_total'),
        ('offer','offers','quantity_reserved'),
        ('offer','offers','reservation_started_at'),
        ('offer','offers','pickup_start'),
        ('offer','offers','pickup_end'),
        ('offer','offers','allergens_status'),
        ('offer','offers','allergens'),
        ('offer','offers','food_notes'),
        ('offer','offers','status'),
        ('offer','offers','published_at'),
        ('offer','offers','version'),
        ('offer','offers','created_at'),
        ('offer','offers','updated_at')
)
SELECT format('%I.%I.%I', r.table_schema, r.table_name, r.column_name) AS required_column,
       c.column_name IS NOT NULL AS column_exists
FROM required_columns r
LEFT JOIN information_schema.columns c
  ON c.table_schema = r.table_schema
 AND c.table_name = r.table_name
 AND c.column_name = r.column_name
ORDER BY r.table_schema, r.table_name, r.column_name;

\echo '=== Database constraints that govern the generated records ==='
SELECT n.nspname AS schema_name,
       t.relname AS table_name,
       c.conname AS constraint_name,
       pg_get_constraintdef(c.oid) AS definition
FROM pg_constraint c
JOIN pg_class t ON t.oid = c.conrelid
JOIN pg_namespace n ON n.oid = t.relnamespace
WHERE (n.nspname, t.relname) IN (('merchant','merchants'), ('offer','offers'))
ORDER BY n.nspname, t.relname, c.conname;

\echo '=== Merchant supply (informational; seed creates 40 isolated test merchants) ==='
SELECT status,
       city,
       count(*) AS merchant_count
FROM merchant.merchants
GROUP BY status, city
ORDER BY status, city;

\echo '=== Offer/category model ==='
SELECT to_regclass('offer.categories') AS category_table;
SELECT pg_get_constraintdef(c.oid) AS category_check_constraint
FROM pg_constraint c
WHERE c.conrelid = 'offer.offers'::regclass
  AND c.conname = 'ck_offers_category';

\echo '=== Relationship and media model ==='
SELECT c.conname AS foreign_key_name,
       pg_get_constraintdef(c.oid) AS definition
FROM pg_constraint c
WHERE c.contype = 'f'
  AND c.conrelid IN ('merchant.merchants'::regclass, 'offer.offers'::regclass)
ORDER BY c.conrelid::regclass::text, c.conname;

SELECT table_schema, table_name
FROM information_schema.tables
WHERE table_schema IN ('merchant','offer')
  AND table_type = 'BASE TABLE'
  AND (table_name ILIKE '%image%' OR table_name ILIKE '%media%' OR table_name ILIKE '%asset%')
ORDER BY table_schema, table_name;

\echo '=== Existing KULHA load-test data (safe to inspect before a rerun) ==='
SELECT count(*) AS tagged_test_merchants
FROM merchant.merchants
WHERE right(name, length(' [KULHA-LOADTEST]')) = ' [KULHA-LOADTEST]';

SELECT count(*) AS tagged_test_offers
FROM offer.offers
WHERE left(COALESCE(food_notes, ''), length('KULHA_LOADTEST_1000_V1:')) = 'KULHA_LOADTEST_1000_V1:';

\echo 'Preflight is read-only. Inspect any false `exists` result or missing constraint before running seed-1000-offers.sql.'
