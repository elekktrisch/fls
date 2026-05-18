-- =============================================================================
-- S-048 walking-skeleton: extends the V2 `club` table with the two columns
-- the Clubs CRUD slice needs (`slug`, `public_registration_enabled`) plus a
-- single canonical dev row so the SPA's first navigation to `/clubs` has
-- something to display.
--
-- Per ADR 0022 directive 2: schema is STRUCTURAL. No CHECK on slug format,
-- no trigger to enforce lowercase, no generated column. `Club.rebrand()` on
-- the aggregate validates the regex; this migration only carries the partial
-- UNIQUE that makes the slug identity-bearing once populated.
--
-- The partial UNIQUE (`WHERE slug IS NOT NULL`) is identity-bearing — the one
-- structural-constraint flavor ADR 0022 directive 2 explicitly permits.
-- =============================================================================

ALTER TABLE club
    ADD COLUMN slug                         VARCHAR(64),
    ADD COLUMN public_registration_enabled  BOOLEAN NOT NULL DEFAULT false;

CREATE UNIQUE INDEX ux_club_slug ON club (slug) WHERE slug IS NOT NULL;


-- -----------------------------------------------------------------------------
-- Seed dev row. The fixed UUID `019e30c3-2c00-7001-8000-000000000001` is
-- referenced by ClubsControllerIT + Playwright specs as the canonical starting
-- point. Country = CH, club_state = ACTIVE — both UUIDs come from the
-- canonical seed JSON (see reference-seeds-canonical-uuids.json).
-- -----------------------------------------------------------------------------

INSERT INTO club (id, clubname, club_key, country_id, club_state_id, slug, public_registration_enabled)
VALUES (
    '019e30c3-2c00-7001-8000-000000000001',
    'Seed Club',
    'SEED',
    '019e2e15-2c00-74be-8000-0000000004be',  -- CH
    '019e2e15-2c00-7bb8-8000-000000000bb8',  -- ACTIVE
    'seed-club-1',
    false
)
ON CONFLICT (id) DO NOTHING;
