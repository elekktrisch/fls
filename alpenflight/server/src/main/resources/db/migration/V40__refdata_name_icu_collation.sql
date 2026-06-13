-- J-26 T-15 — move the ICU sort collation from query-level to column-level.
--
-- `t_country.name` and `t_club_state.name` were sorted via native queries
-- that appended `COLLATE "de-CH-x-icu"` to `ORDER BY name` so accented Latin
-- characters (Côte d'Ivoire, Curaçao, Réunion) sort INSIDE their letter group
-- rather than after the ASCII range as the default `C`/locale collation would
-- place them. JPQL has no portable `COLLATE`, which forced `nativeQuery = true`
-- on both reference-data repos (ADR 0027 native-SQL register pressure).
--
-- Attaching the collation to the COLUMN makes a plain `ORDER BY name` yield the
-- SAME ICU order, so the repos can drop to zero-native Spring Data derived
-- finders with identical behavior. The `de-CH-x-icu` collation already exists
-- on these clusters (LAN PG 15 + CI PG 17) — the native queries used it — so
-- this only re-targets where it is declared, not whether it exists.
--
-- Behaviour-neutral and structural (ADR 0022 directive 2): collation is a
-- comparison/sort property of the column, not a business rule. Types/lengths
-- are preserved exactly (VARCHAR(100) / VARCHAR(50) from V2). No index or FK
-- references either `name` column, so there is nothing to rebuild.
ALTER TABLE t_country
    ALTER COLUMN name TYPE VARCHAR(100) COLLATE "de-CH-x-icu";

ALTER TABLE t_club_state
    ALTER COLUMN name TYPE VARCHAR(50) COLLATE "de-CH-x-icu";
