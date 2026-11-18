# Production privileges for the audit log

The runtime database role must not own `admin.audit_log` and must not inherit membership in an owner/admin role. It requires `USAGE` on the `admin` schema and `SELECT`/`INSERT` on the table only. It must not have `UPDATE`, `DELETE`, `TRUNCATE`, `REFERENCES` or `TRIGGER` privileges. The existing database trigger additionally rejects row updates and deletes; `TRUNCATE` is controlled by the role privilege.

Run the provisioning script as the database owner after substituting the real application connection role:

```sh
psql --set=runtime_role=kulha_runtime --file=scripts/configure-audit-log-privileges.sql "$DATABASE_URL"
```

Replace `kulha_runtime` with the actual runtime role; do not create or grant privileges to an owner account for application use. Verify the effective privileges after applying:

```sql
SELECT has_table_privilege('kulha_runtime', 'admin.audit_log', 'SELECT') AS can_select,
       has_table_privilege('kulha_runtime', 'admin.audit_log', 'INSERT') AS can_insert,
       has_table_privilege('kulha_runtime', 'admin.audit_log', 'UPDATE') AS can_update,
       has_table_privilege('kulha_runtime', 'admin.audit_log', 'DELETE') AS can_delete,
       has_table_privilege('kulha_runtime', 'admin.audit_log', 'TRUNCATE') AS can_truncate;
```

Expected: `can_select=true`, `can_insert=true`, and every other column `false`. Also verify the runtime role does not own the table and has no inherited membership that restores write privileges. The actual production role name is environment-specific and is intentionally not assumed in source control.
