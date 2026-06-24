-- =============================================================================
-- The club's public-display logo. Surfaced (with name + city) on the pilot's
-- tenant-less /join/pending page through GET /api/v1/me/join-request, so a pilot
-- can recognise the club they requested to join before any membership exists.
--
-- Net-new with no legacy source — null until a club sets one. Nullable so the
-- ADD COLUMN needs no backfill and the migration cutover's CLUB reconcile
-- (INSERT ... ON CONFLICT (id) DO UPDATE, EntityStreamIngestor#buildInsertStatement)
-- is unaffected: the candidate tuple omits this column (it is not in ClubMapper),
-- so a reconcile leaves an existing logo intact and a fresh row gets null.
--
-- Per ADR 0022 directive 2 the schema is STRUCTURAL only: no CHECK on URL shape.
-- =============================================================================

ALTER TABLE t_club ADD COLUMN logo_url VARCHAR(500);
