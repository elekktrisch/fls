
ALTER TABLE t_club
    ADD COLUMN slug                         VARCHAR(64),
    ADD COLUMN public_registration_enabled  BOOLEAN NOT NULL DEFAULT false;

CREATE UNIQUE INDEX ux_club_slug ON t_club (slug) WHERE slug IS NOT NULL;



INSERT INTO t_club (id, clubname, club_key, country_id, club_state_id, slug, public_registration_enabled)
VALUES (
    '019e30c3-2c00-7001-8000-000000000001',
    'Seed Club',
    'SEED',
    '019e2e15-2c00-74be-8000-0000000004be',
    '019e2e15-2c00-7bb8-8000-000000000bb8',
    'seed-club-1',
    false
)
ON CONFLICT (id) DO NOTHING;
