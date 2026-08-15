
DO $$
BEGIN
    IF NOT (SELECT rolsuper OR rolcreaterole FROM pg_catalog.pg_roles WHERE rolname = current_user) THEN
        RAISE NOTICE 'S-160: current role % lacks CREATEROLE/superuser — skipping alpenflight_app '
            'provisioning (append-only enforcement is a no-op here; see AppendOnlyAuditRoleIT '
            'fail-loud skip).', current_user;
        RETURN;
    END IF;

    -- 1. App login role — idempotent create (roles are cluster-global).
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'alpenflight_app') THEN
        CREATE ROLE alpenflight_app LOGIN PASSWORD '${app_role_password}';
    END IF;

    -- 2. Schema + broad DML on everything that exists at V54.
    GRANT USAGE ON SCHEMA public TO alpenflight_app;
    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO alpenflight_app;
    GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO alpenflight_app;
    GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO alpenflight_app;

    -- 3. Append-only carve-out — the whole point of S-160. The app may INSERT and
    --    SELECT audit rows but never UPDATE or DELETE them. Re-assert this REVOKE
    --    if a FUTURE migration recreates/renames t_mutation_audit_event (a fresh
    --    table re-inherits the step-2 blanket GRANT and would silently re-open the
    --    tamper surface) — see S-160.
    REVOKE UPDATE, DELETE ON t_mutation_audit_event FROM alpenflight_app;

    -- 4. Default privileges — tables/sequences/functions created by FUTURE
    --    migrations must be reachable by the app role automatically; without this
    --    the app 42501s at runtime on the first table a later migration adds.
    --    No `FOR ROLE` clause: default privileges attach to the role that CREATES
    --    the object, and future migrations run as whoever the migrator is (named
    --    `alpenflight` in prod, but the container user under test) — the implicit
    --    current-role form is portable across both.
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO alpenflight_app;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
        GRANT USAGE, SELECT ON SEQUENCES TO alpenflight_app;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
        GRANT EXECUTE ON FUNCTIONS TO alpenflight_app;
END
$$;
