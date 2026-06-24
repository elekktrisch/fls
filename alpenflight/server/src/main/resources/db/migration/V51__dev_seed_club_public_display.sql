-- =============================================================================
-- J-12a — public-display fields (city + logo) for the canonical seed club, so a
-- pilot's tenant-less /join/pending page renders the FULL public projection
-- (name + city + logo) the contract promises, not just a bare name.
--
-- WHY: V48 stamps seed-club-1 (019e30c3-2c00-7001-8000-000000000001) with the
-- fixed join code 'SEEDCLUB' so the join flow has a stable code to type, but
-- V11a left `city`/`logo_url` null (net-new fields, no legacy source, no create
-- API — ClubUpdateRequest carries neither). On a clean realm the pending page
-- would therefore show only the club name; the J-12a real-idp proof needs the
-- city + logo visible to prove the public projection end to end.
--
-- DEV/TEST-SEED bound to seed-club-1 — identical posture to the sibling
-- V31/V8/V29 dev seeds (see V31 header): the row is seed-club-1-bound, the read
-- (`GET /api/v1/me/join-request`) resolves the public display off the request
-- the pilot owns, and real prod tenants (each a distinct migrated UUID) never
-- see it. The logo is a self-contained inline SVG data URI (no network
-- dependency, fits VARCHAR(500)) so the proof video renders deterministically.
-- Idempotent: only fills the seed club's still-null public-display fields.
-- =============================================================================

UPDATE t_club
   SET city = 'Zürich',
       logo_url = 'data:image/svg+xml,'
                  || '%3Csvg%20xmlns%3D%22http%3A//www.w3.org/2000/svg%22%20'
                  || 'viewBox%3D%220%200%2048%2048%22%3E%3Crect%20width%3D%2248%22%20'
                  || 'height%3D%2248%22%20fill%3D%22%230ea5e9%22/%3E%3Ctext%20x%3D%2224%22%20'
                  || 'y%3D%2231%22%20font-family%3D%22sans-serif%22%20font-size%3D%2220%22%20'
                  || 'fill%3D%22white%22%20text-anchor%3D%22middle%22%3ESC%3C/text%3E%3C/svg%3E'
 WHERE id = '019e30c3-2c00-7001-8000-000000000001'
   AND (city IS NULL OR logo_url IS NULL);
