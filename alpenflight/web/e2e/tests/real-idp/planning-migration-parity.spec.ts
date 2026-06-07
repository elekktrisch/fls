import { test, expect, type Browser, type BrowserContext, type TestInfo } from '@playwright/test';

import {
  loginAsReservationAdmin,
  captureReservationAdminBearer,
  fetchReservationTypeId,
  resolveMigratedTestClubAdmin,
  loginAsMigratedTestClubAdmin,
  useRealBundle,
  SEED_CLUB_A_ID,
  type MigratedClubAdmin,
} from './_helpers/reservation-parity-fixture';
import {
  seedPlanningMasterdata,
  seedReservationOnPlanningDay,
  type PlanningMasterdata,
} from './_helpers/planning-parity-fixture';
import { proofVideo } from './_helpers/proof-video';
import { waitForMessage } from './_helpers/mailpit-client';
import { selectAfOption } from '../_helpers/af-select';

/**
 * J-6 planning-days real chain (live Keycloak auth + real Spring backend + real
 * Postgres) — the journey's `parity_test`.
 *
 * T-13 (capture pull-forward, operator priority): the clean-seed HAPPY PATH is
 * now un-fixme'd and runs FULLY REAL against the real-idp stack — it drives the
 * real `/planning` screens (list render of the V34 seed days · create a day with
 * date + location + 3-role crew + remarks · edit crew · the inline J-5
 * reservations panel · the setup wizard's weekday bulk-create) and captures the
 * screenshots + pass-video the J-6 gallery page renders. The HARDER cases stay
 * `test.fixme` for T-16 (duplicate-409, delete-cascade, tenant-isolation 404,
 * the notification-job→mailpit assertion, and the migrated-parity read) — they
 * are NOT load-bearing for getting J-6 screens onto the deployed gallery.
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
 *
 * ── SHIP-TIME WIRING (T-04..T-11/T-16 fill these in) ─────────────────────────
 *   - a `planning-parity-fixture.ts` (sibling of `reservation-parity-fixture.ts`)
 *     seeds the planning masterdata (location + 3 crew persons with memberships)
 *     and a low-privilege PILOT principal (oracle: delete/update is ClubAdmin OR
 *     creator — drive a real low-priv principal so mock-admin doesn't hide the
 *     authz gap, [[project_real_idp_real_roles_catches_authz_gaps]]);
 *   - the "run planning notifications now" guarded test affordance (T-10) the
 *     email case triggers (the J-15 jobs console is not built);
 *   - the migrated-planning-day identifying fields (unique remark + the
 *     fan-out-resolved own-club location) the migrated-read case matches on.
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

async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  return browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
}

/** A row of the paged planning-day list envelope. */
interface PlanningDayListRow {
  id: string;
  date: string;
  locationId: string;
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
 * so the group's `afterAll` can delete it (retry-isolation).
 *
 * STUB: kept here as the shape the fixme'd cases use; the masterdata it
 * references (location + crew person ids) is seeded by the ship-time
 * `planning-parity-fixture.ts` (T-04+).
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
  /** The clean-seed default reservation type (V31 seed) the inline-panel reservation uses. */
  let reservationTypeId: string;
  /** Every planning-day id this group created in seed-club-1 — deleted in afterAll. */
  const createdIds: string[] = [];
  /** Every reservation id this group created in seed-club-1 — deleted in afterAll. */
  const createdReservationIds: string[] = [];
  let cleanupCtx: BrowserContext;

  test.beforeAll(async ({ browser, request }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    createdIds.length = 0;
    createdReservationIds.length = 0;
    adminBearer = await captureReservationAdminBearer(browser, baseURL);
    // Seed (as clubadmin4, through the REAL create APIs) a fresh location + 3
    // crew persons WITH a seed-club-1 membership so the create/edit form's 3
    // crew <af-select>s offer them (the V34 seed persons carry no membership →
    // not pickable) + a fresh aircraft for the inline-panel reservation. The
    // PILOT-vs-creator authz delete/update probe stays T-16.
    masterdata = await seedPlanningMasterdata(request, adminBearer);
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
        locationId: masterdata.locationId,
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

  test('[happy] the saved-day edit form shows the POPULATED inline AircraftReservations list (J-5 join) — the gallery form parity shot', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);

      // Create a planning day on the seeded location, then SEED A REAL J-5
      // reservation on that day's EXACT date + location (via the real
      // reservations create API — no mocking) so the inline per-day reservations
      // panel renders ≥1 `<af-reservation-row>`. The J-5 read-side join is
      // `listAircraftReservationsForDay(date)` filtered to the day's location
      // (planning.store.ts:210-223), so the reservation MUST be on the day's
      // exact date + location to surface — that day+location alignment is the
      // load-bearing bit (legacy PlanningDayEditController.js:96-104).
      const planningDate = dayKeyFromToday(13);
      const id = await createPlanningDay(ctx, adminBearer, createdIds, {
        planningDate,
        locationId: masterdata.locationId,
      });
      const reservationId = await seedReservationOnPlanningDay(ctx.request, adminBearer, {
        planningDate,
        locationId: masterdata.locationId,
        aircraftId: masterdata.aircraftId,
        pilotPersonId: masterdata.instructorId,
        reservationTypeId,
      });
      createdReservationIds.push(reservationId);

      // Open the SAVED day's edit form (mirrors the legacy saved-day
      // `/planning/:id/edit` form, which renders its inline reservations table).
      await page.goto(`/planning/${id}/edit?lang=de`);
      const panel = page.getByTestId('planning-reservations-panel');
      await expect(panel, 'the inline per-day reservations panel renders (J-5 join)').toBeVisible();
      await expect(page.getByTestId('planning-reservations-list')).toBeVisible();
      // The "new reservation" affordance pre-seeds J-5's create form with this
      // day's date + location (legacy PlanningDayEditController.js:128-132).
      await expect(panel.getByTestId('planning-new-reservation-button')).toBeVisible();

      // CAPTURE-AFTER-DATA-LOADED, CAPTURE-BEFORE-DEEP-ASSERT (J-5 rule): wait for
      // the seeded reservation's row to be visible (the list is POPULATED), then
      // take the gallery `form` parity shot BEFORE the deeper row-content asserts,
      // so a partial red still produces a populated-list shot to pair with legacy.
      const seededRow = page.getByTestId(`planning-reservation-${reservationId}`);
      await expect(
        seededRow,
        'the seeded reservation renders as an <af-reservation-row> in the inline list',
      ).toBeVisible();
      // GALLERY FORM PARITY SHOT (side=alpenflight, view=form): the saved-day edit
      // form with the populated inline reservations list — pairs against the
      // legacy saved-day edit form's inline reservations table (T-16 done-bar:
      // the form view shows the list on BOTH sides).
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-planning-form.png`,
        fullPage: true,
      });
      // The dedicated panel-only shot (optional second paired view) — same state.
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-planning-reservations-panel.png`,
        fullPage: true,
      });

      // Deeper asserts AFTER the shot: exactly the seeded reservation surfaces,
      // and the row carries its aircraft immatriculation (the feature resolves
      // the label and passes it to the row — af-reservation-row).
      await expect(
        page.locator('[data-testid^="planning-reservation-"]'),
        'exactly the one seeded reservation surfaces for this day+location',
      ).toHaveCount(1);
      await expect(seededRow).toContainText(masterdata.aircraftImmat);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-6',
        caption:
          'J-6 · planning · the SAVED planning-day edit form lists that day’s aircraft reservations ' +
          'inline, POPULATED — a real J-5 AircraftReservation seeded on the day’s exact date + ' +
          'location surfaces through the read-side join (club + date + location) as an ' +
          '<af-reservation-row>; pairs against the legacy saved-day edit form’s inline reservations ' +
          'table (form-view parity)',
        acTag: 'happy',
      });
    }
  });

  test.fixme('[key-error] a duplicate (date, location) planning day is rejected 409', async ({
    browser,
  }) => {
    const ctx = await browser.newContext({ baseURL });
    try {
      // T-16: create a day, then POST a second with the SAME (date, location) →
      // 409 (V4 ux_pln_club_date_loc). The rule-wizard variant skips idempotently.
      const dupDate = dayKeyFromToday(9);
      const first = await createPlanningDay(ctx, adminBearer, createdIds, {
        planningDate: dupDate,
        locationId: masterdata.locationId,
      });
      expect(first).toBeTruthy();
      const dup = await ctx.request.post(PLANNINGDAYS, {
        headers: { authorization: adminBearer, 'content-type': 'application/json' },
        data: { planningDate: dupDate, locationId: masterdata.locationId },
      });
      expect(dup.status(), 'a duplicate (date, location) must 409').toBe(409);
      const body = (await dup.json()) as { key?: string };
      expect(body.key).toBe('planning.day.duplicate');
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
      // the fresh seeded location explicitly (overrides the first-location
      // default). Search by the unique seeded name (virtualised long list, J-5 T-27).
      await selectAfOption(
        page,
        'planning-setup-location-select',
        masterdata.locationId,
        masterdata.locationName,
      );

      // PARITY SHOT: the populated setup wizard form (range + weekdays + location)
      // BEFORE generate (capture-before-assert).
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-planning-setup-form.png`,
        fullPage: true,
      });

      const generated = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new URL(r.url()).pathname === `${PLANNINGDAYS}/create/rule` &&
          r.status() === 201,
      );
      await page.getByTestId('planning-setup-generate-button').click();
      const genResp = await generated;
      // The backend returns the days actually created — every one a Sat/Sun in
      // the window at the fresh location. Track them for afterAll cleanup.
      const created = (await genResp.json()) as { id: string; planningDate: string }[];
      expect(created.length, 'the wizard bulk-created at least one weekend day').toBeGreaterThan(0);
      for (const d of created) {
        createdIds.push(d.id);
        const dow = new Date(`${d.planningDate}T00:00:00`).getDay();
        expect(dow === 0 || dow === 6, `generated day ${d.planningDate} is a Sat/Sun`).toBe(true);
      }

      // On bus-success the wizard routes back to /planning where the generated
      // days appear; assert the first generated day renders in the list.
      await expect(page).toHaveURL('/planning');
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

  test.fixme('[key-error] deleting a planning day cascade-deletes its assignments and removes it from the list', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);
      // STUB: create a day with full crew, DELETE it via the real API (or the UI
      // row action), assert it leaves the list AND its assignment rows are gone
      // (fk_pda_planning_day_id ON DELETE CASCADE, V4). Drive the authz with the
      // low-priv PILOT principal too (delete is ClubAdmin OR creator — oracle).
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-6',
        caption:
          'J-6 · planning · deleting a planning day cascade-deletes its crew assignments and the ' +
          'day leaves the future-days list (V4 ON DELETE CASCADE)',
        acTag: 'key-error',
      });
    }
  });

  test.fixme('[edge] tenant isolation: a planning day created by club A is not readable by club B (404)', async ({
    browser,
  }) => {
    const ctx = await browser.newContext({ baseURL });
    try {
      // STUB: create a day as seed-club-1 (adminBearer), then GET it with club
      // B's Bearer (provisionTwoClubs / a second club admin) → 404. The J-0/J-1/
      // J-5 cross-tenant pattern (@TenantId-scoped on operating_club_id, V4).
      expect(SEED_CLUB_A_ID).toBeTruthy();
    } finally {
      await ctx.close();
    }
  });

  test.fixme('[happy/email] PlanningDayNotificationJob run-now → imminent (day+1, club address) + week-ahead (day+7, assigned crew) mails land in mailpit', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);
      // STUB (T-10): create a day+1 planning day (→ imminent club mail) and a
      // day+7 planning day with assigned crew (→ week-ahead per-person mail),
      // trigger the guarded "run planning notifications now" affordance, then
      // assert via the mailpit-client:
      //   - waitForMessage(clubNotificationAddress) → planningday-ok/cancel;
      //   - waitForMessage(<each assigned person's email>) → planningday-assignment.
      // Exact 7-day window + recipient set confirmed at ship time via legacy-oracle.
      expect(waitForMessage).toBeTruthy();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-6',
        caption:
          'J-6 · planning notifications · running the PlanningDayNotificationJob mails the imminent ' +
          '(day+1) planning-day status to the club’s notification address and a week-ahead (day+7) ' +
          'reminder to each assigned crew member — both land in mailpit (real job + real SMTP)',
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
    // TestClub admin by OWNERSHIP (the J-5 migrated-read pattern).
    const resolved = await resolveMigratedTestClubAdmin(browser, baseURL);
    migratedAdmin = resolved.admin;
    migratedBearer = resolved.bearer;
  });

  test.fixme('[migration/parity] the migrated legacy planning day renders under its migrated TestClub tenant, identity-matched to legacy', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsMigratedTestClubAdmin(page, migratedAdmin);

      // STUB (T-11/T-16): the T-11 legacy fixture seeds a planning day with
      // assignments on the legacy TestClub; the real export → migrate carries it
      // (PlanningDay is fan-out NO, but its Location FK fans out → the migrated
      // day must point at its OWN club's Location replica via the
      // (legacy_guid, club_id) ForeignKeyResolver). Assert the migrated day by
      // its IDENTIFYING fields (unique date + the own-club location), not a count.
      const paged = await ctx.request.post(`${PLANNINGDAYS}/page/0/50`, {
        headers: { authorization: migratedBearer, 'content-type': 'application/json' },
        data: { sorting: { date: 'asc' } },
      });
      expect(paged.status(), 'the migrated TestClub can page its migrated planning days').toBe(200);
      const body = (await paged.json()) as { items: PlanningDayListRow[]; totalRows: number };
      expect(body.totalRows).toBeGreaterThanOrEqual(1);
      // STUB: match the migrated day by its fixture-identifying date + assert its
      // locationId resolves to the migrated club's OWN Location replica.

      await page.goto('/planning?lang=de');
      await expect(page.getByTestId('planning-list')).toBeVisible();
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-planning-migrated-list.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-6',
        caption:
          'J-6 · migrated planning day · a real legacy planning day (with crew assignments + a ' +
          'fan-out-resolved own-club location), exported + migrated through the live chain, renders ' +
          'on the migrated TestClub’s /planning list — identity-matched to legacy (full ' +
          'legacy→export→migrate→Keycloak→UI chain, read via the migrated tenant)',
        acTag: 'happy',
      });
    }
  });
});
