DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'alpenflight_app') THEN
        RAISE NOTICE 'J-33: role alpenflight_app is absent — skipping the tenant_club_id backfill '
            'grant (V54 skipped the role provisioning here too).';
        RETURN;
    END IF;

    GRANT UPDATE (tenant_club_id) ON t_mutation_audit_event TO alpenflight_app;
END
$$;

COMMENT ON COLUMN t_mutation_audit_event.tenant_club_id IS
    'The club that owns the audited change. Hibernate reads it as the @TenantId'
    ' discriminator, so a NULL row reaches no club administrator. A live row gets'
    ' the value at INSERT. A migrated row arrives NULL, because legacy AuditLogs'
    ' carries no ClubId, and MigratedAuditRowTenantBackfill then gives it the club'
    ' of the entity the row describes, per S-189. The app role holds a'
    ' column-level UPDATE grant on this cell alone, so the V54 append-only'
    ' carve-out still refuses every other UPDATE and every DELETE on this table.';
