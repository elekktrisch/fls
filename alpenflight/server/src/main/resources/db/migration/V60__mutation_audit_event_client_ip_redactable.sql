DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'alpenflight_app') THEN
        RAISE NOTICE 'J-32: role alpenflight_app is absent — skipping the client_ip redaction grant '
            '(V54 skipped the role provisioning here too).';
        RETURN;
    END IF;

    GRANT UPDATE (client_ip) ON t_mutation_audit_event TO alpenflight_app;
END
$$;

COMMENT ON COLUMN t_mutation_audit_event.client_ip IS
    'Raw submitter IP of an anonymous public registration. Populated on'
    ' actor_kind = ANONYMOUS_PUBLIC rows only; MutationAuditEvent.Builder'
    ' refuses it on every other actor_kind, per ADR 0022 D2 — no CHECK here.'
    ' Retention is 90 days. ClientIpRetentionJob nulls this cell and keeps the'
    ' row; ClientIpRedaction nulls one row ahead of the window on a data-subject'
    ' erasure request. The app role holds a column-level UPDATE grant on this'
    ' cell alone, so the V54 append-only carve-out still refuses every other'
    ' UPDATE and every DELETE on this table.';
