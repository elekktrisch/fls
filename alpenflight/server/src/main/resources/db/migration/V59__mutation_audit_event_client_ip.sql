ALTER TABLE t_mutation_audit_event
    ADD COLUMN client_ip TEXT;

CREATE INDEX ix_mutation_audit_event_client_ip_retention
    ON t_mutation_audit_event (occurred_at)
    WHERE client_ip IS NOT NULL;

COMMENT ON COLUMN t_mutation_audit_event.client_ip IS
    'Raw submitter IP of an anonymous public registration. Populated on'
    ' actor_kind = ANONYMOUS_PUBLIC rows only; MutationAuditEvent.Builder'
    ' refuses it on every other actor_kind, per ADR 0022 D2 — no CHECK here.'
    ' Retention is 90 days; the sweep reads the partial index on occurred_at.';
