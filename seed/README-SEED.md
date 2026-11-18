# KULHA! — 1,000-offer PostgreSQL load-test dataset

This package was generated from the backend source and Flyway migrations in this repository. It is designed for a **development, test, or explicitly approved load-test database**, not for an unreviewed production database.

## Files

- `preflight.sql` — read-only schema, constraint, merchant, and category checks.
- `seed-1000-offers.sql` — transactional, repeatable seed of exactly 1,000 offers and 40 isolated test merchants.
- `cleanup-1000-offers.sql` — removes only the offers carrying the exact seed marker and then removes only the 40 deterministic test merchant rows, if no remaining offer references them.
- `verify-1000-offers.sql` — exact-count, status, category, city, stock, lifecycle, and search-eligibility reports.

## 1. Schema inspected in the backend

The project uses PostgreSQL 16 (`compose.yaml`) and Flyway migrations `V1` through `V11`. The offer persistence implementation is JDBC (`JdbcOfferRepository`); the inspected source does not use JPA entities as its offer persistence model.

### `merchant.merchants` (created by V3, extended by V6 and V9)

| Column | SQL type | Seed behavior |
|---|---|---|
| `id` | `uuid` PK | Stable UUID derived from a KULHA test namespace; no database default exists. |
| `owner_user_id` | `uuid` NOT NULL UNIQUE | Stable synthetic UUID. V3 explicitly says there is no FK to `identity.users`. No login account is created. |
| `name` | `varchar(120)` NOT NULL | Realistic local shop name with `[KULHA-LOADTEST]` suffix. |
| `business_type` | `varchar(20)` NOT NULL | Only `BAKERY`, `CAFE`, `RESTAURANT`, `SUPERMARKET`, `OTHER`. |
| `address` | `varchar(255)` NOT NULL | Moroccan street address. |
| `city` | `varchar(80)` NOT NULL | Casablanca, Rabat, Marrakech, Tanger, Fès, Agadir, Meknès, Kénitra, Oujda, Tétouan, Béni Mellal. |
| `district` | `varchar(80)` | Local district/neighborhood. |
| `phone` | `varchar(30)` NOT NULL | Synthetic Moroccan-format contact number. |
| `opening_hours` | `varchar(500)` NOT NULL | Human-readable opening schedule. |
| `pickup_instructions` | `varchar(500)` | Local pickup note. |
| `status` | `varchar(20)` NOT NULL | `APPROVED`, so the existing public-profile lookup and search indexer accept these merchants. |
| `decision_reason`, `decided_by`, `decided_at` | nullable | No fake admin/user records are created. `decided_at` is populated. |
| `created_at`, `updated_at` | `timestamptz` | Current transaction timestamp. |

V3 has the `ux_merchants_owner` uniqueness constraint and `ck_merchants_status` / `ck_merchants_type` checks. The source documents no cross-schema FKs between modules. The seed checks that `owner_user_id` has not acquired an FK before creating synthetic owner IDs.

### `offer.offers` (created by V7, extended by V8)

| Column | SQL type | Seed behavior |
|---|---|---|
| `id` | `uuid` PK | Deterministic UUID derived from a KULHA test namespace. No identity/sequence default exists. |
| `merchant_id` | `uuid` NOT NULL | Points to one of the 40 inserted, verified `APPROVED` synthetic merchants. V7 does not declare a PostgreSQL FK here; the seed still joins to a real merchant row. |
| `title` | `varchar(120)` NOT NULL | French, Arabic, or English, localized and category-specific. |
| `description` | `varchar(1000)` NOT NULL | Localized offer description. |
| `category` | `varchar(30)` NOT NULL | One of the exact values in `ck_offers_category`: `BREAD_PASTRY`, `PREPARED_MEALS`, `GROCERIES`, `FRUIT_VEG`, `DRINKS`, `SURPRISE_BASKET`, `OTHER`. There is no category table or category FK. |
| `price_minor` | `integer` NOT NULL | MAD centimes, matching ADR-004 and the OpenAPI contract. Values are 100–200,000. |
| `original_price_minor` | nullable `integer` | Discount reference price; always at least `price_minor`. |
| `quantity_total` | `integer` NOT NULL | 1–40 in this dataset (database permits 1–500). |
| `quantity_reserved` | `integer` NOT NULL | 0–`quantity_total`; includes available, low-stock, and sold-out examples. |
| `reservation_started_at` | nullable `timestamptz` | Populated whenever `quantity_reserved > 0`, satisfying V8's check. |
| `pickup_start`, `pickup_end` | nullable `timestamptz` | Both null for DRAFT/CANCELLED; otherwise both set. Published windows start in the future, last 2–8 hours, and end within 7 days. EXPIRED windows are in the past. |
| `allergens_status` | `varchar(10)` | `UNKNOWN` or `DECLARED`. |
| `allergens` | `varchar(20)[]` | Empty for `UNKNOWN`; otherwise only allergen constants supported by the domain model. |
| `food_notes` | nullable `varchar(500)` | Contains the cleanup marker `KULHA_LOADTEST_1000_V1:NNNN`. This is the only per-offer test-data marker available in the inspected schema. It may be visible anywhere the API displays food notes. |
| `status` | `varchar(20)` | Only the six allowed values. Status/window/publication timestamps follow the domain lifecycle. |
| `published_at` | nullable `timestamptz` | Null for DRAFT/CANCELLED; populated for PUBLISHED/UNPUBLISHED/SUSPENDED/EXPIRED. |
| `version` | `bigint` | Set to a plausible lifecycle version. |
| `created_at`, `updated_at` | `timestamptz` | Historic creation and current update timestamps. |

V7/V8 checks covered by the seed are `ck_offers_status`, `ck_offers_category`, `ck_offers_qty`, `ck_offers_price`, `ck_offers_window`, `ck_offers_allergens`, and `ck_offer_reservation_started`.

### Lifecycle and inventory rules followed

The domain `Offer` allows `DRAFT -> PUBLISHED`, `PUBLISHED -> UNPUBLISHED`, `PUBLISHED/UNPUBLISHED -> SUSPENDED`, `SUSPENDED -> UNPUBLISHED`, and DRAFT cancellation. The expiry job expires PUBLISHED/UNPUBLISHED offers after `pickup_end`. This seed uses:

- 650 PUBLISHED offers with future pickup windows;
- 100 EXPIRED offers with elapsed pickup windows;
- 80 UNPUBLISHED offers with future pickup windows;
- 70 SUSPENDED offers with future pickup windows;
- 60 DRAFT offers without a pickup window;
- 40 CANCELLED drafts without a pickup window.

The stock checks never reserve more than the total, and every reserved offer has `reservation_started_at`. PUBLISHED sold-out offers remain PUBLISHED with zero available units; this is consistent with the application: suggestions/default public search filter them out, while search with `availableOnly=false` can include them.

### Images and media

The inspected V1–V11 migrations and offer search document contain no offer image/media relation or image column. Therefore this seed intentionally creates no images and adds no invented media tables/columns.

## 2. Safe execution on Windows PowerShell

### Prerequisites

1. Copy `.env.example` to `.env`, replace the local development database password, then start the stack from the repository root with `.\scripts\dev-up.ps1` (PostgreSQL, Elasticsearch, and Mailpit).
2. Ensure Flyway has run through the current migrations. The Spring Boot application runs Flyway at startup; start it once if this database has not been migrated, then stop it.
3. Install the PostgreSQL `psql` command-line client and make sure it is on `PATH`.
4. Run these scripts against a development/test/load-test database. The seed refuses database names without a delimited `dev`, `test`, `local`, `load`, or `loadtest` segment unless you explicitly opt in for that session.

### Load connection settings from `.env` (without reading the database password into a command)

Run from the project root in PowerShell (the ZIP includes a safe template, not your private `.env`):

```powershell
Copy-Item .\.env.example .\.env
# Edit .env and set a local database password before starting Docker Compose.
notepad .\.env
foreach ($line in Get-Content .\.env) {
    if ($line -match '^(DB_PORT|DB_NAME|DB_USER)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim().Trim('"'), 'Process')
    }
}
$port = if ($env:DB_PORT) { $env:DB_PORT } else { '5433' }
```

If you do not use `.env`, set `$env:DB_NAME`, `$env:DB_USER`, and `$port` manually. The local Compose port defaults to `5433` on Windows (`compose.yaml`). `psql -W` prompts for the database password; do not paste it into the command line.

### Preflight, seed, verify

```powershell
psql -h 127.0.0.1 -p $port -U $env:DB_USER -d $env:DB_NAME -W -v ON_ERROR_STOP=1 -f .\seed\preflight.sql
```

Review the output first. In particular, confirm the required tables/columns and offer checks exist and that `offer.categories` is absent (the category is a constrained string in this schema).

For a local database named simply `kulha`, explicitly enable the load-test seed for the psql session:

```powershell
$env:PGOPTIONS = '-c kulha.allow_load_seed=true'
$env:PGCLIENTENCODING = 'UTF8'
psql -h 127.0.0.1 -p $port -U $env:DB_USER -d $env:DB_NAME -W -v ON_ERROR_STOP=1 -f .\seed\seed-1000-offers.sql
Remove-Item Env:PGOPTIONS
Remove-Item Env:PGCLIENTENCODING
```

If the database name already has a delimited `dev`, `test`, `local`, `load`, or `loadtest` segment, the explicit opt-in is not required. The seed is one transaction and takes a transaction-scoped advisory lock. It creates only 40 isolated tagged merchants and 1,000 tagged offers, never updates or deletes existing offers, and raises an exception (rolling back) unless exactly 1,000 unique tagged offers and all 40 approved test merchants exist at the end. IDs are deterministically derived because the inspected UUID columns have no database-generated defaults; there are no sequences/identity columns to delegate to.

```powershell
psql -h 127.0.0.1 -p $port -U $env:DB_USER -d $env:DB_NAME -W -v ON_ERROR_STOP=1 -f .\seed\verify-1000-offers.sql
```

### Cleanup

```powershell
psql -h 127.0.0.1 -p $port -U $env:DB_USER -d $env:DB_NAME -W -v ON_ERROR_STOP=1 -f .\seed\cleanup-1000-offers.sql
```

Cleanup deletes an offer only when both its deterministic seed UUID and its exact `KULHA_LOADTEST_1000_V1:NNNN` marker match one of the 1,000 generated records. It then considers only the 40 deterministic merchant UUIDs and only rows whose names still end in `[KULHA-LOADTEST]`; a merchant is deleted only when no offer (tagged or otherwise) still references it. No normal merchant, customer, user, reservation, or offer is targeted.

## 3. Elasticsearch indexing (mandatory)

**Do not expect a direct SQL insert to update Elasticsearch.** PostgreSQL is the transactional source of truth; the existing backend indexing implementation is:

1. Normal offer mutations publish an `OfferEventEnvelope` after commit. `OfferSearchIndexListener` queues the offer ID and sends Elasticsearch bulk operations in batches of 50. Only PUBLISHED offers from APPROVED merchants whose pickup end remains outside the reservation cutoff are indexed. Other states are deleted from the index.
2. `OfferSearchRebuildListener` runs on `ApplicationReadyEvent` when `app.search.elasticsearch.rebuild-on-startup=true` (default true). It scans every offer ID from PostgreSQL in batches of 250 and queues IDs through the same index listener. The worker then reads the current offer and approved merchant profile and indexes/deletes it.

There is **no HTTP reindex endpoint** and no supported CLI reindex command in the inspected source. Do not call or invent one. After seeding, restart the backend process so the existing startup reconciliation runs:

```powershell
# Stop the currently running backend (Ctrl+C), then from the project root:
$env:ELASTICSEARCH_OFFERS_INDEX = 'kulha-offers-v3'
$env:ELASTICSEARCH_REBUILD_ON_STARTUP = 'true'
.\mvnw.cmd spring-boot:run
```

For your deployment, set the same environment variables in the backend service configuration and restart that service. The default index was changed to `kulha-offers-v3` because an Elasticsearch index mapping is immutable for incompatible field definitions. If your environment explicitly overrides `ELASTICSEARCH_OFFERS_INDEX` to `kulha-offers-v1`, change that override to `kulha-offers-v3` before restarting. The old index is not deleted by this project change.

The corrected mapping uses Arabic-analyzed sibling root fields (`titleArabic`, `descriptionArabic`, `merchantNameArabic`, `cityArabic`, `districtArabic`) and one-level autocomplete subfields. It avoids the invalid chained `title.ar.autocomplete` mapping that caused the supplied integration-test failures while retaining French/English and Arabic search/autocomplete.

For the local Elasticsearch container from this project (`infra/elasticsearch/docker-compose.yml`), you can count seeded-merchant documents after startup reconciliation using:

```powershell
$body = '{"query":{"wildcard":{"merchantName.keyword":"*kulha-loadtest*"}}}'
curl.exe -s -X POST "http://localhost:9200/kulha-offers-v3/_count" -H "Content-Type: application/json" -d $body
```

The expected count is up to the 650 PUBLISHED offers, provided the worker has finished its reconciliation and the configured reservation cutoff has not been unusually increased. The local container has no Elasticsearch authentication; a secured deployment must use its configured HTTPS URL and credentials without exposing them in shell history.

## 4. Dataset coverage

- Seven schema-supported categories, including Moroccan breads/pastries, prepared meals (tajine, couscous, pastilla, sandwiches/snacks), groceries (including dairy), fruit/vegetables, drinks, surprise baskets, and OTHER.
- 11 cities and many districts, with 25 offers per synthetic merchant.
- French, Arabic, and English titles/descriptions.
- MAD prices and discounted original prices; low, medium, and high price variety.
- Total and reserved inventory including available, low-stock, and sold-out states.
- Multiple future pickup offsets/durations and past pickup windows for expired offers.
- Every supported offer lifecycle status.
- Food notes markers allow exact count, verification, and cleanup without adding fake columns.

## 5. Assumptions and limitations

- The dataset follows the current V1–V11 migration/schema state. The seed deliberately fails if required columns or named checks change.
- Test merchants are created instead of attaching fake offers to existing real merchants. This avoids altering or visually polluting live shop accounts and supplies city/district test coverage in a deterministic way.
- No user accounts are generated for the synthetic merchants because V3 has no owner-user FK. Consequently, these merchant records are for public discovery/search load tests, not authenticated merchant-dashboard tests.
- No reservations/payments are created. The current migration set has no reservation/payment tables to seed, and reservation records are not needed to test offer inventory counters.
- No image/media rows are created because no such relation exists in the current migrations.
- The indexer is eventually consistent and uses an in-memory retry queue. The supported recovery path in this code is startup reconciliation; it is not a durable outbox or a live reindex endpoint.
- Seed `food_notes` contain the KULHA test marker. This is necessary because the current offer schema has no dedicated test-data tag column; it may appear in an API response that exposes food notes.
