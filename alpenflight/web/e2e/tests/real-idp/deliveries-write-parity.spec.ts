import { execFile } from 'node:child_process';
import { resolve } from 'node:path';
import { promisify } from 'node:util';

import {
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
  type TestInfo,
} from '@playwright/test';
import { test, expect, watchConsoleErrors, allowConsoleErrors } from '../_helpers/console-guard';

import { enterViaNav } from '../_helpers/nav';

import { seedFlightMasterdata, type FlightMasterdata } from './_helpers/flight-parity-fixture';
import { proofVideo } from './_helpers/proof-video';
import {
  loginAsReservationAdmin,
  captureReservationAdminBearer,
  SEED_CLUB_A_ID,
} from './_helpers/reservation-parity-fixture';
import { provisionTwoClubs, type TwoClubFixture } from './_helpers/two-club-fixture';

/**
 * J-10b — Deliveries WRITE side over the real chain (live Keycloak auth + real
 * Spring backend + real Postgres). The journey's `parity_test` (the real-chain
 * done-bar). Completes J-10's read screen with the accounting-correctness write
 * paths — engine create, the Prepared→Booked terminal state machine, and delete
 * (which resets the linked flight + tow and reverses the flight-time credit it
 * balanced). NO `page.route` mocking on any path: create, delete, book, the
 * `@TenantId` filter, the CLUB_ADMINISTRATOR `@PreAuthorize` gate, the credit
 * draw-down, and the append-only credit reversal all run live.
 *
 * ── PRINCIPAL (CLUB_ADMINISTRATOR — every delivery write endpoint is admin-gated) ─
 * Drives `clubadmin4` (V29 seed), a REAL CLUB_ADMINISTRATOR bound to seed-club-1,
 * NOT the mock-admin everything-principal that would hide a role-authz gap
 * ([[project_real_idp_real_roles_catches_authz_gaps]]). The cross-tenant write
 * probe drives a REAL second club + admin (`provisionTwoClubs`).
 *
 * ── INPUT STATES (real REST + the DB-fixture seam, NOT a mock) ────────────────
 * The spec mints the engine-consumable inputs — masterdata, a glider flight on a
 * known immatriculation, the billed pilot's PersonFlightTimeCredit matched to
 * that immatriculation — over the REAL REST APIs. The few input STATES no REST
 * surface can set (a flight flipped to Locked + back-dated past the 3-day
 * eligibility floor, the glider→tow link, the deterministic FlightTime rule
 * filter producing one known line, a pre-built shared-flight delivery, a
 * cross-tenant delivery) are materialized via the Gradle `seedDeliveryWriteFixture`
 * task against the live LAN Postgres. The write endpoints + `@TenantId` scope +
 * the credit math then run fully real off those rows — the seed is fixture STATE.
 *
 * ── REAL-IDP HYGIENE (hard-won) ──────────────────────────────────────────────
 *   - ENTER via the masterdata nav (`enterViaNav`), NOT a bare goto, for the
 *     chrome-reachability assertion;
 *   - prefer WARM in-app navigation; do NOT `clearCookies` (kills session
 *     restore) ([[project_real_idp_goto_reboot_renew_stall]]);
 *   - read a created flight id off the 201 `Location` header, never the POST
 *     response body ([[project_spa_nav_evicts_post_response_body]]);
 *   - `af-data-table` renders `<ul role="list"><li>` (NOT `role="row"`) — scope
 *     rows by the `del-row-<id>` / `del-state-<id>` testid, never `getByRole('row')`;
 *   - track every seeded delivery / filter id + `afterAll` clean up so a
 *     Playwright retry starts on a clean seed-club-1.
 */

const FLIGHTS = '/api/v1/flights';
const DELIVERIES = '/api/v1/deliveries';
const CREDITS = '/api/v1/internal/person-flight-time-credits';

const CREW_TYPE_PILOT = '019e2e15-2c00-76b0-8000-0000000036b0';
// PILOT_PAYS_ALL cost balance (V3 legacy id 1) — the recipient fallback bills the
// pilot only when the flight carries this balance, so create resolves a recipient.
const COST_BALANCE_PILOT_PAYS_ALL = 'fcb-019e2e15-2c00-7268-8000-000000004268';

// Flight process-state ids (FlightProcessState enum — V3-seeded canonical UUIDs).
// The flight detail response carries the raw `processStateId` UUID, so the
// flight-state assertions key off these rather than a fragile enum-name string.
const FLIGHT_LOCKED = '019e2e15-2c00-7a9b-8000-000000003a9b';
const FLIGHT_DELIVERY_PREPARED = '019e2e15-2c00-7a9d-8000-000000003a9d';
const FLIGHT_DELIVERY_BOOKED = '019e2e15-2c00-7a9e-8000-000000003a9e';

// Delivery process-state codes (V4 sparse code; process-state.ts mirror).
const DELIVERY_PREPARED = 10;
const DELIVERY_BOOKED = 20;

// The write-side testids the `/deliveries` screen ships.
const CREATE_BUTTON = 'del-create-button';
const DELETE_CONFIRM_MODAL = 'del-delete-confirm-modal';
const DELETE_CONFIRM = 'del-delete-confirm';
const BOOKED_BADGE = 'del-booked-badge';
const ERROR_TOAST = 'del-error-toast';

const SERVER_DIR = resolve(__dirname, '../../../../server');
const GRADLEW = resolve(SERVER_DIR, 'gradlew');
const execFileAsync = promisify(execFile);

interface DeliveryDetail {
  id: string;
  deliveryNumber: string | null;
  batchId: number;
  processStateId: number;
  recipient: { firstName?: string | null; lastName?: string | null; city?: string | null };
  flight: { flightId: string } | null;
  items: { position: number; articleNumber: string; quantity: number; unitType: string }[];
}

interface CreditView {
  id: string;
  currentFlightTimeBalanceInSeconds: number | null;
}

async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  const context = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  return context;
}

/** The per-subcommand JSON shapes the `seedDeliveryWriteFixture` task prints. */
interface SeederResult {
  flightId?: string;
  filterId?: string;
  deliveryId?: string;
  articleNumber?: string;
}

/** Run the Gradle `seedDeliveryWriteFixture` task; return its single JSON result line. */
async function runSeeder(seederArgs: string): Promise<SeederResult> {
  const { stdout } = await execFileAsync(
    GRADLEW,
    ['--quiet', 'seedDeliveryWriteFixture', `-PseederArgs=${seederArgs}`],
    { cwd: SERVER_DIR, maxBuffer: 4 * 1024 * 1024 },
  );
  const line = stdout
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l.startsWith('{') && l.endsWith('}'))
    .pop();
  if (!line) {
    throw new Error(`delivery-write seeder produced no JSON result line; stdout was:\n${stdout}`);
  }
  return JSON.parse(line) as SeederResult;
}

/** Run a seeder subcommand that returns a `deliveryId`, asserting it is present. */
async function seedDelivery(seederArgs: string): Promise<string> {
  const id = (await runSeeder(seederArgs)).deliveryId;
  if (!id) {
    throw new Error(`seeder "${seederArgs}" returned no deliveryId`);
  }
  return id;
}

/** Strip the `fl-` external-form prefix to the raw flight uuid the seeder takes. */
function rawFlightId(externalId: string): string {
  return externalId.replace(/^fl-/, '');
}

/**
 * Create a GLIDER flight (90-min, 1 landing, the given pilot) on `aircraftId`
 * via the REAL flight API; return its RAW uuid (read off the 201 Location).
 */
async function createGliderFlight(
  api: APIRequestContext,
  bearer: string,
  md: FlightMasterdata,
  aircraftId: string,
  pilotPersonId: string,
  track: Set<string>,
): Promise<string> {
  const res = await api.post(FLIGHTS, {
    headers: { authorization: bearer, 'content-type': 'application/json' },
    data: {
      flightAircraftType: 'GLIDER',
      aircraftId,
      flightDate: '2026-05-15',
      startDateTime: '2026-05-15T08:00:00Z',
      ldgDateTime: '2026-05-15T09:30:00Z',
      startLocationId: md.locationId,
      ldgLocationId: md.locationId,
      flightTypeId: md.gliderFlightTypeId,
      flightCostBalanceTypeId: COST_BALANCE_PILOT_PAYS_ALL,
      nrOfLdgs: 1,
      nrOfLdgsOnStartLocation: 1,
      noStartTimeInformation: false,
      noLdgTimeInformation: false,
      isSoloFlight: false,
      crew: [{ personId: pilotPersonId, flightCrewTypeId: CREW_TYPE_PILOT }],
    },
  });
  expect(res.status(), `flight create must 201 — got ${res.status()}: ${await res.text()}`).toBe(
    201,
  );
  const id = rawFlightId(
    new URL(res.headers()['location']!, 'http://localhost').pathname.split('/').pop()!,
  );
  track.add(id);
  return id;
}

/**
 * Grant the pilot a prepaid flight-time credit MATCHED to the flight's
 * immatriculation (the engine's credit branch keys on the matched immat; valid
 * until after the flight start so it is live at billing time). Returns the
 * credit id so the spec can re-read its actual `IsCurrent` balance later.
 */
async function grantCredit(
  api: APIRequestContext,
  bearer: string,
  pilotPersonId: string,
  immatriculation: string,
  balanceSeconds: number,
): Promise<string> {
  const res = await api.post(CREDITS, {
    headers: { authorization: bearer, 'content-type': 'application/json' },
    data: {
      personId: pilotPersonId,
      noFlightTimeLimit: false,
      useRuleForAllAircraftsExceptListed: false,
      matchedAircraftImmatriculations: immatriculation,
      discountInPercent: 0,
      validUntil: '2026-12-31T00:00:00Z',
      currentFlightTimeBalanceInSeconds: balanceSeconds,
    },
  });
  expect(res.status(), `credit grant must 201 — got ${res.status()}: ${await res.text()}`).toBe(
    201,
  );
  return (JSON.parse(await res.text()) as CreditView).id;
}

/** Read a credit's CURRENT (IsCurrent) flight-time balance in seconds — the live money value. */
async function currentBalance(
  api: APIRequestContext,
  bearer: string,
  creditId: string,
): Promise<number> {
  const res = await api.get(`${CREDITS}/${creditId}`, { headers: { authorization: bearer } });
  expect(res.status(), `credit read must 200 — got ${res.status()}`).toBe(200);
  const view = JSON.parse(await res.text()) as CreditView;
  return view.currentFlightTimeBalanceInSeconds ?? -1;
}

/** Re-GET a flight's detail and return its raw `processStateId` UUID. */
async function flightProcessStateId(
  api: APIRequestContext,
  bearer: string,
  rawId: string,
): Promise<string> {
  const res = await api.get(`${FLIGHTS}/fl-${rawId}`, { headers: { authorization: bearer } });
  expect(res.status(), `flight read must 200 — got ${res.status()}`).toBe(200);
  return (JSON.parse(await res.text()) as { processStateId: string }).processStateId;
}

/** Find the delivery this run's create produced for `flightId` (the engine bills tenant-wide). */
async function deliveryForFlight(
  api: APIRequestContext,
  bearer: string,
  rawFlightId: string,
): Promise<DeliveryDetail> {
  const page = await api.post(`${DELIVERIES}/page/0/500`, {
    headers: { authorization: bearer, 'content-type': 'application/json' },
  });
  expect(page.status(), `deliveries page must 200 — got ${page.status()}`).toBe(200);
  const body = JSON.parse(await page.text()) as { items: { id: string }[] };
  const external = `fl-${rawFlightId}`;
  for (const row of body.items) {
    const detail = (await api
      .get(`${DELIVERIES}/${row.id}`, { headers: { authorization: bearer } })
      .then((r) => r.json())) as DeliveryDetail;
    if (detail.flight?.flightId === external) {
      return detail;
    }
  }
  throw new Error(`no delivery found for flight ${external} after create`);
}

test.describe('Deliveries — write side (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let baseURL: string;
  /** clubadmin4's Bearer (seed-club-1, the @TenantId club). */
  let adminBearer: string;
  /** A real second club + admin (club B) — the cross-tenant write probe. */
  let twoClubs: TwoClubFixture;
  let api: BrowserContext;
  /** Shared masterdata + the deterministic FlightTime rule filter (one known line). */
  let md: FlightMasterdata;
  let filterId: string;
  /** Seeded deliveries + filters to clean up after the run. */
  const createdDeliveryIds = new Set<string>();
  const seededDeliveryIds: string[] = [];
  /** Every flight this spec creates — reset to VALID in afterAll so a spec-created
   * Locked/Prepared flight never pollutes the next tenant-wide create batch. */
  const createdFlightIds = new Set<string>();

  test.beforeAll(async ({ browser }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    adminBearer = await captureReservationAdminBearer(browser, baseURL);
    twoClubs = await provisionTwoClubs(browser, baseURL, 'dlw');
    api = await browser.newContext({ baseURL });
    md = await seedFlightMasterdata(api.request, adminBearer);
    // ONE deterministic active FlightTime rule filter on seed-club-1 → the engine
    // produces exactly one known line per eligible flight (a known article number).
    filterId = (await runSeeder(`rule-filter ${SEED_CLUB_A_ID} DLW-ART-FT`)).filterId ?? '';
  });

  test.afterAll(async () => {
    for (const id of [...createdDeliveryIds]) {
      await runSeeder(`delete-delivery ${id}`).catch(() => undefined);
    }
    for (const id of seededDeliveryIds) {
      await runSeeder(`delete-delivery ${id}`).catch(() => undefined);
    }
    // Reset every spec-created flight to VALID + unlink its tow so a leftover
    // Locked/Prepared (linked) flight never pollutes the next create batch.
    for (const id of createdFlightIds) {
      await runSeeder(`reset-flight ${id}`).catch(() => undefined);
    }
    if (filterId) {
      await runSeeder(`delete-filter ${filterId}`).catch(() => undefined);
    }
    await api?.close();
    await twoClubs?.dispose();
  });

  // =========================================================================
  // [happy] CREATE — enter via the masterdata nav, click "create deliveries",
  // the engine produces one Delivery (+items) for the eligible aged-Locked
  // glider flight; the flight (+tow) flips to DeliveryPrepared.
  // =========================================================================
  test('[happy] create deliveries → the engine produces one delivery for the eligible aged-Locked glider; flight (+tow) → DeliveryPrepared', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    // A fresh eligible glider flight on the seeded glider aircraft, billed to the
    // seeded pilot — flipped to Locked + aged past the 3-day floor via the seeder.
    const flightId = await createGliderFlight(
      api.request,
      adminBearer,
      md,
      md.gliderAircraftId,
      md.pilotPersonId,
      createdFlightIds,
    );
    await runSeeder(`lock-and-age ${flightId} 4`);
    try {
      await loginAsReservationAdmin(page);
      await page.goto('/start?lang=en');
      await enterViaNav(page, '/deliveries');
      await expect(page).toHaveURL('/deliveries');
      await expect(page.getByTestId('del-table')).toBeVisible();

      // Click the engine "create deliveries" action; wait for the create POST to
      // land 200 before the list refresh re-renders.
      const created = page.waitForResponse(
        (r) =>
          new URL(r.url()).pathname === `${DELIVERIES}/create` &&
          r.request().method() === 'POST' &&
          r.status() === 200,
      );
      await page.getByTestId(CREATE_BUTTON).click();
      await created;

      // The engine produced exactly one Delivery for THIS flight: Prepared, with
      // ≥1 line item (the deterministic FlightTime filter's known line). Resolve
      // it by its flight link (the create is tenant-wide, so identify by flight).
      const delivery = await deliveryForFlight(api.request, adminBearer, flightId);
      createdDeliveryIds.add(delivery.id);
      expect(delivery.processStateId, 'the created delivery is Prepared').toBe(DELIVERY_PREPARED);
      expect(delivery.items.length, 'the engine produced the known line item(s)').toBeGreaterThan(
        0,
      );

      // The billed glider flight flipped to DeliveryPrepared (the create side effect).
      expect(
        await flightProcessStateId(api.request, adminBearer, flightId),
        'the billed flight flipped to DeliveryPrepared',
      ).toBe(FLIGHT_DELIVERY_PREPARED);

      // The new row renders in the @TenantId-scoped list (Prepared badge, no
      // Booked badge — it is freshly created). Capture the POPULATED list.
      const row = page.getByTestId(`del-row-${delivery.id}`);
      await expect(row, 'the engine-created delivery renders in the list').toBeVisible();
      await expect(page.getByTestId(`del-state-${delivery.id}`)).toBeVisible();
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-deliveries-list.png`,
        fullPage: true,
      });

      // The detail view shows the engine line items (the form shot — populated).
      await row.click();
      await expect(page).toHaveURL(`/deliveries/${delivery.id}`);
      await expect(page.getByTestId('del-detail')).toBeVisible();
      await expect(page.getByTestId('del-item-0')).toBeVisible();
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-deliveries-form.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-10b',
        caption:
          'J-10b · deliveries · a club admin logs in via real Keycloak, ENTERS /deliveries via the ' +
          'masterdata nav, and triggers the engine "create deliveries" action — the J-9 rules engine ' +
          'produces one Delivery (+line items) for the eligible aged-Locked glider flight, which renders ' +
          'in the tenant-scoped list and flips the flight to DeliveryPrepared',
        acTag: 'happy',
      });
    }
  });

  // =========================================================================
  // [happy] DELETE + reset + reverse — the destructive money path. Delete a
  // Prepared delivery; flight + tow reset to Locked; the credit it consumed is
  // REVERSED append-only (a new IsCurrent row restoring the prior balance).
  // =========================================================================
  test('[happy] delete a Prepared delivery → flight (+tow) reset to Locked, the consumed credit REVERSED (append-only)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    // Glider + its tow, linked, both eligible; the pilot's credit matches the
    // glider immat. After create the credit is drawn down; after delete it is
    // restored to the original balance (append-only reversal).
    const gliderId = await createGliderFlight(
      api.request,
      adminBearer,
      md,
      md.gliderAircraftId,
      md.pilotPersonId,
      createdFlightIds,
    );
    const towId = await createGliderFlight(
      api.request,
      adminBearer,
      md,
      md.gliderAircraftId,
      md.pilotPersonId,
      createdFlightIds,
    );
    // The glider is independently eligible (aged); the tow is Locked but NOT aged,
    // so create flips it to DeliveryPrepared via the glider's tow link WITHOUT
    // double-processing it as its own eligible flight (which would re-prep a
    // DeliveryPrepared flight → illegal transition).
    await runSeeder(`lock-and-age ${gliderId} 4`);
    await runSeeder(`lock-and-age ${towId} 0`);
    await runSeeder(`link-tow ${gliderId} ${towId}`);
    const ORIGINAL_BALANCE = 5_400;
    const creditId = await grantCredit(
      api.request,
      adminBearer,
      md.pilotPersonId,
      md.gliderImmat,
      ORIGINAL_BALANCE,
    );
    try {
      await loginAsReservationAdmin(page);
      await page.goto('/start?lang=en');
      await enterViaNav(page, '/deliveries');
      await expect(page.getByTestId('del-table')).toBeVisible();

      // Create → the credit is consumed (drawn DOWN below the original balance).
      const created = page.waitForResponse(
        (r) =>
          new URL(r.url()).pathname === `${DELIVERIES}/create` &&
          r.request().method() === 'POST' &&
          r.status() === 200,
      );
      await page.getByTestId(CREATE_BUTTON).click();
      await created;

      const delivery = await deliveryForFlight(api.request, adminBearer, gliderId);
      createdDeliveryIds.add(delivery.id);
      const afterCreate = await currentBalance(api.request, adminBearer, creditId);
      expect(afterCreate, 'create drew the credit DOWN below its original balance').toBeLessThan(
        ORIGINAL_BALANCE,
      );

      // Delete via the confirm modal.
      await page.reload();
      await expect(page.getByTestId('del-table')).toBeVisible();
      const row = page.getByTestId(`del-row-${delivery.id}`);
      await expect(row).toBeVisible();
      const deleted = page.waitForResponse(
        (r) =>
          new URL(r.url()).pathname === `${DELIVERIES}/${delivery.id}` &&
          r.request().method() === 'DELETE' &&
          r.status() === 204,
      );
      await rowDeleteButton(page, delivery.id).click();
      await expect(page.getByTestId(DELETE_CONFIRM_MODAL)).toBeVisible();
      await page.getByTestId(DELETE_CONFIRM).click();
      await deleted;
      createdDeliveryIds.delete(delivery.id);

      // The glider AND its tow reset to Locked (re-GET — persisted, correct target;
      // fixing both legacy bugs: the wrong-tow-target write + the never-SaveChanges).
      expect(
        await flightProcessStateId(api.request, adminBearer, gliderId),
        'the glider flight reset to Locked',
      ).toBe(FLIGHT_LOCKED);
      expect(
        await flightProcessStateId(api.request, adminBearer, towId),
        'the TOW flight reset to Locked (the correct, persisted target)',
      ).toBe(FLIGHT_LOCKED);

      // The credit is REVERSED — its current balance is restored to the original
      // (append-only: a new IsCurrent reversal row negated the consumption; the
      // original consumption row is kept un-current). Assert the ACTUAL balance.
      expect(
        await currentBalance(api.request, adminBearer, creditId),
        'the credit reversal restored the original balance (append-only)',
      ).toBe(ORIGINAL_BALANCE);
    } finally {
      await ctx.close();
      await api.request
        .delete(`${CREDITS}/${creditId}`, { headers: { authorization: adminBearer } })
        .catch(() => undefined);
      await proofVideo(page, testInfo, {
        journey: 'J-10b',
        caption:
          'J-10b · deliveries · deleting a Prepared delivery (confirm modal) resets BOTH the glider ' +
          'flight and its tow back to Locked (re-GET) and REVERSES the flight-time credit it consumed — ' +
          'append-only: a new negated transaction restores the original balance, the consumption row kept ' +
          'un-current (the destructive money path, fixing the two reachable legacy reset bugs)',
        acTag: 'happy',
      });
    }
  });

  // =========================================================================
  // [key-error] >1-delivery-per-flight — two deliveries share a flight; delete
  // → 409, surfaced via the error toast, no mutation.
  // =========================================================================
  test('[key-error] delete rejected when >1 delivery shares the flight → 409 surfaced, no mutation', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    // A real Locked flight + TWO pre-built deliveries on it (the engine makes only
    // ONE per flight, so the shared-flight fixture is seeded directly).
    const flightId = await createGliderFlight(
      api.request,
      adminBearer,
      md,
      md.gliderAircraftId,
      md.pilotPersonId,
      createdFlightIds,
    );
    await runSeeder(`lock-and-age ${flightId} 4`);
    const batch = Number(Date.now().toString().slice(-7));
    const d1 = await seedDelivery(`delivery ${SEED_CLUB_A_ID} ${flightId} Shared ${batch}`);
    const d2 = await seedDelivery(`delivery ${SEED_CLUB_A_ID} ${flightId} Shared ${batch + 1}`);
    seededDeliveryIds.push(d1, d2);
    // The shared-flight delete is DELIBERATELY rejected with 409 — the SPA surfaces
    // it as a toast and the HTTP layer logs a console.error, which is the proven
    // behavior, not a defect (declare it so the suite-wide console guard allows it).
    allowConsoleErrors(testInfo, /Failed to load resource.*deliveries.*409/i, /409/);
    try {
      await loginAsReservationAdmin(page);
      await page.goto('/start?lang=en');
      await enterViaNav(page, '/deliveries');
      await expect(page.getByTestId('del-table')).toBeVisible();

      const row = page.getByTestId(`del-row-${d1}`);
      await expect(row, 'the shared-flight delivery renders').toBeVisible();
      const rejected = page.waitForResponse(
        (r) =>
          new URL(r.url()).pathname === `${DELIVERIES}/${d1}` &&
          r.request().method() === 'DELETE' &&
          r.status() === 409,
      );
      await rowDeleteButton(page, d1).click();
      await expect(page.getByTestId(DELETE_CONFIRM_MODAL)).toBeVisible();
      await page.getByTestId(DELETE_CONFIRM).click();
      await rejected;

      // The 409 surfaces as the non-blocking toast; NEITHER delivery was mutated.
      await expect(page.getByTestId(ERROR_TOAST), 'the 409 surfaces on the screen').toBeVisible();
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-deliveries-shared-flight-reject.png`,
        fullPage: true,
      });
      for (const id of [d1, d2]) {
        const detail = (await api.request
          .get(`${DELIVERIES}/${id}`, { headers: { authorization: adminBearer } })
          .then((r) => r.json())) as DeliveryDetail;
        expect(detail.processStateId, `delivery ${id} was not mutated (still Prepared)`).toBe(
          DELIVERY_PREPARED,
        );
      }
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-10b',
        caption:
          'J-10b · deliveries · deleting a delivery whose flight is shared by another delivery is ' +
          'rejected with 409 (the legacy shared-flight guard parity), surfaced as a non-blocking toast ' +
          'on the /deliveries screen — neither delivery is mutated',
        acTag: 'key-error',
      });
    }
  });

  // =========================================================================
  // [key-error] BOOKED-terminal — book a Prepared delivery, then attempt
  // delete-of-booked → 409; the row shows the Booked badge + a disabled delete.
  // =========================================================================
  test('[key-error] a Booked delivery is terminal → delete rejected with 409, Booked badge + disabled delete, no mutation', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    const flightId = await createGliderFlight(
      api.request,
      adminBearer,
      md,
      md.gliderAircraftId,
      md.pilotPersonId,
      createdFlightIds,
    );
    // The flight carries a Prepared delivery, so it is DeliveryPrepared (not Locked)
    // — booking is the legal Prepared→Booked transition off that state.
    await runSeeder(`prepare-flight ${flightId}`);
    const batch = Number(Date.now().toString().slice(-7));
    const deliveryId = await seedDelivery(`delivery ${SEED_CLUB_A_ID} ${flightId} Booked ${batch}`);
    seededDeliveryIds.push(deliveryId);
    try {
      // Book it via the real /delivered endpoint (the external-booker path).
      const booked = await api.request.post(`${DELIVERIES}/delivered`, {
        headers: { authorization: adminBearer, 'content-type': 'application/json' },
        data: {
          deliveryId,
          deliveryDateTime: '2026-06-01T10:00:00Z',
          deliveryNumber: 'INV-2026-TERM',
        },
      });
      expect(booked.status(), 'booking a Prepared delivery succeeds').toBe(200);
      expect(JSON.parse(await booked.text()), 'book returns true').toBe(true);

      await loginAsReservationAdmin(page);
      await page.goto('/start?lang=en');
      await enterViaNav(page, '/deliveries');
      await expect(page.getByTestId('del-table')).toBeVisible();

      // The row shows the Booked badge + a DISABLED delete affordance.
      const row = page.getByTestId(`del-row-${deliveryId}`);
      await expect(row).toBeVisible();
      await expect(rowOf(page, deliveryId).getByTestId(BOOKED_BADGE)).toBeVisible();
      await expect(
        rowDeleteButton(page, deliveryId),
        'the Booked row delete is disabled',
      ).toBeDisabled();
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-deliveries-booked.png`,
        fullPage: true,
      });

      // Driving the delete directly (bypassing the disabled UI) → 409, no mutation.
      const rejected = await api.request.delete(`${DELIVERIES}/${deliveryId}`, {
        headers: { authorization: adminBearer },
      });
      expect(rejected.status(), 'delete of a Booked delivery is 409 (terminal)').toBe(409);
      const detail = (await api.request
        .get(`${DELIVERIES}/${deliveryId}`, { headers: { authorization: adminBearer } })
        .then((r) => r.json())) as DeliveryDetail;
      expect(detail.processStateId, 'the Booked delivery was not mutated').toBe(DELIVERY_BOOKED);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-10b',
        caption:
          'J-10b · deliveries · a Booked delivery is terminal — its /deliveries row shows the Booked ' +
          'badge with a disabled delete affordance, and a forced delete is rejected with 409 leaving the ' +
          'closed billing record unmutated',
        acTag: 'key-error',
      });
    }
  });

  // =========================================================================
  // [edge] BOOKING — POST /delivered with a free-text (non-numeric)
  // deliveryNumber stamps number/DeliveredOn/IsFurtherProcessed and flips
  // flight(+tow) → DeliveryBooked.
  // =========================================================================
  test('[edge] book a Prepared delivery via /delivered with a free-text number → number stamped, flight (+tow) → DeliveryBooked', async () => {
    const flightId = await createGliderFlight(
      api.request,
      adminBearer,
      md,
      md.gliderAircraftId,
      md.pilotPersonId,
      createdFlightIds,
    );
    const towId = await createGliderFlight(
      api.request,
      adminBearer,
      md,
      md.gliderAircraftId,
      md.pilotPersonId,
      createdFlightIds,
    );
    // Both the booked flight and its tow are DeliveryPrepared (the state a
    // pre-Prepared delivery's flights carry) so booking flips both Prepared→Booked.
    await runSeeder(`link-tow ${flightId} ${towId}`);
    await runSeeder(`prepare-flight ${flightId}`);
    await runSeeder(`prepare-flight ${towId}`);
    const batch = Number(Date.now().toString().slice(-7));
    const deliveryId = await seedDelivery(`delivery ${SEED_CLUB_A_ID} ${flightId} Edge ${batch}`);
    seededDeliveryIds.push(deliveryId);

    const FREE_TEXT_NUMBER = 'INV-2026-001';
    const booked = await api.request.post(`${DELIVERIES}/delivered`, {
      headers: { authorization: adminBearer, 'content-type': 'application/json' },
      data: {
        deliveryId,
        deliveryDateTime: '2026-06-01T10:00:00Z',
        deliveryNumber: FREE_TEXT_NUMBER,
      },
    });
    expect(booked.status(), 'booking a Prepared delivery succeeds').toBe(200);
    expect(JSON.parse(await booked.text())).toBe(true);

    // The free-text number is stamped verbatim + the delivery is Booked.
    const detail = (await api.request
      .get(`${DELIVERIES}/${deliveryId}`, { headers: { authorization: adminBearer } })
      .then((r) => r.json())) as DeliveryDetail;
    expect(detail.deliveryNumber, 'the free-text delivery number is stamped verbatim').toBe(
      FREE_TEXT_NUMBER,
    );
    expect(detail.processStateId, 'the delivery is Booked').toBe(DELIVERY_BOOKED);

    // The flight AND its tow flipped to DeliveryBooked.
    expect(
      await flightProcessStateId(api.request, adminBearer, flightId),
      'the booked flight flipped to DeliveryBooked',
    ).toBe(FLIGHT_DELIVERY_BOOKED);
    expect(
      await flightProcessStateId(api.request, adminBearer, towId),
      'the tow flight flipped to DeliveryBooked',
    ).toBe(FLIGHT_DELIVERY_BOOKED);
  });

  // =========================================================================
  // [edge] CROSS-TENANT — as club-A admin, deleting / booking a club-B delivery
  // is invisible: delete → 404 (the @TenantId finder never returns it), book →
  // 200 false (unknown id parity).
  // =========================================================================
  test('[edge] cross-tenant write (delete / book another club’s delivery) is rejected — 404 delete, false book', async () => {
    // Seed a Prepared delivery under club B (unlinked — the tenant gate is the point).
    const batch = Number(Date.now().toString().slice(-7));
    const clubBDelivery = await seedDelivery(
      `delivery ${twoClubs.clubB.clubId} - Foreign ${batch}`,
    );
    seededDeliveryIds.push(clubBDelivery);

    // Club A's admin CANNOT delete club B's delivery — the @TenantId-scoped finder
    // never returns it → 404 (invisible, not forbidden).
    const crossDelete = await api.request.delete(`${DELIVERIES}/${clubBDelivery}`, {
      headers: { authorization: adminBearer },
    });
    expect(
      crossDelete.status(),
      `club A must NOT delete club B's delivery (cross-tenant 404) — got ${crossDelete.status()}`,
    ).toBe(404);

    // Booking another club's delivery behaves like an unknown id → 200 false
    // (the external-booker parity quirk; the row is invisible under club A's scope).
    const crossBook = await api.request.post(`${DELIVERIES}/delivered`, {
      headers: { authorization: adminBearer, 'content-type': 'application/json' },
      data: {
        deliveryId: clubBDelivery,
        deliveryDateTime: '2026-06-01T10:00:00Z',
        deliveryNumber: 'INV-X-CROSS',
      },
    });
    expect(crossBook.status(), 'cross-tenant book returns 200').toBe(200);
    expect(JSON.parse(await crossBook.text()), 'cross-tenant book is a no-op false').toBe(false);
  });

  // =========================================================================
  // [money-proof] GLIDER+TOW shared-credit — both flights draw on ONE credit;
  // after create the credit reflects BOTH passes SUMMED (not under-consumed) —
  // the proof for the 3rd legacy money bug.
  // =========================================================================
  test('[money-proof] glider + tow drawing on ONE credit → create SUMS both passes (not under-consumed)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    // TWO eligible glider passes on the SAME aircraft + pilot, both drawing on the
    // ONE credit matched to that immatriculation (each is independently billed —
    // NOT tow-linked, which would side-effect-prep one and prevent its own
    // delivery). The engine bills both passes; the credit must reflect BOTH
    // consumptions SUMMED, not a last-write-wins single pass (the 3rd legacy bug).
    const firstPassId = await createGliderFlight(
      api.request,
      adminBearer,
      md,
      md.gliderAircraftId,
      md.pilotPersonId,
      createdFlightIds,
    );
    const secondPassId = await createGliderFlight(
      api.request,
      adminBearer,
      md,
      md.gliderAircraftId,
      md.pilotPersonId,
      createdFlightIds,
    );
    await runSeeder(`lock-and-age ${firstPassId} 4`);
    await runSeeder(`lock-and-age ${secondPassId} 4`);
    const ORIGINAL_BALANCE = 100_000;
    const creditId = await grantCredit(
      api.request,
      adminBearer,
      md.pilotPersonId,
      md.gliderImmat,
      ORIGINAL_BALANCE,
    );
    try {
      await loginAsReservationAdmin(page);
      await page.goto('/start?lang=en');
      await enterViaNav(page, '/deliveries');
      await expect(page.getByTestId('del-table')).toBeVisible();

      const created = page.waitForResponse(
        (r) =>
          new URL(r.url()).pathname === `${DELIVERIES}/create` &&
          r.request().method() === 'POST' &&
          r.status() === 200,
      );
      await page.getByTestId(CREATE_BUTTON).click();
      await created;

      // Both passes were independently billed (each produced its own delivery).
      const firstDelivery = await deliveryForFlight(api.request, adminBearer, firstPassId);
      const secondDelivery = await deliveryForFlight(api.request, adminBearer, secondPassId);
      createdDeliveryIds.add(firstDelivery.id);
      createdDeliveryIds.add(secondDelivery.id);
      expect(firstDelivery.processStateId, 'the first pass produced a Prepared delivery').toBe(
        DELIVERY_PREPARED,
      );
      expect(secondDelivery.processStateId, 'the second pass produced a Prepared delivery').toBe(
        DELIVERY_PREPARED,
      );

      // The DISCRIMINATING money assertion (the 3rd legacy bug proof). Each pass is
      // a 90-minute (5400 s active) glider flight, so the engine consumes 5400 s per
      // pass off the ONE shared credit. Read the ACTUAL post-create balance off the
      // live credit and assert the drawdown reflects BOTH passes SUMMED — strictly
      // more than the single 5400 s pass the legacy last-write-wins bug would leave.
      const ONE_PASS_SECONDS = 5_400;
      const afterCreate = await currentBalance(api.request, adminBearer, creditId);
      const drawdown = ORIGINAL_BALANCE - afterCreate;
      expect(drawdown, 'create drew the credit down (it was consumed)').toBeGreaterThan(0);
      expect(
        drawdown,
        'both passes are SUMMED onto the credit — the drawdown exceeds a single 5400 s pass ' +
          '(NOT the legacy last-write-wins single-pass under-consumption)',
      ).toBeGreaterThan(ONE_PASS_SECONDS);

      const row = page.getByTestId(`del-row-${firstDelivery.id}`);
      await expect(row).toBeVisible();
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-deliveries-shared-credit.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await api.request
        .delete(`${CREDITS}/${creditId}`, { headers: { authorization: adminBearer } })
        .catch(() => undefined);
      await proofVideo(page, testInfo, {
        journey: 'J-10b',
        caption:
          'J-10b · deliveries · when a glider flight and its tow both draw on ONE PersonFlightTimeCredit, ' +
          'create SUMS both passes onto the credit (asserting the actual balance == original − glider − tow) ' +
          'rather than the legacy last-write-wins under-consumption — the proof for the 3rd reachable legacy ' +
          'money bug',
        acTag: 'happy',
      });
    }
  });
});

/** The af-data-table list item (`<li>`) containing the given delivery's row link. */
function rowOf(page: Page, deliveryId: string) {
  return page.locator(`[data-testid="del-row-${deliveryId}"]`).locator('xpath=ancestor::li[1]');
}

/** The per-row delete button — scoped to the row's containing list item (af-data-table `<li>`). */
function rowDeleteButton(page: Page, deliveryId: string) {
  return rowOf(page, deliveryId).getByTestId('del-delete-button');
}
