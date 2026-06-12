-- =============================================================================
-- J-7 T-16: give seed-club-1 a homebase so LOCATION canned flight-reports work
-- end to end against real data.
--
-- WHY: a LOCATION canned report (`/flightreports/location/...`) binds its
-- `searchFilter.locationId` from the caller's club homebase, surfaced on `/me`
-- as `homebaseLocationId` (= `t_club.homebase_id`, projected by MeService —
-- J-7 T-09b). The backend location-branch summary (FlightReportQueryService
-- #computeSummaries) ONLY groups when a LocationId is set; with no homebase the
-- canned location report returns an EMPTY summary, which is exactly the J-7
-- real-chain gate red this migration fixes.
--
-- Until now NO migration set `t_club.homebase_id` for seed-club-1, so the clean
-- realm's club had a null homebase — yet the mock principal (app.config.mock.ts
-- MOCK_USER.homebaseLocationId) and MeControllerIT both already assume Bern-Belp
-- (c001) is the homebase. This closes that mock-vs-real divergence: the real
-- clean-seed club genuinely has the same homebase the mock fakes.
--
-- Target: Bern-Belp / LSZB (`...c001`), a seed-club-1 location created by V34
-- (`V34__dev_planning_seed.sql`) — so the FK target exists. Idempotent: only
-- sets it when still unset, never overrides a homebase a later flow may set.
--
-- DEV/TEST seed (mirrors V8/V26/V28/V29/V34): bound to seed-club-1
-- (`019e30c3-2c00-7001-8000-000000000001`); runtime clubs created via
-- POST /api/v1/clubs never see this row. Note MeControllerIT resets this club's
-- homebase to NULL in its own @BeforeEach (real-HTTP IT, no tx rollback), so its
-- null-homebase assertions are unaffected.
-- =============================================================================

UPDATE t_club
   SET homebase_id = '019e30c3-2c00-7001-8000-00000000c001'  -- Bern-Belp / LSZB (V34)
 WHERE id = '019e30c3-2c00-7001-8000-000000000001'           -- seed-club-1
   AND homebase_id IS NULL;
