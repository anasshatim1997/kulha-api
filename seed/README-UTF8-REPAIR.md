# Repairing previously stored French-text mojibake

The checked-in seed SQL files contain valid UTF-8 (`Pâtisserie`, `l’Oasis`). The Windows `psql` invocation was not explicitly pinning its client encoding. If its active client code page did not match the UTF-8 seed file, PostgreSQL could store strings such as `pÃ¢tisserie` or `lâ€™Oasis` even though the SQL file itself was correct.

New seed runs are protected by `SET client_encoding = 'UTF8'` in the SQL files and `PGCLIENTENCODING=UTF8` in the PowerShell helper. PostgreSQL's normal database encoding is UTF-8; a collation change is not the remedy for this conversion error.

## 1. Preview rows before repair

Connect to the intended local/development database with `psql` and UTF-8 client encoding, then run:

```sql
SHOW server_encoding;
SHOW client_encoding;
SELECT id, name, address FROM merchant.merchants
WHERE name LIKE '%Ã%' OR name LIKE '%â€%'
   OR address LIKE '%Ã%' OR address LIKE '%â€%';
SELECT id, title, description FROM offer.offers
WHERE title LIKE '%Ã%' OR title LIKE '%â€%'
   OR description LIKE '%Ã%' OR description LIKE '%â€%';
```

Review the matched records. This is a targeted repair for the Windows-1252-decoded UTF-8 signatures shown above; it is not a general-purpose transcoder for arbitrary legacy encodings.

## 2. Apply the targeted repair

From PowerShell, set the explicit one-session guard and UTF-8 client encoding:

```powershell
$env:PGOPTIONS = '-c kulha.allow_encoding_repair=true'
$env:PGCLIENTENCODING = 'UTF8'
psql -h 127.0.0.1 -p 5433 -U postgres -d kulha -W -v ON_ERROR_STOP=1 -f .\seed\repair-utf8-mojibake.sql
Remove-Item Env:PGOPTIONS
Remove-Item Env:PGCLIENTENCODING
```

The script is transactional, requires an explicit opt-in, refuses a non-UTF-8 PostgreSQL database, and only updates merchant/offer text fields containing the recognizable corruption markers. It leaves values that cannot be safely converted untouched. Back up the database before running any data repair.

## 3. Rebuild Elasticsearch

PostgreSQL is the source of truth, but the public offers search index may still contain the old text. After a successful repair, restart the backend with the existing `ELASTICSEARCH_REBUILD_ON_STARTUP=true` configuration so the normal startup reconciliation rebuilds the current index from PostgreSQL. No frontend string replacement is involved.
