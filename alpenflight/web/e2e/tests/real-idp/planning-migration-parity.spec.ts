import { type Browser, type BrowserContext, type TestInfo } from '@playwright/test';
import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';

import {
  loginAsReservationAdmin,
  captureReservationAdminBearer,
  fetchReservationTypeId,
  resolveMigratedTestClubAdmin,
  loginAsMigratedTestClubAdmin,
  useRealBundle,
  type MigratedClubAdmin,
} from './_helpers/reservation-parity-fixture';
import {
  seedPlanningMasterdata,
  seedFreshPlanningLocation,
  seedReservationOnPlanningDay,
  provisionSeedClubPilot,
  captureSeedClubPilotBearer,
  SEED_CLUB_NOTIFICATION_ADDRESS,
  type PlanningMasterdata,
  type SeedClubPilot,
} from './_helpers/planning-parity-fixture';
import {
  provisionTwoClubs,
  loginAsClubAdmin,
  type TwoClubFixture,
} from './_helpers/two-club-fixture';
import { proofVideo } from './_helpers/proof-video';
import { waitForMessage, waitForMessageWithSubject, purgeMailpit } from './_helpers/mailpit-client';
import { selectAfOption } from '../_helpers/af-select';

/**
 * J-6 planning-days real chain (live Keycloak auth + real Spring backend + real
 * Postgres) — the journey's `parity_test`.
 *
 * T-16 (§4 done-gate): ALL cases now run FULLY REAL against the real-idp stack
 * (no mocks). The clean-seed happy path drives the real `/planning` screens (list
 * render of the V34 seed days · create a day with date + location + 3-role crew +
 * remarks · edit crew · the inline J-5 reservations panel · the setup wizard's
 * weekday bulk-create) and captures the screenshots + pass-video the J-6 gallery
 * page renders. The thickened key-error / edge / email / migration cases (T-16):
 *   - duplicate (date, location) → 409 + the rule-wizard skips existing idempotently;
 *   - delete a day → its 3 assignments cascade + the day leaves the list, AND a
 *     real low-privilege PILOT (non-admin, non-creator) is FORBIDDEN (403) — the
 *     admin-or-creator gate, proven with a REAL low-priv principal (the mock-admin
 *     would have hidden it, [[project_real_idp_real_roles_catches_authz_gaps]]);
 *   - cross-tenant GET → 404 driven by a REAL second-club admin (club B);
 *   - the notification job run-now → imminent (day+1, club address) + week-ahead
 *     (day+7, each assigned crew member) mails land in mailpit;
 *   - the migrated-parity read of a real legacy planning day (own-club location).
 *
 * Mirrors the J-5 reservation real-idp discipline
 * (`reservations-migration-parity.spec.ts`):
 *   - the same clean-seed principal: `clubadmin4` bound to seed-club-1 (the
 *     @TenantId club) — `loginAsReservationAdmin` / `captureReservationAdminBearer`
 *     are journey-agnostic seed-club-1 helpers reused here;
 *   - track every created planning-day id + `afterAll` DELETE them so a retry
 *     starts on a clean tenant (seed-club-1 is the shared, never-truncated seed);
 *   - DELTA-assert list counts, never absolutes;
 *   - warm in-app nav only, no `clearCookies`;
 *   - read a created id from the 201 `Location` header / a re-GET, never the POST
 *     body (the SPA navigates on success → Chrome evicts the body);
 *   - the migrated-data half rides the SAME real bundle the J-0c fan-out spec
 *     ingests; skips cleanly when the per-push synth gate ran (no real export).
 *
 * ── REAL PRINCIPALS (no mock-admin) ──────────────────────────────────────────
 *   - the delete/update authz probe drives a REAL low-privilege PILOT bound to
 *     seed-club-1 (`provisionSeedClubPilot`) — neither admin nor the record
 *     creator → 403; the mock-admin would hide this gate
 *     ([[project_real_idp_real_roles_catches_authz_gaps]]);
 *   - the cross-tenant 404 probe drives a REAL second club + admin (`provisionTwoClubs`);
 *   - the notification case fires the guarded `POST .../notifications/run`
 *     affordance (the J-15 jobs console is not built) + asserts mailpit.
 *
 * ── WHAT THIS PROVES (J-6 acceptance, grounded in the behavior oracle) ───────
 *   [happy] create a planning day (date + location + 3-role crew + remarks)
 *     THROUGH THE UI → it renders in the future-days list.
 *   [happy] edit the crew → persists on reopen.
 *   [happy] the edit screen shows that day's AircraftReservations inline (J-5 join).
 *   [key-error] duplicate (date, location) → 409 (V4 ux_pln_club_date_loc).
 *   [happy] setup wizard bulk-creates days across a range filtered by weekday.
 *   [key-error] delete → assignments cascade-delete; the day leaves the list.
 *   [edge] tenant isolation: club A's day is not readable by club B (404).
 *   [happy/email] PlanningDayNotificationJob run-now → imminent (day+1, club
 *     address) + week-ahead (day+7, each assigned person) mails land in mailpit.
 *   [migration/parity] a migrated planning day (assignments + fan-out-resolved
 *     location) renders for the migrated club admin, identity-matched to legacy.
 */

const PLANNINGDAYS = '/api/v1/planning-days';

/** `YYYY-MM-DD` `days` days from local today (planning days are future days). */
function dayKeyFromToday(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

/**
 * `YYYY-MM-DD` of the first Saturday at least `minDays` days from local today —
 * used by the duplicate-409 + rule-wizard-skip case so the SAME date the wizard
 * emits (it generates Sat/Sun only) is the one already created (the skip probe).
 */
function nextSaturdayFromToday(minDays: number): string {
  const d = new Date();
  d.setDate(d.getDate() + minDays);
  // getDay(): Sat = 6 → advance to the next Saturday (or stay if already Sat).
  while (d.getDay() !== 6) {
    d.setDate(d.getDate() + 1);
  }
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  const context = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
  // Guard every page this context opens, not just the fixture-injected one.
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  return context;
}

/** A row of the paged planning-day list envelope (the `PlanningDayDetail` shape). */
interface PlanningDayListRow {
  id: string;
  planningDate: string;
  locationId: string;
  info?: string | null;
  instructorPersonId?: string | null;
  towingPilotPersonId?: string | null;
  flightOperatorPersonId?: string | null;
  numberOfAircraftReservations: number;
}

/** A planning-day create/update request body (3 nullable person ids — oracle).
 *  Wire field names match the orval client: `planningDate` / `info`. */
interface PlanningDayRequest {
  planningDate: string;
  locationId: string;
  instructorPersonId?: string;
  towingPilotPersonId?: string;
  flightOperatorPersonId?: string;
  info?: string;
}

/**
 * Create a planning day via the REAL REST API and return the created id (from
 * the 201 `Location` header — never the POST body). Tracks the id in `created`
 * so the group's `afterAll` can delete it (retry-isolation). The masterdata it
 * references (location + crew person ids) is seeded by `planning-parity-fixture.ts`.
 */
async function createPlanningDay(
  ctx: BrowserContext,
  bearer: string,
  created: string[],
  body: PlanningDayRequest,
): Promise<string> {
  const res = await ctx.request.post(PLANNINGDAYS, {
    headers: { authorization: bearer, 'content-type': 'application/json' },
    data: body,
  });
  expect(
    res.status(),
    `planning-day create must 201 — got ${res.status()}: ${await res.text()}`,
  ).toBe(201);
  const location = res.headers()['location'];
  expect(location, 'create must return a 201 Location header').toBeTruthy();
  const id = new URL(location!, 'http://localhost').pathname.split('/').pop() ?? '';
  expect(id, `Location "${location}" must end in a planning-day UUID`).toMatch(/^[0-9a-f-]{36}$/);
  created.push(id);
  return id;
}

// ===========================================================================
// CLEAN-SEED real chain — create→list + edit-crew + inline-reservations +
// duplicate-409 + setup-wizard + delete-cascade + tenant-isolation + the
// notification-job run-now → mailpit. The load-bearing J-6 proof.
// ===========================================================================
test.describe('Planning days — clean-seed real chain (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let baseURL: string;
  /** clubadmin4's Bearer (seed-club-1, the operating/@TenantId club). */
  let adminBearer: string;
  /** A fresh location + 3 pickable crew persons + an aircraft (seed-club-1). */
  let masterdata: PlanningMasterdata;
  /**
   * T-21 collision isolation: each day-creating test gets its OWN fresh
   * seed-club-1 location so no two tests share a (date, location) space. The two
   * bulk-create tests ([:527] rule-wizard skip-probe + [:599] setup wizard) emit
   * EVERY Sat/Sun in a window; a single-date test landing on a Sat/Sun inside one
   * of those windows AT THE SAME location 409s on `ux_pln_club_date_loc` (wall-
   * clock dependent — what weekday a given `dayKeyFromToday(N)` lands on). Distinct
   * locations make the (club, date, location) key collision-free regardless of the
   * calendar date the suite runs on. Seeded once in `beforeAll`. The UI-picker
   * tests ([:255] create, [:423] inline, [:599] wizard) carry a `name` so the
   * `<af-select>` can search the unique location out of the virtualised long list.
   */
  let locEditCrew: { locationId: string; locationName: string };
  let locInline: { locationId: string; locationName: string };
  let locDuplicate: { locationId: string; locationName: string };
  let locWizard: { locationId: string; locationName: string };
  let locDelete: { locationId: string; locationName: string };
  let locTenant: { locationId: string; locationName: string };
  let locNotify: { locationId: string; locationName: string };
  /** The clean-seed default reservation type (V31 seed) the inline-panel reservation uses. */
  let reservationTypeId: string;
  /** Every planning-day id this group created in seed-club-1 — deleted in afterAll. */
  const createdIds: string[] = [];
  /** Every reservation id this group created in seed-club-1 — deleted in afterAll. */
  const createdReservationIds: string[] = [];
  let cleanupCtx: BrowserContext;
  /** A real low-privilege PILOT (seed-club-1) — the delete-authz 403 probe. */
  let pilot: SeedClubPilot;
  let pilotBearer: string;
  /** A real second club + admin (club B) — the cross-tenant-404 probe. */
  let twoClubs: TwoClubFixture;

  test.beforeAll(async ({ browser, request }, testInfo) => {
    // Provisioning two clubs + a pilot through real Keycloak and seeding the
    // masterdata through the real create APIs exceeds the 45s per-test budget on
    // a slow CI box.
    testInfo.setTimeout(180_000);
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    createdIds.length = 0;
    createdReservationIds.length = 0;
    adminBearer = await captureReservationAdminBearer(browser, baseURL);
    // A real low-privilege PILOT (seed-club-1) for the delete-authz 403 probe, and
    // a real second club (B) + its admin for the cross-tenant-404 probe — driven
    // through real Keycloak so neither the mock-admin nor a synthetic claim hides
    // the role / tenant gate ([[project_real_idp_real_roles_catches_authz_gaps]]).
    pilot = await provisionSeedClubPilot();
    pilotBearer = await captureSeedClubPilotBearer(browser, baseURL, pilot);
    twoClubs = await provisionTwoClubs(browser, baseURL, 'pln');
    // Seed (as clubadmin4, through the REAL create APIs) a fresh location + 3
    // crew persons WITH a seed-club-1 membership so the create/edit form's 3
    // crew <af-select>s offer them (the V34 seed persons carry no membership →
    // not pickable) + a fresh aircraft for the inline-panel reservation. The
    // PILOT-vs-creator authz delete/update probe stays T-16.
    masterdata = await seedPlanningMasterdata(request, adminBearer);
    // T-21: one fresh location per day-creating test so their (date, location)
    // spaces never overlap (collision-free regardless of wall-clock — see the
    // field decls above). The [:255] happy-create keeps `masterdata`'s default
    // location; the inline-reservations [:423] picks `locInline` in the UI, the
    // setup wizard [:599] picks `locWizard` in the UI — both carry a unique name.
    // Seed them in PARALLEL: 7 independent location creates run concurrently so
    // the extra seeding stays well inside the `beforeAll` budget (they are
    // disjoint rows — `shortTag()`'s per-process counter keeps the names unique
    // even when the creates land in the same millisecond).
    [locEditCrew, locInline, locDuplicate, locWizard, locDelete, locTenant, locNotify] =
      await Promise.all([
        seedFreshPlanningLocation(request, adminBearer, 'EditCrew'),
        seedFreshPlanningLocation(request, adminBearer, 'Inline'),
        seedFreshPlanningLocation(request, adminBearer, 'Duplicate'),
        seedFreshPlanningLocation(request, adminBearer, 'Wizard'),
        seedFreshPlanningLocation(request, adminBearer, 'Delete'),
        seedFreshPlanningLocation(request, adminBearer, 'Tenant'),
        seedFreshPlanningLocation(request, adminBearer, 'Notify'),
      ]);
    // The V31-seeded default reservation type — the inline-panel parity shot
    // reserves the seeded aircraft on the captured planning day with this type.
    reservationTypeId = await fetchReservationTypeId(request, adminBearer);
    cleanupCtx = await browser.newContext({ baseURL });
  });

  test.afterAll(async () => {
    // Reservations FIRST (a reservation references no planning day, but delete it
    // before its day so the shared seed-club-1 tenant is left fully clean).
    for (const id of createdReservationIds) {
      try {
        await cleanupCtx.request.delete(`/api/v1/aircraft-reservations/${id}`, {
          headers: { authorization: adminBearer },
        });
      } catch (err) {
        console.warn(
          `[J-6] afterAll cleanup: delete reservation ${id} failed (${(err as Error).message})`,
        );
      }
    }
    for (const id of createdIds) {
      try {
        await cleanupCtx.request.delete(`${PLANNINGDAYS}/${id}`, {
          headers: { authorization: adminBearer },
        });
      } catch (err) {
        console.warn(`[J-6] afterAll cleanup: delete ${id} failed (${(err as Error).message})`);
      }
    }
    await cleanupCtx?.close();
    await pilot?.dispose();
    await twoClubs?.dispose();
  });

  test('[happy] create a planning day through the UI (date + location + 3-role crew + remarks) → it renders in the future-days list', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);

      // The future-days list renders the V34 clean-seed days (the weekday
      // full-crew day + the next-Saturday weekend day, Sat/Sun flagged). Pin the
      // German cold-start locale (the primary market + the gallery locale).
      await page.goto('/planning?lang=de');
      await expect(page.locator('h1')).toContainText('Planung');
      await expect(page.getByTestId('planning-list')).toBeVisible();
      // At least one V34 seed weekend row is present and flagged (Sat/Sun).
      await expect(
        page.locator('[data-testid^="planning-row-"][data-weekend="true"]').first(),
        'the V34 weekend seed day renders flagged on the future-days list',
      ).toBeVisible();

      await page.getByTestId('planning-new-button').locator('button').click();
      await expect(page).toHaveURL('/planning/new/edit');

      // Drive the FULL clean-seed UI create: date + the fresh seeded location +
      // all 3 crew roles from the real <af-select>s + remarks. The fresh location
      // keeps this create off the V34 seed days' (date, location) so it is a
      // clean 201, never a duplicate-409.
      await page.getByTestId('planning-date').locator('input').fill(dayKeyFromToday(5));
      // Pass a search term per pick: seed-club-1 is never truncated, so prior
      // runs accumulate locations/persons and nz-select virtualises a long list
      // — type the unique seeded name so the target option renders (J-5 T-27).
      await selectAfOption(
        page,
        'planning-location-select',
        masterdata.locationId,
        masterdata.locationName,
      );
      await selectAfOption(
        page,
        'planning-instructor-select',
        masterdata.instructorId,
        masterdata.instructorName,
      );
      await selectAfOption(
        page,
        'planning-towpilot-select',
        masterdata.towPilotId,
        masterdata.towPilotName,
      );
      await selectAfOption(
        page,
        'planning-flightop-select',
        masterdata.flightOpId,
        masterdata.flightOpName,
      );
      await page.getByTestId('planning-remarks').locator('input').fill('J-6 real-chain day');

      // DIAGNOSTIC (not the gallery-paired form shot): the populated CREATE form
      // before save. The GALLERY `form` view (alpenflight-planning-form.png) is
      // captured by the inline-reservations test below on a SAVED day so it shows
      // the populated inline reservations list, mirroring the legacy saved-day
      // edit form's inline reservations table (T-16 populated-list parity). This
      // create-form shot keeps a record of the empty-day create field set.
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-planning-create-form.png`,
        fullPage: true,
      });

      // Read the created id off the 201 Location header (the SPA navigates on
      // bus-success → Chrome evicts the POST body). Track it for afterAll
      // cleanup BEFORE asserting so a later failure still cleans the row.
      const createdResp = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new URL(r.url()).pathname === '/api/v1/planning-days' &&
          r.status() === 201,
      );
      await page.getByTestId('planning-save-button').click();
      const resp = await createdResp;
      const location = resp.headers()['location'];
      expect(location, 'create must return a 201 Location header').toBeTruthy();
      const id = new URL(location!, 'http://localhost').pathname.split('/').pop() ?? '';
      expect(id, `Location "${location}" must end in a planning-day UUID`).toMatch(
        /^[0-9a-f-]{36}$/,
      );
      createdIds.push(id);

      // On bus-success the SPA returns to the /planning list; the created day
      // renders in its row with its location + the 3 crew display names.
      await expect(page).toHaveURL('/planning');
      const row = page.getByTestId(`planning-row-${id}`);
      await expect(row, 'the created planning day renders in the future-days list').toBeVisible();
      await expect(row).toContainText(masterdata.locationName);
      await expect(row).toContainText(masterdata.instructorName);
      await expect(row).toContainText(masterdata.towPilotName);
      await expect(row).toContainText(masterdata.flightOpName);

      // PARITY SHOT: the populated /planning list (legacy planning table ↔ list)
      // — at least the V34 seed days + this created row (≥3 rows, every column).
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-planning-list.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-6',
        caption:
          'J-6 · planning · a club admin logs in via real Keycloak and creates a planning day ' +
          '(date · location · instructor / tow-pilot / flight-operator · remarks) THROUGH THE UI — ' +
          'it renders in the /planning future-days list (real backend round-trip)',
        acTag: 'happy',
      });
    }
  });

  test('[happy] editing a planning day’s crew persists and reflects on reopen', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);

      // Create a bare day (no instructor) via the real API, then drive the edit
      // form to ASSIGN the instructor crew role, save (real PUT), reopen, and
      // assert the change persisted (the picker shows the assigned name).
      const id = await createPlanningDay(ctx, adminBearer, createdIds, {
        planningDate: dayKeyFromToday(11),
        locationId: locEditCrew.locationId,
      });

      await page.goto(`/planning/${id}/edit?lang=de`);
      await expect(page.getByTestId('planning-edit-form')).toBeVisible();
      await selectAfOption(
        page,
        'planning-instructor-select',
        masterdata.instructorId,
        masterdata.instructorName,
      );

      const updated = page.waitForResponse(
        (r) =>
          r.request().method() === 'PUT' &&
          new URL(r.url()).pathname === `${PLANNINGDAYS}/${id}` &&
          r.status() === 200,
      );
      await page.getByTestId('planning-save-button').click();
      await updated;
      await expect(page).toHaveURL('/planning');

      // Reopen — the instructor assignment persisted on the real round-trip.
      await page.goto(`/planning/${id}/edit?lang=de`);
      await expect(page.getByTestId('planning-instructor-select')).toContainText(
        masterdata.instructorName,
      );
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-6',
        caption:
          'J-6 · planning · editing a planning day’s crew assignments persists and reflects on ' +
          'reopen (real PUT round-trip over the generic typed-assignment rows)',
        acTag: 'happy',
      });
    }
  });

  test('[happy] the inline AircraftReservations list appears on date+location SELECT (create mode, pre-save) AND on a saved day (J-5 join) — the gallery form parity shot', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);

      // SEED A REAL J-5 reservation on a chosen date + the seeded location (via
      // the real reservations create API — no mocking) so the inline per-day
      // reservations panel renders ≥1 `<af-reservation-row>`. The J-5 read-side
      // join is `listAircraftReservationsForDay(date)` filtered to the location
      // (planning.store.ts), so the panel keys on that EXACT date + location —
      // NOT a saved planning-day id (T-08c improvement over legacy's saved-day
      // gate, legacy PlanningDayEditController.js:96-104). We seed the
      // reservation alone (no planning day needed to make the panel surface) to
      // prove the panel appears on a NEW, UNSAVED day the moment date+location
      // are picked.
      const planningDate = dayKeyFromToday(13);
      const reservationId = await seedReservationOnPlanningDay(ctx.request, adminBearer, {
        planningDate,
        locationId: locInline.locationId,
        aircraftId: masterdata.aircraftId,
        pilotPersonId: masterdata.instructorId,
        reservationTypeId,
      });
      createdReservationIds.push(reservationId);

      // CREATE MODE (the operator's intended UX, T-08c): open a BLANK new-day
      // form, pick the date + location, and the inline panel loads that
      // date+location's reservations reactively — NO save, no planning-day id.
      await page.goto('/planning/new/edit?lang=de');
      await expect(page.getByTestId('planning-edit-form')).toBeVisible();
      await page.getByTestId('planning-date').locator('input').fill(planningDate);
      await selectAfOption(
        page,
        'planning-location-select',
        locInline.locationId,
        locInline.locationName,
      );

      const panel = page.getByTestId('planning-reservations-panel');
      await expect(
        panel,
        'the inline per-day reservations panel renders on date+location select (create mode, pre-save)',
      ).toBeVisible();
      await expect(panel.getByTestId('planning-new-reservation-button')).toBeVisible();

      // CAPTURE-AFTER-DATA-LOADED, CAPTURE-BEFORE-DEEP-ASSERT (J-5 rule): wait for
      // the seeded reservation's row to be visible (the list is POPULATED) on the
      // UNSAVED create form, then take the gallery `form` parity shot BEFORE the
      // deeper row-content asserts, so a partial red still produces a
      // populated-list shot to pair with legacy.
      const seededRow = page.getByTestId(`planning-reservation-${reservationId}`);
      await expect(
        seededRow,
        'the seeded reservation renders as an <af-reservation-row> in the inline list (create mode)',
      ).toBeVisible();
      // GALLERY FORM PARITY SHOT (side=alpenflight, view=form): the CREATE form
      // with the populated inline reservations list shown on date-select —
      // pairs against the legacy saved-day edit form's inline reservations table
      // (the form view shows the list on BOTH sides; AlpenFlight shows it sooner,
      // the deliberate improvement).
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-planning-form.png`,
        fullPage: true,
      });
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-planning-reservations-panel.png`,
        fullPage: true,
      });
      await expect(seededRow).toContainText(masterdata.aircraftImmat);

      // DON'T REGRESS SAVED-DAY behavior: create the day on that same
      // date+location, open its SAVED edit form, and assert the SAME inline list
      // surfaces (the read-side join is unchanged for a saved day).
      const id = await createPlanningDay(ctx, adminBearer, createdIds, {
        planningDate,
        locationId: locInline.locationId,
      });
      await page.goto(`/planning/${id}/edit?lang=de`);
      await expect(
        page.getByTestId('planning-reservations-panel'),
        'a SAVED day still shows its inline reservations (no regression)',
      ).toBeVisible();
      await expect(
        page.getByTestId(`planning-reservation-${reservationId}`),
        'the saved-day edit form lists the same reservation inline (delta/presence)',
      ).toBeVisible();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-6',
        caption:
          'J-6 · planning · the inline AircraftReservations list appears the moment a date + ' +
          'location are picked on a NEW (unsaved) planning day — a real J-5 AircraftReservation ' +
          'seeded on that exact date + location surfaces through the read-side join (club + date + ' +
          'location) as an <af-reservation-row>, NO save required (the deliberate improvement over ' +
          'legacy’s saved-day gate); the same list also shows on the saved day’s edit form',
        acTag: 'happy',
      });
    }
  });

  test('[key-error] a duplicate (date, location) planning day is rejected 409; the rule-wizard skips the existing day idempotently', async ({
    browser,
  }) => {
    const ctx = await browser.newContext({ baseURL });
    try {
      // Create a day, then POST a second with the SAME (date, location) → 409
      // (V4 ux_pln_club_date_loc corrects legacy's no-dedup bug). Use a Saturday
      // so the SAME day is reachable by the rule-wizard skip-existing probe below
      // (the wizard only emits Sat/Sun in this window).
      const dupDate = nextSaturdayFromToday(15);
      const first = await createPlanningDay(ctx, adminBearer, createdIds, {
        planningDate: dupDate,
        locationId: locDuplicate.locationId,
      });
      expect(first).toBeTruthy();

      // The INTENTIONAL self-collision: the SAME (date, location) as `first` →
      // 409. Same location on purpose — this is the duplicate-409 probe, not a
      // cross-test accident (T-21 isolation gives this test its OWN location, so
      // its window cannot collide with another test's days).
      const dup = await ctx.request.post(PLANNINGDAYS, {
        headers: { authorization: adminBearer, 'content-type': 'application/json' },
        data: { planningDate: dupDate, locationId: locDuplicate.locationId },
      });
      expect(
        dup.status(),
        `a duplicate (date, location) must 409 — got ${dup.status()}: ${await dup.text()}`,
      ).toBe(409);
      const body = (await dup.json()) as { key?: string };
      expect(body.key).toBe('planning.day.duplicate');

      // RULE-WIZARD IDEMPOTENT SKIP (the oracle's second half): bulk-create over a
      // window that INCLUDES the already-existing Saturday `dupDate`, at the same
      // location. The existing (club, date, location) day is SKIPPED idempotently
      // (no 409, no dupe); the response lists only the days actually created, and
      // `dupDate` is NOT among them — proving skip-existing, not re-insert.
      const ruleStart = dupDate; // inclusive — the existing Saturday itself
      const ruleEnd = dayKeyFromToday(36); // a few weekends past it
      const ruled = await ctx.request.post(`${PLANNINGDAYS}/create/rule`, {
        headers: { authorization: adminBearer, 'content-type': 'application/json' },
        data: {
          startDate: ruleStart,
          endDate: ruleEnd,
          everyMonday: false,
          everyTuesday: false,
          everyWednesday: false,
          everyThursday: false,
          everyFriday: false,
          everySaturday: true,
          everySunday: false,
          locationId: locDuplicate.locationId,
        },
      });
      expect(
        ruled.status(),
        `the rule-wizard must 201 (skip-existing, not reject) — got ${ruled.status()}: ${await ruled.text()}`,
      ).toBe(201);
      const created = (await ruled.json()) as { id: string; planningDate: string }[];
      for (const d of created) {
        createdIds.push(d.id);
      }
      expect(
        created.some((d) => d.planningDate === dupDate),
        `the rule-wizard must SKIP the already-existing ${dupDate} (idempotent), not re-create it — ` +
          `created ${JSON.stringify(created.map((d) => d.planningDate))}`,
      ).toBe(false);
      // It still creates the OTHER Saturdays in the window (it did not no-op wholesale).
      expect(
        created.length,
        'the rule-wizard creates the remaining (non-existing) Saturdays in the window',
      ).toBeGreaterThan(0);
    } finally {
      await ctx.close();
    }
  });

  test('[happy] the setup wizard bulk-creates days across a range filtered by weekday', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);

      // A future window at the fresh seeded location (the wizard defaults Sat+Sun
      // ticked). Picking the fresh location keeps every generated (date,
      // location) off the V34 seed days, so the bulk-create yields a clean
      // non-empty result (no idempotent skips on a fresh tenant window).
      const start = dayKeyFromToday(20);
      const end = dayKeyFromToday(34);

      await page.goto('/planningsetup?lang=de');
      await expect(page.getByTestId('planning-setup-form')).toBeVisible();
      await page.getByTestId('planning-setup-start').locator('input').fill(start);
      await page.getByTestId('planning-setup-end').locator('input').fill(end);
      // Sat+Sun are default-ticked (PlanningDaySetupController.js:8-10); select
      // this test's OWN fresh location explicitly (T-21 isolation — overrides the
      // first-location default). Search by the unique seeded name (virtualised
      // long list, J-5 T-27).
      await selectAfOption(
        page,
        'planning-setup-location-select',
        locWizard.locationId,
        locWizard.locationName,
      );

      // PARITY SHOT: the populated setup wizard form (range + weekdays + location)
      // BEFORE generate (capture-before-assert).
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-planning-setup-form.png`,
        fullPage: true,
      });

      // Only WAIT for the 201 to land — do NOT read its body. The wizard navigates
      // back to /planning on bus-success, and a SPA nav on POST-success evicts the
      // captured response body (`genResp.json()` then throws "No data found" —
      // [[project_spa_nav_evicts_post_response_body]]; the prior flaky-passes-on-retry
      // T-20 fanout red). `/create/rule` returns a LIST (no single-resource Location
      // header), so the deterministic source for the created days is a RE-GET of the
      // paged list, scoped to the fresh window + the fresh location — every Sat/Sun
      // the wizard expanded the rule into (a fresh location + fresh window means the
      // wizard created exactly these days, nothing skipped).
      const generated = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new URL(r.url()).pathname === `${PLANNINGDAYS}/create/rule` &&
          r.status() === 201,
      );
      await page.getByTestId('planning-setup-generate-button').click();
      await generated;

      // On bus-success the wizard routes back to /planning where the generated
      // days appear.
      await expect(page).toHaveURL('/planning');

      // Re-GET the created days from the real paged list (never the evicted POST
      // body): the wizard's window [start, end] at THIS test's OWN fresh location
      // holds exactly the Sat/Sun days it created. Because `locWizard` is dedicated
      // to this test (T-21), every row at that location IS a wizard day — the
      // re-GET (filtered to `locWizard.locationId`, [start, end] matching the
      // wizard's exact inclusive window) tracks EVERY day the wizard created, so
      // `afterAll` deletes them all (no untracked survivor pollutes the tenant).
      // Track them for afterAll cleanup + assert the bulk-create count off the
      // authoritative server state.
      // Pass the `Day.From` lower bound = the wizard window start so accumulated
      // EARLIER days (the +1..+19 days other tests create + V34 seed + prior-run
      // residue on the never-truncated tenant) are dropped server-side and cannot
      // push the wizard's +20..+34 days off this single page (offset 0, size 50) —
      // making the re-GET an AUTHORITATIVE, complete capture of every wizard day.
      const paged = await ctx.request.post(`${PLANNINGDAYS}/page/0/50`, {
        headers: { authorization: adminBearer, 'content-type': 'application/json' },
        data: { sorting: { planningDate: 'asc' }, searchFilter: { from: start } },
      });
      expect(paged.status(), 'the planning list pages the wizard-created days').toBe(200);
      const pagedBody = (await paged.json()) as { items: PlanningDayListRow[] };
      const created = pagedBody.items.filter(
        (d) =>
          d.locationId === locWizard.locationId && d.planningDate >= start && d.planningDate <= end,
      );
      expect(created.length, 'the wizard bulk-created at least one weekend day').toBeGreaterThan(0);
      for (const d of created) {
        createdIds.push(d.id);
        const dow = new Date(`${d.planningDate}T00:00:00`).getDay();
        expect(dow === 0 || dow === 6, `generated day ${d.planningDate} is a Sat/Sun`).toBe(true);
      }

      // The first generated day renders in the /planning future-days list (the
      // screen wires to the real backend round-trip).
      await expect(
        page.getByTestId(`planning-row-${created[0]!.id}`),
        'a wizard-generated day renders in the future-days list',
      ).toBeVisible();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-6',
        caption:
          'J-6 · planning setup · the wizard bulk-creates planning days across a date range filtered ' +
          'by weekday (every Sat+Sun between start/end) at a location; the created days appear in ' +
          'the /planning list (real rule-expand endpoint)',
        acTag: 'happy',
      });
    }
  });

  test('[key-error] deleting a planning day cascade-deletes its assignments and removes it from the list; a non-admin non-creator PILOT is forbidden (403)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);

      // Create a FULLY-CREWED day (3 assignment rows) via the real API as
      // clubadmin4 (the creator), on a fresh future date at the seeded location.
      const dayDate = dayKeyFromToday(17);
      const id = await createPlanningDay(ctx, adminBearer, createdIds, {
        planningDate: dayDate,
        locationId: locDelete.locationId,
        instructorPersonId: masterdata.instructorId,
        towingPilotPersonId: masterdata.towPilotId,
        flightOperatorPersonId: masterdata.flightOpId,
      });

      // The detail confirms all 3 typed assignment rows exist before delete (the
      // 3 nullable person ids project the generic assignment rows — oracle).
      const before = await ctx.request.get(`${PLANNINGDAYS}/${id}`, {
        headers: { authorization: adminBearer },
      });
      expect(before.status()).toBe(200);
      const beforeBody = (await before.json()) as PlanningDayRequest;
      expect(beforeBody.instructorPersonId, 'the day carries its instructor assignment').toBe(
        masterdata.instructorId,
      );
      expect(beforeBody.towingPilotPersonId).toBe(masterdata.towPilotId);
      expect(beforeBody.flightOperatorPersonId).toBe(masterdata.flightOpId);

      // AUTHZ PROBE (real low-priv PILOT, not admin, not the creator): the delete
      // is gated ClubAdmin-OR-creator, so the PILOT is FORBIDDEN (403) — and the
      // day still exists afterwards (the gate held, no data lost). The mock-admin
      // would have hidden this gap ([[project_real_idp_real_roles_catches_authz_gaps]]).
      const forbidden = await ctx.request.delete(`${PLANNINGDAYS}/${id}`, {
        headers: { authorization: pilotBearer },
      });
      expect(
        forbidden.status(),
        `a non-admin non-creator PILOT must be forbidden from deleting — got ${forbidden.status()}`,
      ).toBe(403);
      const stillThere = await ctx.request.get(`${PLANNINGDAYS}/${id}`, {
        headers: { authorization: adminBearer },
      });
      expect(stillThere.status(), 'the forbidden delete left the day intact').toBe(200);

      // HAPPY DELETE through the UI as clubadmin4 (the creator): open the list,
      // kebab → delete → confirm → the row leaves the list. Mirrors the mock-spec
      // delete flow (the kebab item is page-level, the confirm fires the DELETE).
      await page.goto('/planning?lang=de');
      await expect(page.getByTestId('planning-list')).toBeVisible();
      const row = page.getByTestId(`planning-row-${id}`);
      await expect(row, 'the crewed day renders in the list before delete').toBeVisible();

      const deleted = page.waitForResponse(
        (r) =>
          r.request().method() === 'DELETE' &&
          new URL(r.url()).pathname === `${PLANNINGDAYS}/${id}` &&
          r.status() === 204,
      );
      await page.getByTestId(`planning-kebab-${id}`).click();
      await page.getByTestId(`planning-delete-${id}`).click();
      await page.getByTestId('planning-delete-confirm').click();
      await deleted;

      // The day leaves the future-days list (real soft-delete excluded from reads).
      await expect(row, 'the deleted day no longer renders in the list').toHaveCount(0);

      // The day + its 3 cascade-deleted assignments are gone — the detail 404s
      // (the assignments cascaded with the parent; fk_pda_planning_day_id ON
      // DELETE CASCADE, V4 — re-creating the SAME (date, location) now succeeds,
      // not a 409, proving the row is truly gone, and the create tracks for cleanup).
      const after = await ctx.request.get(`${PLANNINGDAYS}/${id}`, {
        headers: { authorization: adminBearer },
      });
      expect(after.status(), 'the deleted planning day is no longer readable (404)').toBe(404);
      const recreate = await createPlanningDay(ctx, adminBearer, createdIds, {
        planningDate: dayDate,
        locationId: locDelete.locationId,
      });
      expect(
        recreate,
        'the freed (date, location) accepts a new day — the prior row truly deleted',
      ).toBeTruthy();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-6',
        caption:
          'J-6 · planning · deleting a planning day (kebab → confirm) cascade-deletes its 3 crew ' +
          'assignments and the day leaves the future-days list (V4 ON DELETE CASCADE); a real ' +
          'low-privilege PILOT — neither club admin nor the record creator — is FORBIDDEN (403) ' +
          'from deleting it (the admin-or-creator gate, proven with a real low-priv principal)',
        acTag: 'key-error',
      });
    }
  });

  test('[edge] tenant isolation: a planning day created by club A is not readable by club B (404)', async ({
    browser,
  }) => {
    const ctx = await browser.newContext({ baseURL });
    try {
      // Create a day as seed-club-1 (clubadmin4 / adminBearer).
      const id = await createPlanningDay(ctx, adminBearer, createdIds, {
        planningDate: dayKeyFromToday(19),
        locationId: locTenant.locationId,
      });

      // Capture club B's admin Bearer through real Keycloak (a DIFFERENT tenant).
      const bCtx = await browser.newContext({ baseURL });
      const bPage = await bCtx.newPage();
      let clubBBearer: string;
      try {
        const reqPromise = bPage.waitForRequest(
          (req) =>
            req.url().includes('/api/v1/') &&
            typeof req.headers()['authorization'] === 'string' &&
            /^Bearer /i.test(req.headers()['authorization']!),
        );
        await loginAsClubAdmin(bPage, twoClubs.clubB);
        await bPage.goto('/planning');
        clubBBearer = (await reqPromise).headers()['authorization']!;
      } finally {
        await bCtx.close();
      }

      // seed-club-1's admin reads it fine (the positive control — the id is valid).
      const ownRead = await ctx.request.get(`${PLANNINGDAYS}/${id}`, {
        headers: { authorization: adminBearer },
      });
      expect(ownRead.status(), 'the owning tenant reads its own day').toBe(200);

      // Club B's admin CANNOT read seed-club-1's day — the read is @TenantId-scoped
      // on operating_club_id (V4), so a cross-tenant GET 404s (NOT 403 — the row
      // is invisible to the other tenant, not forbidden). The J-0/J-1/J-5 pattern.
      const crossTenant = await ctx.request.get(`${PLANNINGDAYS}/${id}`, {
        headers: { authorization: clubBBearer },
      });
      expect(
        crossTenant.status(),
        `club B must NOT read club A's planning day (cross-tenant 404) — got ${crossTenant.status()}`,
      ).toBe(404);
    } finally {
      await ctx.close();
    }
  });

  test('[happy/email] PlanningDayNotificationJob run-now → imminent (day+1, club address) + week-ahead (day+7, assigned crew) mails land in mailpit', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);

      // Clean slate so the per-recipient mailpit assertions are unambiguous (the
      // mailpit-client fails loud on >1 match for an address). The club address is
      // fixed (seed-club-1's V34 notification address), so a purge + creating
      // EXACTLY ONE day+1 day keeps the imminent club mail a single match.
      await purgeMailpit();

      // IMMINENT (day+1): one planning day dated exactly tomorrow at the seeded
      // location, WITH a real J-5 reservation on that day+location → the job
      // chooses planningday-ok (takes place) and mails the CLUB address.
      const imminentDate = dayKeyFromToday(1);
      const imminentId = await createPlanningDay(ctx, adminBearer, createdIds, {
        planningDate: imminentDate,
        locationId: locNotify.locationId,
      });
      const reservationId = await seedReservationOnPlanningDay(ctx.request, adminBearer, {
        planningDate: imminentDate,
        locationId: locNotify.locationId,
        aircraftId: masterdata.aircraftId,
        pilotPersonId: masterdata.instructorId,
        reservationTypeId,
      });
      createdReservationIds.push(reservationId);

      // WEEK-AHEAD (day+7): one planning day dated exactly +7 with all 3 crew
      // assigned → each assigned person (unique-per-run email) gets a
      // planningday-assignment-notification.
      const weekAheadId = await createPlanningDay(ctx, adminBearer, createdIds, {
        planningDate: dayKeyFromToday(7),
        locationId: locNotify.locationId,
        instructorPersonId: masterdata.instructorId,
        towingPilotPersonId: masterdata.towPilotId,
        flightOperatorPersonId: masterdata.flightOpId,
      });
      expect(imminentId && weekAheadId).toBeTruthy();

      // Fire the guarded run-now affordance (dev/test profile + ClubAdmin) — the
      // real job runs both exact-date passes for clubadmin4's own club. The J-15
      // jobs console is not built, so this is the deterministic e2e trigger.
      const run = await ctx.request.post(`${PLANNINGDAYS}/notifications/run`, {
        headers: { authorization: adminBearer },
      });
      expect(
        run.status(),
        `run-now must 200 for a ClubAdmin — got ${run.status()}: ${await run.text()}`,
      ).toBe(200);
      const summary = (await run.json()) as {
        imminentMailCount: number;
        weekAheadMailCount: number;
      };
      // At least our imminent day + our 3 week-ahead assignees were mailed (delta —
      // a co-located run could leave another day+1/+7, so assert >=, not ==).
      expect(
        summary.imminentMailCount,
        'the imminent pass mailed ≥1 club mail',
      ).toBeGreaterThanOrEqual(1);
      expect(
        summary.weekAheadMailCount,
        'the week-ahead pass mailed the 3 assignees',
      ).toBeGreaterThanOrEqual(3);

      // IMMINENT mail landed at the CLUB address (planningday-ok — the day has a
      // reservation). The imminent pass mails the club address ONCE PER day+1
      // planning day, and seed-club-1's tenant is never truncated — a prior /
      // co-located run (or the V34 seed's day, if first-applied so it lands on
      // day+1 relative to this run) can leave a SECOND day+1 day, so a second
      // legit club mail to the SAME address is by design (PlanningDayNotificationJobIT:
      // "two day+1 days → two club mails"). Key on the EXPECTED subject rather
      // than demanding a singleton inbox — still fails loud if an UNEXPECTED
      // template (e.g. a stray cancel) lands at the club address. The
      // imminentMailCount >= 1 assertion above already models this delta.
      const clubMail = await waitForMessageWithSubject(
        SEED_CLUB_NOTIFICATION_ADDRESS,
        'Flugbetriebstag findet statt',
      );
      expect(
        clubMail.Subject,
        'the imminent club mail is the planningday-ok template (the day takes place)',
      ).toBe('Flugbetriebstag findet statt');

      // WEEK-AHEAD assignment mail landed at EACH assigned person's unique email.
      const instructorMail = await waitForMessage(masterdata.instructorEmail);
      expect(instructorMail.Subject).toBe('Erinnerung: Einteilung Flugbetriebstag');
      const towPilotMail = await waitForMessage(masterdata.towPilotEmail);
      expect(towPilotMail.Subject).toBe('Erinnerung: Einteilung Flugbetriebstag');
      const flightOpMail = await waitForMessage(masterdata.flightOpEmail);
      expect(flightOpMail.Subject).toBe('Erinnerung: Einteilung Flugbetriebstag');
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-6',
        caption:
          'J-6 · planning notifications · running the PlanningDayNotificationJob (the guarded ' +
          'run-now affordance) mails the imminent (day+1) planning-day status to the club’s ' +
          'notification address (planningday-ok, the day has a reservation) and a week-ahead (day+7) ' +
          'reminder to each of the 3 assigned crew members — all land in mailpit (real job, real SMTP)',
        acTag: 'happy',
      });
    }
  });
});

// ===========================================================================
// MIGRATED-DATA real chain — a real legacy planning day, exported + migrated
// through the live chain, renders in the MIGRATED TestClub's list (with its
// migrated assignments + fan-out-resolved own-club location). Rides the SAME
// real bundle the J-0c fan-out spec ingests; runs only when the fanout's real
// export ran (J5_BUNDLE_SOURCE=real). STUB — T-11 wires the PlanningDay mapper
// bindings + producer SELECT; T-16 thickens this read.
// ===========================================================================
test.describe('Planning days — migrated legacy planning day renders (real-idp)', () => {
  test.describe.configure({ mode: 'serial', retries: 0 });

  test.skip(
    !useRealBundle(),
    'migrated-planning-day render requires the real legacy export (J5_BUNDLE_SOURCE=real, fanout only)',
  );

  let baseURL: string;
  let migratedAdmin: MigratedClubAdmin;
  let migratedBearer: string;

  test.beforeAll(async ({ browser }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    // The fanout ingests the real bundle via fan-out-migration-parity.spec.ts
    // (J-0c) earlier in the same Playwright invocation; resolve the migrated
    // TestClub admin by OWNERSHIP (the J-5 migrated-read pattern). The resolver
    // widens the hook budget for the cold ingest+enumeration path.
    const resolved = await resolveMigratedTestClubAdmin(browser, baseURL, testInfo);
    migratedAdmin = resolved.admin;
    migratedBearer = resolved.bearer;
  });

  test('[migration/parity] the migrated legacy planning day renders under its migrated TestClub tenant, identity-matched to legacy', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsMigratedTestClubAdmin(page, migratedAdmin);

      // The T-11 legacy fixture (`4 or 5 Insert Test Data.sql:1033-1122`) seeds 3
      // planning days on the legacy TestClub with the unique remarks 'Test' /
      // 'Test2' / 'Test3' (the last dated GETDATE()+100), each with crew
      // assignments at the LSZK location. PlanningDay is fan-out NO, but its
      // Location FK fans out → the migrated day must point at its OWN club's
      // Location replica via the (legacy_guid, club_id) ForeignKeyResolver. Assert
      // the migrated day by its IDENTIFYING legacy remark (the `info` field), not a
      // bare count — distinguishing "the T-11 legacy day round-tripped" from "some
      // other day exists". We key on 'Test3' (the +100 day): deterministically far
      // future + untouched by the notification passes (+1/+7).
      const paged = await ctx.request.post(`${PLANNINGDAYS}/page/0/50`, {
        headers: { authorization: migratedBearer, 'content-type': 'application/json' },
        data: { sorting: { planningDate: 'asc' } },
      });
      expect(paged.status(), 'the migrated TestClub can page its migrated planning days').toBe(200);
      const body = (await paged.json()) as { items: PlanningDayListRow[]; totalRows: number };

      const migrated = body.items.find((d) => d.info === 'Test3');
      expect(
        migrated,
        `the migrated legacy planning day (remark "Test3") must be present for the migrated ` +
          `TestClub — the T-11 legacy→export→migrate→render round-trip. Got ` +
          `${body.items.length} row(s): ${JSON.stringify(body.items.map((d) => d.info))}`,
      ).toBeTruthy();

      // Its Location FK resolved to the migrated club's OWN-club Location replica
      // (the J-0b own-club fan-out invariant): the locationId is present and
      // resolves to an active location IN THE MIGRATED TENANT (a 200 read as the
      // migrated admin) — a cross-club replica would 404 here. This is the
      // load-bearing migrate-fidelity assertion (the day points at its own copy).
      expect(migrated!.locationId, 'the migrated day carries a resolved location FK').toBeTruthy();
      const ownLocation = await ctx.request.get(`/api/v1/locations/${migrated!.locationId}`, {
        headers: { authorization: migratedBearer },
      });
      expect(
        ownLocation.status(),
        'the migrated day’s location resolves to the migrated club’s OWN Location replica (own-club FK)',
      ).toBe(200);

      // It renders on the migrated TestClub admin's /planning list (the screen
      // wires to the migrated data). Identify the migrated row by its id — a
      // residual day cannot satisfy this.
      await page.goto('/planning?lang=de');
      await expect(page.getByTestId('planning-list')).toBeVisible();
      await expect(
        page.getByTestId(`planning-row-${migrated!.id}`),
        'the identified migrated planning day renders in the migrated tenant’s future-days list',
      ).toBeVisible();
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-planning-migrated-list.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-6',
        caption:
          'J-6 · migrated planning day · a real legacy planning day (remark "Test3", with crew ' +
          'assignments + a fan-out-resolved own-club location), exported + migrated through the live ' +
          'chain, renders on the migrated TestClub’s /planning list — identity-matched to legacy and ' +
          'pointing at its OWN club’s Location replica (full legacy→export→migrate→Keycloak→UI chain)',
        acTag: 'happy',
      });
    }
  });
});
