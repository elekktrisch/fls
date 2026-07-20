-- =============================================================================
-- S-160: split the DDL/DML migrator role from the runtime app role so the audit
-- trail (t_mutation_audit_event) is structurally append-only for the app.
--
-- The existing `alpenflight` role stays the MIGRATOR: it owns the schema, has
-- DDL, and runs Flyway (boot-time via spring.flyway.{url,user,password}; the
-- Gradle flywayMigrate task via the build.gradle.kts flyway block). This
-- migration runs AS the migrator.
--
-- `alpenflight_app` is a new LOGIN role the running application boots as (the
-- main datasource). It gets broad DML on every app table BUT only INSERT,SELECT
-- on t_mutation_audit_event — no UPDATE/DELETE — so app-credential compromise
-- cannot tamper or erase audit history (S-027 threat-row (d)). The migrator
-- retains full CRUD on the audit table for lawful maintenance migrations, so
-- Flyway is never locked out (the exact concern S-160's Context flags).
--
-- Roles are CLUSTER-global while Flyway history is per-DB, so every step is
-- create-if-not-exists / idempotent: a re-provisioned DB on a shared cluster
-- where `alpenflight_app` already exists must not error.
--
-- The app-role password is injected via the Flyway placeholder
-- ${app_role_password} (never hardcoded) — wired from APP_DB_PASSWORD in
-- application.yml + the Gradle flyway placeholders block, loopback dev default.
--
-- Per ADR 0022 directive 2 this is infrastructure (role/grant topology), not
-- business logic — no schema/behavior change to any table.
-- =============================================================================

-- 1. App login role — idempotent create (roles are cluster-global).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'alpenflight_app') THEN
        CREATE ROLE alpenflight_app LOGIN PASSWORD '${app_role_password}';
    END IF;
END
$$;

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
