-- M3: append-only audit log for sensitive actions.

CREATE TABLE admin.audit_log (
    id             uuid        PRIMARY KEY,
    actor_user_id  uuid        NOT NULL,
    action         varchar(60) NOT NULL,
    target_type    varchar(40) NOT NULL,
    target_id      varchar(64) NOT NULL,
    reason         varchar(500),
    result         varchar(20) NOT NULL,
    correlation_id varchar(64),
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_log_target ON admin.audit_log (target_type, target_id, created_at);
CREATE INDEX ix_audit_log_actor ON admin.audit_log (actor_user_id, created_at);

-- Rows can be added but never changed or removed by the application.
-- (In production the application's database account also gets no UPDATE, DELETE or TRUNCATE privilege.)
CREATE FUNCTION admin.audit_log_is_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'admin.audit_log is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_no_update_or_delete
    BEFORE UPDATE OR DELETE ON admin.audit_log
    FOR EACH ROW EXECUTE FUNCTION admin.audit_log_is_append_only();
