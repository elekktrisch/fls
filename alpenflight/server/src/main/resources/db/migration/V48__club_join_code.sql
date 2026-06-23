-- =============================================================================
-- S-177 — the club join code: a short, human-shareable discovery key a pilot
-- types to file a request to join (S-178). 8 chars over a 31-char ambiguous-
-- glyph-stripped alphabet (~40 bits of entropy). NOT NULL + global UNIQUE so
-- a code resolves unambiguously to one Club across all tenants / Deployments.
--
-- Per ADR 0022 directive 2 the schema is STRUCTURAL only: no CHECK on the
-- alphabet, no rotation rule — Club.rotateJoinCode mints + re-mints on the
-- aggregate, which is the RUNTIME authority. The DEFAULT here is a structural
-- bootstrap, mirroring t_club.deployment_id's DEFAULT (V14): it fills existing
-- rows on ADD COLUMN and satisfies NOT NULL for the migration cutover's
-- `INSERT ... ON CONFLICT (id) DO UPDATE` CLUB reconcile, whose candidate tuple
-- omits this provisioning-owned column (EntityStreamIngestor#buildInsertStatement).
-- A real club always gets its code from Club.create / rotateJoinCode.
--
-- gen_join_code() draws 8 chars from the alphabet with random(); the global
-- UNIQUE index is the collision backstop. The default expression is volatile so
-- each row (existing backfill + each reconcile candidate) gets its own value.
-- =============================================================================

CREATE FUNCTION gen_join_code() RETURNS TEXT
    LANGUAGE sql VOLATILE AS $$
    SELECT string_agg(
        substr('ABCDEFGHJKLMNPQRSTUVWXYZ23456789',
               1 + floor(random() * 31)::int, 1),
        '')
    FROM generate_series(1, 8);
$$;

ALTER TABLE t_club ADD COLUMN join_code TEXT NOT NULL DEFAULT gen_join_code();

-- The canonical V5 seed club gets a fixed, legible code so specs that drive the
-- join flow have a stable value to type.
UPDATE t_club
   SET join_code = 'SEEDCLUB'
 WHERE id = '019e30c3-2c00-7001-8000-000000000001';

CREATE UNIQUE INDEX ux_club_join_code ON t_club (join_code);
