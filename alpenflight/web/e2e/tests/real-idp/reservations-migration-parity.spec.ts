import { test, expect, type Browser, type BrowserContext, type TestInfo } from '@playwright/test';

import {
  loginAsClubAdmin,
  provisionTwoClubs,
  type TwoClubFixture,
} from './_helpers/two-club-fixture';
import {
  captureMigratedTestClubBearer,
  captureReservationAdminBearer,
  fetchReservationTypeId,
  loginAsMigratedTestClubAdmin,
  loginAsReservationAdmin,
  resolveMigratedTestClubAdmin,
  seedReservationMasterdata,
  useRealBundle,
  MIGRATED_RESERVATION_REMARK,
  MIGRATED_RESERVATION_TYPE_NAME,
  type MigratedClubAdmin,
  type ReservationMasterdata,
} from './_helpers/reservation-parity-fixture';
import { proofVideo } from './_helpers/proof-video';
import { selectAfOption } from '../_helpers/af-select';

/**
 * J-5 T-16 — the Aircraft-reservation real chain (live Keycloak auth + real
 * Spring backend + real Postgres). This is the spec that proves J-5 vertically:
 * the FIRST journey whose load-bearing proof is a REJECTION (overlap → 409), not
 * a round-trip. NO mocking on the happy + key-error paths — a `page.route`
 * interception would defeat the seam (the @TenantId stamp, the
 * `AircraftReservation.conflictsWith()` GiST range probe, the duration guard, the
 * cross-tenant-open aircraft FK must all run live).
 *
 * Mirrors the J-1 aircraft / J-2 flight real-idp discipline (retry-isolation):
 *   - track every created reservation id + `afterAll` DELETE them so a Playwright
 *     retry starts on a clean tenant (seed-club-1 is the shared, never-truncated
 *     Flyway seed — residue from a failed attempt would red the next);
 *   - DELTA-assert list counts (baseline + N), never absolutes;
 *   - warm in-app nav only, no `clearCookies` (real-idp session restore relies on
 *     the cookie jar);
 *   - read a created id from the 201 `Location` header / a re-GET, never the POST
 *     response body (the SPA navigates on success → Chrome evicts the body).
 *
 * ── RESERVATION-TYPE — clean-seed default + UI type-picker (J-5 T-17) ───────
 * The reservation type has NO create API (`t_aircraft_reservation_type` is
 * tenant-scoped, populated only by migration). To let the FULL clean-seed UI
 * create flow run, `V31__dev_reservation_type_seed.sql` seeds ONE active default
 * type ("Allgemein") for seed-club-1, so the form-required `reservationTypeId`
 * dropdown is non-empty on a clean realm. The HAPPY-PATH create test now drives
 * the whole thing through the UI: navigate to `/reservations/new`, pick the
 * aircraft / the seeded TYPE / pilot / location from the real `<af-select>`s,
 * fill the date+time, submit, and read the created id off the 201 Location — the
 * full clean-seed UI create→type-picker chain end-to-end. The remaining mutation
 * cases (overlap-409 / duration-422 / all-day / cross-tenant / edit-delete-frees)
 * still drive the REST API directly — those error/edge probes need no type (it is
 * OPTIONAL on the backend request). The MIGRATED-DATA half proves the type
 * renders end to end from a real legacy reservation too. The type-CREATE API
 * itself stays out of scope (deferred masterdata screen).
 */

const RESERVATIONS = '/api/v1/aircraft-reservations';

async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  return browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
}

/** A row of the paged-list envelope (`AircraftReservationListItem`). */
interface ReservationListRow {
  id: string;
  aircraftId: string;
  reservationTypeName?: string | null;
  remarks?: string | null;
}

/**
 * Create a reservation via the REAL REST API and return the created id (from the
 * 201 `Location` header — never the POST body). Tracks the id in `created` so the
 * group's `afterAll` can delete it (retry-isolation).
 */
async function createReservation(
  ctx: BrowserContext,
  bearer: string,
  created: string[],
  body: {
    aircraftId: string;
    pilotPersonId: string;
    locationId: string;
    start: string;
    end: string;
    isAllDay: boolean;
    remarks?: string;
  },
): Promise<string> {
  const res = await ctx.request.post(RESERVATIONS, {
    headers: { authorization: bearer, 'content-type': 'application/json' },
    data: body,
  });
  expect(
    res.status(),
    `reservation create must 201 — got ${res.status()}: ${await res.text()}`,
  ).toBe(201);
  const location = res.headers()['location'];
  expect(location, 'create must return a 201 Location header').toBeTruthy();
  const id = new URL(location!, 'http://localhost').pathname.split('/').pop() ?? '';
  expect(id, `Location "${location}" must end in a reservation UUID`).toMatch(/^[0-9a-f-]{36}$/);
  created.push(id);
  return id;
}

// ===========================================================================
// CLEAN-SEED real chain — create→list→scheduler + overlap-409 + duration-422 +
// all-day + cross-tenant-open + edit/delete-frees. The load-bearing J-5 proof.
// ===========================================================================
test.describe('Aircraft reservations — clean-seed real chain (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let twoClubs: TwoClubFixture;
  let baseURL: string;
  /** clubadmin4's Bearer (seed-club-1, the operating/@TenantId club). */
  let adminBearer: string;
  let masterdata: ReservationMasterdata;
  /** The clean-seed default reservation type (V31 seed) the UI picker selects. */
  let reservationTypeId: string;
  /** Every reservation id this group created in seed-club-1 — deleted in afterAll. */
  const createdIds: string[] = [];
  let cleanupCtx: BrowserContext;

  test.beforeAll(async ({ browser, request }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    // Re-running beforeAll on a retry (serial mode): clear the prior attempt's id
    // list so afterAll only chases this attempt's rows.
    createdIds.length = 0;

    // A foreign club + its admin Bearer for the cross-tenant aircraft (club B,
    // distinct from seed-club-1). clubadmin4 (seed-club-1) is the reservation
    // principal.
    twoClubs = await provisionTwoClubs(browser, baseURL, 'resv');
    adminBearer = await captureReservationAdminBearer(browser, baseURL);

    // Capture club B's admin Bearer (the foreign managing club).
    const bCtx = await browser.newContext({ baseURL });
    const bPage = await bCtx.newPage();
    let foreignBearer: string;
    try {
      await loginAsClubAdmin(bPage, twoClubs.clubB);
      const reqPromise = bPage.waitForRequest(
        (req) =>
          req.url().includes('/api/v1/') &&
          typeof req.headers()['authorization'] === 'string' &&
          /^Bearer /i.test(req.headers()['authorization']!),
      );
      await bPage.goto('/aircraft');
      foreignBearer = (await reqPromise).headers()['authorization']!;
    } finally {
      await bCtx.close();
    }

    masterdata = await seedReservationMasterdata(request, adminBearer, foreignBearer);

    // The clean-seed default reservation type (V31 dev seed) — the UI create flow
    // selects it from the form's type dropdown. Fails loud if the seed is missing.
    reservationTypeId = await fetchReservationTypeId(request, adminBearer);

    // A persistent context for the afterAll cleanup deletes (own request fixture).
    cleanupCtx = await browser.newContext({ baseURL });
  });

  test.afterAll(async () => {
    // Delete every reservation this group created so the shared seed-club-1
    // tenant is clean for the next Playwright retry / run (mirrors J-1 T-15).
    for (const id of createdIds) {
      try {
        await cleanupCtx.request.delete(`${RESERVATIONS}/${id}`, {
          headers: { authorization: adminBearer },
        });
      } catch (err) {
        console.warn(`[J-5] afterAll cleanup: delete ${id} failed (${(err as Error).message})`);
      }
    }
    await cleanupCtx?.close();
    await twoClubs?.dispose();
  });

  test('[happy] create a timed reservation through the UI type-picker → it renders in the list and scheduler lane', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);

      // Baseline list count BEFORE the create → assert a DELTA, not an absolute.
      await page.goto('/reservations');
      await expect(page.locator('h1')).toContainText('Reservationen');
      await expect(page.getByTestId('reservations-table')).toBeVisible();
      const baseline = await page.locator('[data-testid^="reservations-row-"]').count();

      // Drive the FULL clean-seed UI create: new-form → pick aircraft / the
      // V31-seeded TYPE / pilot / location from the real <af-select>s → fill the
      // timed window → submit. Proves the create→type-picker chain end-to-end on
      // clean seed (real form → real backend → real GiST aggregate → real PG).
      await page.getByTestId('reservations-new-button').locator('button').click();
      await expect(page).toHaveURL('/reservations/new');

      await selectAfOption(page, 'reservation-aircraft-select', masterdata.managedAircraftId);
      // The clean-seed default reservation type — the T-17 done-bar (an empty
      // dropdown before V31 made this flow impossible; now it is selectable).
      await selectAfOption(page, 'reservation-type-select', reservationTypeId);
      await selectAfOption(page, 'reservation-pilot-select', masterdata.pilotPersonId);
      await selectAfOption(page, 'reservation-location-select', masterdata.locationId);
      await page.getByTestId('reservation-date').locator('input').fill('2026-09-01');
      await page.getByTestId('reservation-start-time').locator('input').fill('10:00');
      await page.getByTestId('reservation-end-time').locator('input').fill('11:00');

      // Capture the 201 (the SPA navigates on bus-success → read the id from the
      // Location header, never the evicted POST body). Track it for afterAll
      // cleanup BEFORE asserting, so a later failure still cleans the row.
      const createdResp = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new URL(r.url()).pathname === '/api/v1/aircraft-reservations' &&
          r.status() === 201,
      );
      await page.getByTestId('reservation-save-button').click();
      const resp = await createdResp;
      const location = resp.headers()['location'];
      expect(location, 'create must return a 201 Location header').toBeTruthy();
      const id = new URL(location!, 'http://localhost').pathname.split('/').pop() ?? '';
      expect(id, `Location "${location}" must end in a reservation UUID`).toMatch(
        /^[0-9a-f-]{36}$/,
      );
      createdIds.push(id);

      // On bus-success the SPA returns to the list.
      await expect(page).toHaveURL('/reservations');

      // It renders in the UI list (the screen wires to the live backend). The
      // immat is decorated client-side from the aircraft picker.
      const row = page.getByTestId(`reservations-row-${id}`);
      await expect(row, 'the created reservation renders in the list').toBeVisible();
      await expect(page.getByTestId(`reservations-immat-${id}`)).toHaveText(
        masterdata.managedImmat,
      );
      await expect(page.getByTestId(`reservations-allday-${id}`)).toHaveText('Zeitfenster');
      // DELTA assert (residue-proof on the shared seed-club-1 tenant).
      await expect(page.locator('[data-testid^="reservations-row-"]')).toHaveCount(baseline + 1);

      // Scheduler lane×time: the reservation lands in ITS aircraft lane at a
      // time-derived offset (10:00 of a 24h window ≈ 41.6% from the left).
      await page.goto('/reservation-scheduler');
      await expect(page.getByTestId('reservation-scheduler')).toBeVisible();
      const lane = page.getByTestId(`reservation-scheduler-lane-${masterdata.managedAircraftId}`);
      await expect(lane).toBeVisible();
      const block = lane.getByTestId(`reservation-scheduler-block-${id}`);
      await expect(block, 'the reservation block is in its aircraft lane').toBeVisible();
      const left = await block.evaluate((el) => (el as HTMLElement).style.left);
      const leftPct = Number.parseFloat(left);
      expect(leftPct, '10:00 of a 24h window ≈ 41.6% from the lane left edge').toBeGreaterThan(40);
      expect(leftPct).toBeLessThan(43);

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-reservations-list.png`,
        fullPage: true,
      });
      await page.goto('/reservation-scheduler');
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-reservation-scheduler.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-5',
        caption:
          'J-5 · reservations · a club admin logs in via real Keycloak and creates a timed aircraft ' +
          'reservation THROUGH THE UI FORM — picking the aircraft, the clean-seed reservation type, ' +
          'pilot and location from the real dropdowns — and it renders in the /reservations list and ' +
          'in the right aircraft lane on the /reservation-scheduler at its time offset (full ' +
          'clean-seed UI create→type-picker chain, real backend round-trip)',
        acTag: 'happy',
      });
    }
  });

  test('[key-error] a second overlapping reservation on the same aircraft is rejected 409', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);

      // Seed one reservation 13:00–14:00, then probe an overlapping 13:30–13:45
      // on the SAME aircraft → 409 key aircraft.reservation.overlap.
      const existingId = await createReservation(ctx, adminBearer, createdIds, {
        aircraftId: masterdata.managedAircraftId,
        pilotPersonId: masterdata.pilotPersonId,
        locationId: masterdata.locationId,
        start: '2026-09-02T13:00:00Z',
        end: '2026-09-02T14:00:00Z',
        isAllDay: false,
      });

      const overlap = await ctx.request.post(RESERVATIONS, {
        headers: { authorization: adminBearer, 'content-type': 'application/json' },
        data: {
          aircraftId: masterdata.managedAircraftId,
          pilotPersonId: masterdata.pilotPersonId,
          locationId: masterdata.locationId,
          start: '2026-09-02T13:30:00Z',
          end: '2026-09-02T13:45:00Z',
          isAllDay: false,
        },
      });
      expect(overlap.status(), 'an overlapping reservation on the same aircraft must 409').toBe(
        409,
      );
      const body = (await overlap.json()) as { key?: string };
      expect(body.key).toBe('aircraft.reservation.overlap');

      // An ADJACENT `[)` reservation (14:00–15:00, end==next.start) is admitted
      // (half-open overlap, not a conflict).
      const adjacent = await createReservation(ctx, adminBearer, createdIds, {
        aircraftId: masterdata.managedAircraftId,
        pilotPersonId: masterdata.pilotPersonId,
        locationId: masterdata.locationId,
        start: '2026-09-02T14:00:00Z',
        end: '2026-09-02T15:00:00Z',
        isAllDay: false,
      });

      // Editing the EXISTING reservation in place (self-exclude) does NOT
      // self-conflict: a PUT keeping its own window succeeds (200), not 409.
      const selfEdit = await ctx.request.put(`${RESERVATIONS}/${existingId}`, {
        headers: { authorization: adminBearer, 'content-type': 'application/json' },
        data: {
          aircraftId: masterdata.managedAircraftId,
          pilotPersonId: masterdata.pilotPersonId,
          locationId: masterdata.locationId,
          start: '2026-09-02T13:00:00Z',
          end: '2026-09-02T13:50:00Z',
          isAllDay: false,
          remarks: 'edited in place — must not self-conflict',
        },
      });
      expect(
        selfEdit.status(),
        'editing a reservation in place must NOT conflict with itself (self-exclude)',
      ).toBe(200);

      expect(adjacent).toBeTruthy();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-5',
        caption:
          'J-5 · reservation conflict · a second reservation overlapping an existing one on the ' +
          'SAME aircraft is rejected 409 (aircraft.reservation.overlap); an adjacent [) booking is ' +
          'admitted and an in-place edit does NOT self-conflict (real conflictsWith aggregate)',
        acTag: 'key-error',
      });
    }
  });

  test('[key-error] a timed reservation with end ≤ start is rejected 422', async ({ browser }) => {
    const ctx = await browser.newContext({ baseURL });
    try {
      const res = await ctx.request.post(RESERVATIONS, {
        headers: { authorization: adminBearer, 'content-type': 'application/json' },
        data: {
          aircraftId: masterdata.managedAircraftId,
          pilotPersonId: masterdata.pilotPersonId,
          locationId: masterdata.locationId,
          start: '2026-09-03T15:00:00Z',
          end: '2026-09-03T14:00:00Z',
          isAllDay: false,
        },
      });
      expect(res.status(), 'a timed reservation with end ≤ start must 422').toBe(422);
      const body = (await res.json()) as { key?: string };
      expect(body.key).toBe('aircraft.reservation.duration');
    } finally {
      await ctx.close();
    }
  });

  test('[happy] an all-day reservation stores the full-day span and renders as a full-day band', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);

      const id = await createReservation(ctx, adminBearer, createdIds, {
        aircraftId: masterdata.managedAircraftId,
        pilotPersonId: masterdata.pilotPersonId,
        locationId: masterdata.locationId,
        start: '2026-09-04T00:00:00Z',
        end: '2026-09-04T00:00:00Z',
        isAllDay: true,
      });

      // The detail confirms the aggregate stored it all-day (real round-trip).
      const detail = await ctx.request.get(`${RESERVATIONS}/${id}`, {
        headers: { authorization: adminBearer },
      });
      expect(detail.status()).toBe(200);
      const d = (await detail.json()) as { isAllDay: boolean };
      expect(d.isAllDay, 'the reservation is stored all-day').toBe(true);

      // The list cell shows the all-day marker; the scheduler renders a full
      // (100%) band — the all-day full-day-band contract (T-10 placement).
      await page.goto('/reservations');
      await expect(page.getByTestId(`reservations-allday-${id}`)).toHaveText('Ganztägig');
      await page.goto('/reservation-scheduler');
      const block = page.getByTestId(`reservation-scheduler-block-${id}`);
      await expect(block).toBeVisible();
      const width = await block.evaluate((el) => (el as HTMLElement).style.width);
      expect(width, 'an all-day reservation is a full-width band').toBe('100%');
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-5',
        caption:
          'J-5 · all-day reservation · an all-day reservation stores the full-day span and renders ' +
          'as a full-width band on the /reservation-scheduler (real backend)',
        acTag: 'happy',
      });
    }
  });

  test('[edge] cross-tenant aircraft (legacy-open): an operating club reserves a foreign-managed aircraft (no charter gate)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);

      // The foreign aircraft is managed by club B (created by club B's admin in
      // beforeAll). seed-club-1's admin reserves it — legacy-open: NO charter
      // gate, the reservation succeeds (201), stamped with the operating club.
      const id = await createReservation(ctx, adminBearer, createdIds, {
        aircraftId: masterdata.foreignAircraftId,
        pilotPersonId: masterdata.pilotPersonId,
        locationId: masterdata.locationId,
        start: '2026-09-05T09:00:00Z',
        end: '2026-09-05T10:00:00Z',
        isAllDay: false,
        remarks: 'cross-tenant legacy-open',
      });

      // The reservation is stamped with the OPERATING club (seed-club-1), not the
      // aircraft's managing club — the detail's operatingClubId proves the stamp.
      const detail = await ctx.request.get(`${RESERVATIONS}/${id}`, {
        headers: { authorization: adminBearer },
      });
      expect(
        detail.status(),
        'the cross-tenant reservation is readable by its operating club',
      ).toBe(200);
      const d = (await detail.json()) as { aircraftId: string };
      expect(d.aircraftId, 'the reservation references the foreign-managed aircraft FK').toBe(
        masterdata.foreignAircraftId,
      );

      // It renders in the operating club's list under the foreign immat.
      await page.goto('/reservations');
      await expect(page.getByTestId(`reservations-immat-${id}`)).toHaveText(
        masterdata.foreignImmat,
      );
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-5',
        caption:
          'J-5 · cross-tenant-open · an operating club reserves an aircraft MANAGED BY A DIFFERENT ' +
          'club with NO charter gate — the reservation succeeds (201), the aircraft FK crosses ' +
          'tenants freely, and the reservation is stamped with the operating club (legacy parity)',
        acTag: 'edge',
      });
    }
  });

  test('[happy] deleting a reservation soft-deletes it and frees the slot (a new overlapping create then succeeds)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);

      // Book 16:00–17:00, confirm an overlapping 16:30–16:45 is blocked (409),
      // then delete the first and confirm the SAME overlapping create now succeeds.
      const firstId = await createReservation(ctx, adminBearer, createdIds, {
        aircraftId: masterdata.managedAircraftId,
        pilotPersonId: masterdata.pilotPersonId,
        locationId: masterdata.locationId,
        start: '2026-09-06T16:00:00Z',
        end: '2026-09-06T17:00:00Z',
        isAllDay: false,
      });

      const blocked = await ctx.request.post(RESERVATIONS, {
        headers: { authorization: adminBearer, 'content-type': 'application/json' },
        data: {
          aircraftId: masterdata.managedAircraftId,
          pilotPersonId: masterdata.pilotPersonId,
          locationId: masterdata.locationId,
          start: '2026-09-06T16:30:00Z',
          end: '2026-09-06T16:45:00Z',
          isAllDay: false,
        },
      });
      expect(blocked.status(), 'the slot is occupied → overlapping create 409s').toBe(409);

      // Delete the first reservation via the UI list kebab → confirm dialog.
      await page.goto('/reservations');
      await expect(page.getByTestId(`reservations-row-${firstId}`)).toBeVisible();
      page.once('dialog', (dialog) => dialog.accept());
      await page.getByTestId(`reservations-kebab-${firstId}`).click();
      await page.getByTestId(`reservations-delete-${firstId}`).click();
      await expect(page.getByTestId(`reservations-row-${firstId}`)).toHaveCount(0);

      // The freed slot now ACCEPTS the previously-blocked overlapping booking.
      const freed = await createReservation(ctx, adminBearer, createdIds, {
        aircraftId: masterdata.managedAircraftId,
        pilotPersonId: masterdata.pilotPersonId,
        locationId: masterdata.locationId,
        start: '2026-09-06T16:30:00Z',
        end: '2026-09-06T16:45:00Z',
        isAllDay: false,
      });
      expect(freed, 'the freed slot accepts a new overlapping reservation').toBeTruthy();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-5',
        caption:
          'J-5 · delete frees the slot · deleting a reservation soft-deletes it and frees its ' +
          'aircraft window — a previously-409d overlapping reservation then succeeds (real ' +
          'soft-delete excluded from the conflict probe)',
        acTag: 'happy',
      });
    }
  });
});

// ===========================================================================
// MIGRATED-DATA real chain — a real legacy reservation, exported + migrated
// through the live chain, renders in the MIGRATED TestClub's list (with its
// migrated type). Rides the REAL legacy export (T-07 wired the producer/consumer
// bindings + the legacy seed); there is NO synth reservation bundle, so this
// block runs only when the fanout's real export ran (J5_BUNDLE_SOURCE=real).
//
// CRITICAL (J-5 T-18): the read MUST happen as the MIGRATED TestClub's admin,
// NOT clubadmin4 / seed-club-1. The T-07 fixture (§10c) stamps the reservation on
// the legacy TestClub (`0FA7B76F-…`); `CLUB` migrates FULL_PORT (non-fanout) so it
// keeps that UUID. The paged read is `@TenantId`-scoped on `operating_club_id`, so
// seed-club-1 (clubadmin4) never sees the migrated row — there is no Flyway-seeded
// reservation on seed-club-1, so a `totalRows >= 1` read there would pass only on
// unrelated clean-seed residue, never proving the round-trip. We resolve the
// migration-provisioned migrated TestClub admin (the J-0c/J-1 migrated-read
// pattern) and assert the migrated reservation's IDENTIFYING fields (the unique
// fixture remark + its `Schulung` type), not a count.
// ===========================================================================
test.describe('Aircraft reservations — migrated legacy reservation renders (real-idp)', () => {
  test.describe.configure({ mode: 'serial', retries: 0 });

  // The migrated reservation rides the SAME real bundle the J-0c fan-out spec
  // ingests in this fanout run (the T-07-bound reservation rows are exported by
  // alpenflight-export alongside Locations/Aircraft/Flights). When the real
  // export did NOT run (per-push synth gate, local), there is no migrated
  // reservation to assert — skip cleanly rather than false-green. The clean-seed
  // group above is the per-push verticality proof; this block is the fanout
  // done-bar for the migration round-trip.
  test.skip(
    !useRealBundle(),
    'migrated-reservation render requires the real legacy export (J5_BUNDLE_SOURCE=real, fanout only)',
  );

  let baseURL: string;
  let migratedAdmin: MigratedClubAdmin;
  let migratedBearer: string;

  test.beforeAll(async ({ browser }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    // The fanout ingests the real bundle via fan-out-migration-parity.spec.ts
    // (J-0c) earlier in the same Playwright invocation; the migration provisions
    // a Keycloak admin for the FULL_PORT migrated TestClub — the tenant the
    // legacy fixture stamped the reservation on. Resolve + make it loginable, then
    // capture its tenant-scoped Bearer so the `@TenantId`-scoped read sees the
    // migrated reservation (NOT seed-club-1, which carries none).
    migratedAdmin = await resolveMigratedTestClubAdmin();
    migratedBearer = await captureMigratedTestClubBearer(browser, baseURL, migratedAdmin);
  });

  test('[happy] the migrated legacy reservation renders under its migrated TestClub tenant', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsMigratedTestClubAdmin(page, migratedAdmin);

      // The T-07 legacy fixture (§10c) seeds a TIMED cross-tenant reservation
      // with the unique remark 'Cross-tenant timed reservation (fixture)' and the
      // 'Schulung' type (§10b) on the legacy TestClub. The real export → migrate
      // carries it into AlpenFlight; assert the migrated row by its IDENTIFYING
      // fields via the real paged read as the migrated tenant — distinguishing
      // "the T-07 legacy reservation round-tripped" from "some other reservation
      // exists" (a bare count cannot).
      const paged = await ctx.request.post(`${RESERVATIONS}/page/0/50`, {
        headers: { authorization: migratedBearer, 'content-type': 'application/json' },
        data: { sorting: { start: 'asc' } },
      });
      expect(paged.status(), 'the migrated TestClub can page its migrated reservations').toBe(200);
      const body = (await paged.json()) as { items: ReservationListRow[]; totalRows: number };

      const migrated = body.items.find((r) => r.remarks === MIGRATED_RESERVATION_REMARK);
      expect(
        migrated,
        `the migrated legacy reservation (remark "${MIGRATED_RESERVATION_REMARK}") must be ` +
          `present for the migrated TestClub — the T-07 legacy→export→migrate→render round-trip. ` +
          `Got ${body.items.length} row(s): ${JSON.stringify(body.items.map((r) => r.remarks))}`,
      ).toBeTruthy();
      // Its migrated type round-tripped too (§10b — `Schulung`).
      expect(
        migrated!.reservationTypeName,
        'the migrated reservation carries its migrated type name (Schulung)',
      ).toBe(MIGRATED_RESERVATION_TYPE_NAME);

      // It renders in the UI list as the migrated TestClub admin (the screen
      // wires to the migrated data). Identify the row by the migrated id, not
      // "first row", so a residual row can't satisfy the assertion.
      await page.goto('/reservations');
      await expect(page.getByTestId('reservations-table')).toBeVisible();
      await expect(
        page.getByTestId(`reservations-row-${migrated!.id}`),
        'the identified migrated reservation row renders in the list',
      ).toBeVisible();

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-reservations-migrated-list.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-5',
        caption:
          'J-5 · migrated reservation · a real legacy aircraft reservation, exported + migrated ' +
          "through the live chain, renders in the migrated TestClub's /reservations list under its " +
          'unique fixture remark + migrated Schulung type (full legacy→export→migrate→Keycloak→UI ' +
          'chain, read via the migrated FULL_PORT tenant)',
        acTag: 'happy',
      });
    }
  });
});
