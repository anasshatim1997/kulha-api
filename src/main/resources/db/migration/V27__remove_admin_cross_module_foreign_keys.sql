-- Enforce ADR 001: admin stores external module identifiers as scalar UUIDs.
-- This migration is intentionally forward-only. Do not edit V26 or recreate these
-- cross-module constraints in a later migration without an explicit architecture decision.

CREATE TABLE admin.migration_prerequisites (
    migration_version INTEGER PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_admin_migration_prerequisite_version CHECK (migration_version > 0)
);

-- Drop only the five cross-module constraints confirmed in the V26 local catalog.
ALTER TABLE admin.support_cases
    DROP CONSTRAINT support_cases_assigned_to_fkey;
ALTER TABLE admin.support_cases
    DROP CONSTRAINT support_cases_customer_user_id_fkey;
ALTER TABLE admin.support_cases
    DROP CONSTRAINT support_cases_merchant_id_fkey;
ALTER TABLE admin.support_cases
    DROP CONSTRAINT support_cases_offer_id_fkey;
ALTER TABLE admin.support_case_timeline
    DROP CONSTRAINT support_case_timeline_author_user_id_fkey;

-- The admin-owned timeline.case_id FK (ON DELETE CASCADE) is deliberately retained.
-- Record success last; PostgreSQL transactional DDL rolls back both the drops and
-- this marker if any statement in V27 fails.
INSERT INTO admin.migration_prerequisites (migration_version) VALUES (27);
