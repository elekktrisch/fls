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
import { seededFlightDateInsideListWindowAndPastBillGate } from './_helpers/seed-flight-date';
import {
  loginAsReservationAdmin,
  captureReservationAdminBearer,
  SEED_CLUB_A_ID,
} from './_helpers/reservation-parity-fixture';
import { provisionTwoClubs, type TwoClubFixture } from './_helpers/two-club-fixture';

const FLIGHTS = '/api/v1/flights';
const DELIVERIES = '/api/v1/deliveries';
const CREDITS = '/api/v1/internal/person-flight-time-credits';

const CREW_TYPE_PILOT = '019e2e15-2c00-76b0-8000-0000000036b0';
const COST_BALANCE_PILOT_PAYS_ALL = 'fcb-019e2e15-2c00-7268-8000-000000004268';

const FLIGHT_LOCKED = '019e2e15-2c00-7a9b-8000-000000003a9b';
const FLIGHT_DELIVERY_PREPARED = '019e2e15-2c00-7a9d-8000-000000003a9d';
const FLIGHT_DELIVERY_BOOKED = '019e2e15-2c00-7a9e-8000-000000003a9e';

const DELIVERY_PREPARED = 10;
const DELIVERY_BOOKED = 20;

const DAYS_AGED_PAST_ELIGIBILITY_FLOOR = 4;
const DAYS_AGED_STILL_INELIGIBLE = 0;
const NO_FLIGHT_LINK = '-';

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

interface SeederResult {
  flightId?: string;
  filterId?: string;
  deliveryId?: string;
  articleNumber?: string;
}

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

async function seedDelivery(seederArgs: string): Promise<string> {
  const id = (await runSeeder(seederArgs)).deliveryId;
  if (!id) {
    throw new Error(`seeder "${seederArgs}" returned no deliveryId`);
  }
  return id;
}

function rawFlightId(externalId: string): string {
  return externalId.replace(/^fl-/, '');
}

async function createGliderFlight(
  api: APIRequestContext,
  bearer: string,
  md: FlightMasterdata,
  aircraftId: string,
  pilotPersonId: string,
  track: Set<string>,
): Promise<string> {
  const flightDate = seededFlightDateInsideListWindowAndPastBillGate();
  const res = await api.post(FLIGHTS, {
    headers: { authorization: bearer, 'content-type': 'application/json' },
    data: {
      flightAircraftType: 'GLIDER',
      aircraftId,
      flightDate,
      startDateTime: `${flightDate}T08:00:00Z`,
      ldgDateTime: `${flightDate}T09:30:00Z`,
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

async function flightProcessStateId(
  api: APIRequestContext,
  bearer: string,
  rawId: string,
): Promise<string> {
  const res = await api.get(`${FLIGHTS}/fl-${rawId}`, { headers: { authorization: bearer } });
  expect(res.status(), `flight read must 200 — got ${res.status()}`).toBe(200);
  return (JSON.parse(await res.text()) as { processStateId: string }).processStateId;
}

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
  let adminBearer: string;
  let twoClubs: TwoClubFixture;
  let api: BrowserContext;
  let md: FlightMasterdata;
  let filterId: string;
  const createdDeliveryIds = new Set<string>();
  const seededDeliveryIds: string[] = [];
  const createdFlightIds = new Set<string>();

  test.beforeAll(async ({ browser }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    adminBearer = await captureReservationAdminBearer(browser, baseURL);
    twoClubs = await provisionTwoClubs(browser, baseURL, 'dlw');
    api = await browser.newContext({ baseURL });
    md = await seedFlightMasterdata(api.request, adminBearer);
    filterId = (await runSeeder(`rule-filter ${SEED_CLUB_A_ID} DLW-ART-FT`)).filterId ?? '';
  });

  test.afterAll(async () => {
    for (const id of [...createdDeliveryIds]) {
      await runSeeder(`delete-delivery ${id}`).catch(() => undefined);
    }
    for (const id of seededDeliveryIds) {
      await runSeeder(`delete-delivery ${id}`).catch(() => undefined);
    }
    for (const id of createdFlightIds) {
      await runSeeder(`reset-flight ${id}`).catch(() => undefined);
    }
    if (filterId) {
      await runSeeder(`delete-filter ${filterId}`).catch(() => undefined);
    }
    await api?.close();
    await twoClubs?.dispose();
  });

  test('[happy] create deliveries → the engine produces one delivery for the eligible aged-Locked glider; flight (+tow) → DeliveryPrepared', async ({
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
    await runSeeder(`lock-and-age ${flightId} ${DAYS_AGED_PAST_ELIGIBILITY_FLOOR}`);
    try {
      await loginAsReservationAdmin(page);
      await page.goto('/start?lang=en');
      await enterViaNav(page, '/deliveries');
      await expect(page).toHaveURL('/deliveries');
      await expect(page.getByTestId('del-table')).toBeVisible();

      const created = page.waitForResponse(
        (r) =>
          new URL(r.url()).pathname === `${DELIVERIES}/create` &&
          r.request().method() === 'POST' &&
          r.status() === 200,
      );
      await page.getByTestId(CREATE_BUTTON).click();
      await created;

      const delivery = await deliveryForFlight(api.request, adminBearer, flightId);
      createdDeliveryIds.add(delivery.id);
      expect(delivery.processStateId, 'the created delivery is Prepared').toBe(DELIVERY_PREPARED);
      expect(delivery.items.length, 'the engine produced the known line item(s)').toBeGreaterThan(
        0,
      );

      expect(
        await flightProcessStateId(api.request, adminBearer, flightId),
        'the billed flight flipped to DeliveryPrepared',
      ).toBe(FLIGHT_DELIVERY_PREPARED);

      const row = page.getByTestId(`del-row-${delivery.id}`);
      await expect(row, 'the engine-created delivery renders in the list').toBeVisible();
      await expect(page.getByTestId(`del-state-${delivery.id}`)).toBeVisible();
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-deliveries-list.png`,
        fullPage: true,
      });

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

  test('[happy] delete a Prepared delivery → flight (+tow) reset to Locked, the consumed credit REVERSED (append-only)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
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
    await runSeeder(`lock-and-age ${gliderId} ${DAYS_AGED_PAST_ELIGIBILITY_FLOOR}`);
    await runSeeder(`lock-and-age ${towId} ${DAYS_AGED_STILL_INELIGIBLE}`);
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

      expect(
        await flightProcessStateId(api.request, adminBearer, gliderId),
        'the glider flight reset to Locked',
      ).toBe(FLIGHT_LOCKED);
      expect(
        await flightProcessStateId(api.request, adminBearer, towId),
        'the TOW flight reset to Locked (the correct, persisted target)',
      ).toBe(FLIGHT_LOCKED);

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

  test('[key-error] delete rejected when >1 delivery shares the flight → 409 surfaced, no mutation', async ({
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
    await runSeeder(`lock-and-age ${flightId} ${DAYS_AGED_PAST_ELIGIBILITY_FLOOR}`);
    const batch = Number(Date.now().toString().slice(-7));
    const d1 = await seedDelivery(`delivery ${SEED_CLUB_A_ID} ${flightId} Shared ${batch}`);
    const d2 = await seedDelivery(`delivery ${SEED_CLUB_A_ID} ${flightId} Shared ${batch + 1}`);
    seededDeliveryIds.push(d1, d2);
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
    await runSeeder(`prepare-flight ${flightId}`);
    const batch = Number(Date.now().toString().slice(-7));
    const deliveryId = await seedDelivery(`delivery ${SEED_CLUB_A_ID} ${flightId} Booked ${batch}`);
    seededDeliveryIds.push(deliveryId);
    try {
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

    const detail = (await api.request
      .get(`${DELIVERIES}/${deliveryId}`, { headers: { authorization: adminBearer } })
      .then((r) => r.json())) as DeliveryDetail;
    expect(detail.deliveryNumber, 'the free-text delivery number is stamped verbatim').toBe(
      FREE_TEXT_NUMBER,
    );
    expect(detail.processStateId, 'the delivery is Booked').toBe(DELIVERY_BOOKED);

    expect(
      await flightProcessStateId(api.request, adminBearer, flightId),
      'the booked flight flipped to DeliveryBooked',
    ).toBe(FLIGHT_DELIVERY_BOOKED);
    expect(
      await flightProcessStateId(api.request, adminBearer, towId),
      'the tow flight flipped to DeliveryBooked',
    ).toBe(FLIGHT_DELIVERY_BOOKED);
  });

  test('[edge] cross-tenant write (delete / book another club’s delivery) is rejected — 404 delete, false book', async () => {
    const batch = Number(Date.now().toString().slice(-7));
    const clubBDelivery = await seedDelivery(
      `delivery ${twoClubs.clubB.clubId} ${NO_FLIGHT_LINK} Foreign ${batch}`,
    );
    seededDeliveryIds.push(clubBDelivery);

    const crossDelete = await api.request.delete(`${DELIVERIES}/${clubBDelivery}`, {
      headers: { authorization: adminBearer },
    });
    expect(
      crossDelete.status(),
      `club A must NOT delete club B's delivery (cross-tenant 404) — got ${crossDelete.status()}`,
    ).toBe(404);

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

  test('[money-proof] glider + tow drawing on ONE credit → create SUMS both passes (not under-consumed)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
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
    await runSeeder(`lock-and-age ${firstPassId} ${DAYS_AGED_PAST_ELIGIBILITY_FLOOR}`);
    await runSeeder(`lock-and-age ${secondPassId} ${DAYS_AGED_PAST_ELIGIBILITY_FLOOR}`);
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

function rowOf(page: Page, deliveryId: string) {
  return page.locator(`[data-testid="del-row-${deliveryId}"]`).locator('xpath=ancestor::li[1]');
}

function rowDeleteButton(page: Page, deliveryId: string) {
  return rowOf(page, deliveryId).getByTestId('del-delete-button');
}
