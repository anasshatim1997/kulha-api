# KULHA Map Demo Dataset

This is a local-only visual test dataset for the customer map. It inserts **8 synthetic Casablanca shops and 16 active offers** with GPS coordinates so the map can be inspected without waiting for real merchants to add locations.

The names contain `[KULHA MAP DEMO]` so they are clearly distinguishable from real businesses. The records do not create identity users or merchant login accounts. They exist only to exercise public offers, shop grouping, map pins, distance sorting and best-offer previews.

## Dataset

| District | Demo shop | Pins | Offers |
|---|---|---:|---:|
| Maarif | Boulangerie Nour | 1 | 2 |
| Gauthier | Pâtisserie Al Baraka | 1 | 2 |
| Bourgogne | Café Bahia | 1 | 2 |
| Racine | Maison du Tajine | 1 | 2 |
| Californie | Marché Californie | 1 | 2 |
| Oasis | Le Fournil de l’Oasis | 1 | 2 |
| Habous | Snack Casa Medina | 1 | 2 |
| Aïn Diab | Épicerie Anfa | 1 | 2 |

All 16 offers are published, have stock, and use pickup windows beginning one to three hours after the seed runs. Prices are in MAD centimes, matching the production schema.

## Safety and repeatability

- The seed runs in one transaction and takes an advisory lock.
- It requires an explicit `PGOPTIONS` opt-in: `-c kulha.allow_map_demo_seed=true`.
- It refuses database names other than the local `kulha` database or names containing a delimited `dev`, `test`, or `local` segment.
- It verifies the expected schema/migrations and refuses if the merchant owner-ID model has changed.
- IDs are deterministic, so rerunning the seed updates only its own demo records.
- Conflict updates are restricted to records already carrying the demo marker/IDs.
- Cleanup deletes only the 16 deterministic demo offer IDs and the 8 deterministic demo merchant IDs with the demo marker.

Do not run this against a production database. The explicit opt-in is required even for a database named `kulha`.

## 1. Start local services and apply migrations

Run from `backend/kulha-api` in PowerShell:

```powershell
.\scripts\dev-up.ps1
.\mvnw.cmd spring-boot:run
```

Wait for Spring Boot to report that the application started, then stop it with `Ctrl+C`. This ensures Flyway has applied the merchant geolocation, district, pickup-instruction and offer lifecycle migrations before the seed runs.

## 2. Load the local database connection values

The backend's local Compose setup reads `.env`. Load only the connection settings needed by `psql` (the password is never placed on the command line):

```powershell
foreach ($line in Get-Content .\.env) {
    if ($line -match '^(DB_PORT|DB_NAME|DB_USER)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim().Trim('"'), 'Process')
    }
}
$port = if ($env:DB_PORT) { $env:DB_PORT } else { '5433' }
$dbName = if ($env:DB_NAME) { $env:DB_NAME } else { 'kulha' }
$dbUser = if ($env:DB_USER) { $env:DB_USER } else { 'postgres' }
```

Install the PostgreSQL command-line client (`psql`) and ensure it is on `PATH`. The local Compose port defaults to `5433` on Windows. `-W` prompts for the password.

## 3. Insert and verify the fake stores

The SQL and helper explicitly set PostgreSQL's client encoding to UTF-8. This is important on Windows: never run a UTF-8 seed through a non-UTF-8 `psql` client encoding, or accented names can be stored as mojibake.

The simplest option is to run the included PowerShell helper from the backend folder:

```powershell
.\seed\seed-map-demo.ps1
```

It reads only `DB_PORT`, `DB_NAME` and `DB_USER` from `.env`, prompts for the database password, opts in for this session, runs the transaction and verifies the result. It refuses database names other than the local `kulha` database or names containing a delimited `dev`, `test` or `local` segment.

You can also run the SQL files separately with `psql` using `PGOPTIONS=-c kulha.allow_map_demo_seed=true`. The verification query should show eight demo stores with coordinates and `active_demo_offers = 16`.

## 4. Rebuild the Elasticsearch offer index

Direct SQL changes do not publish application events. Stop any running backend process, then restart it so the existing startup index rebuild reads the seeded rows from PostgreSQL:

```powershell
$env:ELASTICSEARCH_OFFERS_INDEX = 'kulha-offers-v3'
$env:ELASTICSEARCH_REBUILD_ON_STARTUP = 'true'
.\mvnw.cmd spring-boot:run
```

Wait until the backend has completed startup and indexing. Start the frontend in its own terminal with the normal project command (`pnpm dev`), then open:

- French: `http://localhost:3000/offers/map?city=Casablanca`
- English: `http://localhost:3000/en/offers/map?city=Casablanca`
- Arabic: `http://localhost:3000/ar/offers/map?city=Casablanca`

The map route is a regular page, not a modal. On desktop it shows the map beside the ranked shop list. On mobile, use the Map/Stores switch. The same view is reachable from `/offers` and from an offer detail page.

## 5. Remove only the demo data

Stop the backend before cleanup. Then run:

```powershell
$env:PGOPTIONS = '-c kulha.allow_map_demo_seed=true'
psql -h 127.0.0.1 -p $port -U $dbUser -d $dbName -W -v ON_ERROR_STOP=1 -f .\seed\cleanup-map-demo.sql
Remove-Item Env:PGOPTIONS
```

Restart the backend with `ELASTICSEARCH_REBUILD_ON_STARTUP=true` to remove the corresponding demo documents from Elasticsearch too. Cleanup never targets ordinary merchants or offers.
