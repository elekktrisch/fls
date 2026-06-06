import { test, expect, type Browser, type BrowserContext, type TestInfo } from '@playwright/test';

import {
  loginAsReservationAdmin,
  captureReservationAdminBearer,
  resolveMigratedTestClubAdmin,
  loginAsMigratedTestClubAdmin,
  useRealBundle,
  SEED_CLUB_A_ID,
  type MigratedClubAdmin,
} from './_helpers/reservation-parity-fixture';
import { proofVideo } from './_helpers/proof-video';
import { waitForMessage } from './_helpers/mailpit-client';
import { selectAfOption } from '../_helpers/af-select';

/**
 * J-6 planning-days real chain (live Keycloak auth + real Spring backend + real
 * Postgres) — the journey's `parity_test`. SPEC SKELETON ONLY (T-01).
 *
 * SPEC STUB. This file commits the real-idp parity SHAPE — the flow steps +
 * selectors + thin assertions — before the backend (`ch.alpenflight.planning`),
 * the SPA `/planning` screens, the `PlanningDayNotificationJob`, and the
 * migration `MapperLegacyBindings` wiring exist. Every case is `test.fixme`
 * (parsed + typechecked, NOT run); T-16 thickens these to full real assertions
 * from the behavior oracle + runs the §4 gate via `e2e-driver`.
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

const PLANNINGDAYS = '/api/v1/planningdays';

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

/** A planning-day create/update request body (3 nullable person ids — oracle). */
interface PlanningDayRequest {
  date: string;
  locationId: string;
  instructorPersonId?: string;
  towingPilotPersonId?: string;
  flightOperatorPersonId?: string;
  remarks?: string;
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
  /** Every planning-day id this group created in seed-club-1 — deleted in afterAll. */
  const createdIds: string[] = [];
  let cleanupCtx: BrowserContext;

  test.beforeAll(async ({ browser }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    createdIds.length = 0;
    adminBearer = await captureReservationAdminBearer(browser, baseURL);
    // STUB (T-04+): seed planning masterdata (location + 3 crew persons with
    // memberships) here via the ship-time planning-parity-fixture; capture a
    // low-privilege PILOT principal for the authz-gated delete/update case.
    cleanupCtx = await browser.newContext({ baseURL });
  });

  test.afterAll(async () => {
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

  test.fixme('[happy] create a planning day through the UI (date + location + 3-role crew + remarks) → it renders in the future-days list', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);

      // Baseline list count BEFORE the create → DELTA, not an absolute. Pin the
      // German cold-start locale (the primary market + the gallery locale).
      await page.goto('/planning?lang=de');
      await expect(page.locator('h1')).toContainText('Planung');
      await expect(page.getByTestId('planning-list')).toBeVisible();

      await page.getByTestId('planning-new-button').locator('button').click();
      await expect(page).toHaveURL('/planning/new');

      // STUB: the seeded location + 3 crew person ids come from the ship-time
      // planning-parity-fixture; pick them from the real <af-select>s.
      await page.getByTestId('planning-date').locator('input').fill(dayKeyFromToday(5));
      // await selectAfOption(page, 'planning-location-select', masterdata.locationId);
      // await selectAfOption(page, 'planning-instructor-select', masterdata.instructorId);
      // await selectAfOption(page, 'planning-towpilot-select', masterdata.towPilotId);
      // await selectAfOption(page, 'planning-flightop-select', masterdata.flightOpId);
      await page.getByTestId('planning-remarks').locator('textarea').fill('J-6 real-chain day');

      // PARITY SHOT (legacy planning edit form ↔ AlpenFlight) — capture the
      // populated form BEFORE save (capture-before-assert).
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-planning-form.png`,
        fullPage: true,
      });

      const createdResp = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new URL(r.url()).pathname === '/api/v1/planningdays' &&
          r.status() === 201,
      );
      await page.getByTestId('planning-save-button').click();
      const resp = await createdResp;
      const location = resp.headers()['location'];
      const id = new URL(location!, 'http://localhost').pathname.split('/').pop() ?? '';
      expect(id).toMatch(/^[0-9a-f-]{36}$/);
      createdIds.push(id);

      await expect(page).toHaveURL('/planning');
      await expect(page.getByTestId(`planning-row-${id}`)).toBeVisible();

      // PARITY SHOT: the populated /planning list (legacy planning table ↔ list).
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

  test.fixme('[happy] editing a planning day’s crew persists and reflects on reopen', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);
      // STUB: create a day via createPlanningDay, open its edit form, reassign a
      // role via selectAfOption(page, 'planning-instructor-select', …), save,
      // reopen, assert the change persisted (real PUT round-trip).
      expect(createPlanningDay).toBeTruthy();
      expect(selectAfOption).toBeTruthy();
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

  test.fixme('[happy] the edit screen shows that day’s AircraftReservations inline (J-5 join)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);
      // STUB: create a day + a reservation on it (J-5 API), open the edit form,
      // assert the inline `planning-reservations-panel` lists the reservation
      // and links to /reservations/:id/edit.
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-6',
        caption:
          'J-6 · planning · the planning-day edit screen lists that day’s aircraft reservations ' +
          'inline (the J-5 AircraftReservation read-side joined by club + date + location)',
        acTag: 'happy',
      });
    }
  });

  test.fixme('[key-error] a duplicate (date, location) planning day is rejected 409', async ({
    browser,
  }) => {
    const ctx = await browser.newContext({ baseURL });
    try {
      // STUB: create a day, then POST a second with the SAME (date, location) →
      // 409 (V4 ux_pln_club_date_loc). The rule-wizard variant skips idempotently.
      const dupDate = dayKeyFromToday(9);
      const first = await createPlanningDay(ctx, adminBearer, createdIds, {
        date: dupDate,
        locationId: '00000000-0000-0000-0000-000000000000', // STUB: real seeded location
      });
      expect(first).toBeTruthy();
      const dup = await ctx.request.post(PLANNINGDAYS, {
        headers: { authorization: adminBearer, 'content-type': 'application/json' },
        data: { date: dupDate, locationId: '00000000-0000-0000-0000-000000000000' },
      });
      expect(dup.status(), 'a duplicate (date, location) must 409').toBe(409);
      const body = (await dup.json()) as { key?: string };
      expect(body.key).toBe('planning.day.duplicate');
    } finally {
      await ctx.close();
    }
  });

  test.fixme('[happy] the setup wizard bulk-creates days across a range filtered by weekday', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);
      // STUB: drive /planningsetup — start/end + Sat+Sun + location → generate.
      // Track every created id for cleanup (re-GET the list to collect them);
      // assert the created days are all Sat/Sun and appear on /planning.
      await page.goto('/planningsetup?lang=de');
      await expect(page.getByTestId('planning-setup-form')).toBeVisible();
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
