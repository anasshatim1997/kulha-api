-- Run against the deployed KULHA PostgreSQL database before preparing V27.
-- This is a read-only catalog query. It reports the actual deployed constraint
-- names and definitions; do not substitute names inferred from V26 source.
SELECT
    con.conname AS constraint_name,
    con.conrelid::regclass AS source_table,
    con.confrelid::regclass AS referenced_table,
    pg_get_constraintdef(con.oid) AS definition
FROM pg_constraint con
WHERE con.contype = 'f'
  AND con.conrelid IN (
      'admin.support_cases'::regclass,
      'admin.support_case_timeline'::regclass
  )
ORDER BY con.conrelid::regclass::text, con.conname;
