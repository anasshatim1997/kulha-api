\if :{?runtime_role}
BEGIN;
REVOKE ALL PRIVILEGES ON TABLE admin.audit_log FROM PUBLIC;
REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE admin.audit_log FROM :"runtime_role";
GRANT USAGE ON SCHEMA admin TO :"runtime_role";
GRANT SELECT, INSERT ON TABLE admin.audit_log TO :"runtime_role";
COMMIT;
\else
\echo 'ERROR: pass --set=runtime_role=<application database role>'
\quit 3
\endif
