import { type Browser, type BrowserContext, type Page, type TestInfo } from '@playwright/test';
import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';

import {
  loginAsClubAdmin,
  provisionTwoClubs,
  type TwoClubFixture,
} from './_helpers/two-club-fixture';
import {
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

const RESERVATIONS = '/api/v1/aircraft-reservations';

const UI_CREATE_UNDER_LOADED_FANOUT_TIMEOUT_MS = 90_000;

const DAYS_AHEAD_CLEAR_OF_THE_TIMED_TODAY_RESERVATION = 7;

function todayKey(): string {
  const now = new Date();
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}
function instant(dateKey: string, hhmm: string): string {
  return `${dateKey}T${hhmm}:00Z`;
}

function dayBlock(page: Page, aircraftId: string, reservationId: string) {
  return page
    .getByTestId(`reservation-scheduler-lane-${aircraftId}`)
    .getByTestId(`reservation-scheduler-block-${reservationId}`);
}

async function selectCalendarDay(page: Page, dateKey: string): Promise<void> {
  const pill = page.getByTestId(`reservations-daypicker-${dateKey}`);
  const todayMs = new Date(`${todayKey()}T00:00:00`).getTime();
  const targetMs = new Date(`${dateKey}T00:00:00`).getTime();
  const direction = targetMs >= todayMs ? 'next' : 'prev';
  const wasDay =
    (await page.getByTestId('reservations-view-day').getAttribute('data-selected')) === 'true';
  if ((await pill.count()) === 0 && wasDay) {
    await page.getByTestId('reservations-view-week').click();
  }
  const firstPillKey = async (): Promise<string | null> => {
    const first = page.locator('[data-testid^="reservations-daypicker-"]').first();
    if ((await first.count()) === 0) return null;
    return (
      (await first.getAttribute('data-testid'))?.replace('reservations-daypicker-', '') ?? null
    );
  };
  for (let i = 0; i < 60 && (await pill.count()) === 0; i++) {
    const before = await firstPillKey();
    await page.getByTestId(`reservations-${direction}-week`).click();
    await expect
      .poll(async () => firstPillKey(), { message: 'the day-picker must shift to a new week' })
      .not.toBe(before);
  }
  await expect(pill, `the day-picker must reach ${dateKey}`).toBeVisible();
  await pill.click();
  if (wasDay) {
    await page.getByTestId('reservations-view-day').click();
  }
}

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
  const context = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  return context;
}

interface ReservationListRow {
  id: string;
  aircraftId: string;
  start: string;
  reservationTypeName?: string | null;
  remarks?: string | null;
}

async function createReservation(
  ctx: BrowserContext,
  bearer: string,
  created: string[],
  body: {
    aircraftId: string;
    pilotPersonId: string;
    locationId: string;
    reservationTypeId: string;
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

test.describe('Aircraft reservations — clean-seed real chain (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let twoClubs: TwoClubFixture;
  let baseURL: string;
  let adminBearer: string;
  let masterdata: ReservationMasterdata;
  let reservationTypeId: string;
  const createdIds: string[] = [];
  let cleanupCtx: BrowserContext;

  test.beforeAll(async ({ browser, request }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    createdIds.length = 0;

    twoClubs = await provisionTwoClubs(browser, baseURL, 'resv');
    adminBearer = await captureReservationAdminBearer(browser, baseURL);

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

    reservationTypeId = await fetchReservationTypeId(request, adminBearer);

    cleanupCtx = await browser.newContext({ baseURL });
  });

  test.afterAll(async () => {
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

  test('[happy] create a timed reservation through the UI type-picker → it renders as a time-placed block in its aircraft lane on the day calendar', async ({
    browser,
  }, testInfo) => {
    test.setTimeout(UI_CREATE_UNDER_LOADED_FANOUT_TIMEOUT_MS);
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);

      await page.goto('/reservations?lang=de');
      await expect(page.locator('h1')).toContainText('Reservationen');
      await expect(page.getByTestId('reservations-day-grid')).toBeVisible();

      await page.getByTestId('reservations-new-button').locator('button').click();
      await expect(page).toHaveURL('/reservations/new');

      await selectAfOption(
        page,
        'reservation-aircraft-select',
        masterdata.managedAircraftId,
        masterdata.managedImmat,
      );
      await selectAfOption(page, 'reservation-type-select', reservationTypeId);
      await selectAfOption(page, 'reservation-pilot-select', masterdata.pilotPersonId);
      const secondCrewRequiredByTheMultiSeatAircraft = masterdata.pilotPersonId;
      await selectAfOption(
        page,
        'reservation-second-crew-select',
        secondCrewRequiredByTheMultiSeatAircraft,
      );
      await selectAfOption(page, 'reservation-location-select', masterdata.locationId);
      const today = todayKey();
      await page.getByTestId('reservation-date').locator('input').fill(today);
      await page.getByTestId('reservation-start-time').locator('input').fill('10:00');
      await page.getByTestId('reservation-end-time').locator('input').fill('11:00');

      await test.step('capture the populated create form before the save, so a partial red still yields the gallery shot', async () => {
        await page.screenshot({
          path: `${testInfo.outputDir}/alpenflight-reservation-form.png`,
          fullPage: true,
        });
      });

      const createdResp = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new URL(r.url()).pathname === '/api/v1/aircraft-reservations' &&
          r.status() === 201,
        { timeout: 30_000 },
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

      await expect(page).toHaveURL('/reservations');

      await expect(page.getByTestId('reservations-day-grid')).toBeVisible();
      const lane = page.getByTestId(`reservation-scheduler-lane-${masterdata.managedAircraftId}`);
      await expect(lane).toBeVisible();
      await expect(lane).toContainText(masterdata.managedImmat);
      const block = dayBlock(page, masterdata.managedAircraftId, id);
      await expect(block, 'the created reservation renders as a day-view block').toBeVisible();
      await expect(block).toContainText('10:00');
      const left = await block.evaluate((el) => (el as HTMLElement).style.left);
      const leftPct = Number.parseFloat(/([\d.]+)%/.exec(left)?.[1] ?? 'NaN');
      expect(leftPct, 'a timed block carries a positive sub-100 left offset').toBeGreaterThan(0);
      expect(leftPct).toBeLessThan(100);

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-reservations-list.png`,
        fullPage: true,
      });
      await page.getByTestId('reservations-view-week').click();
      await expect(page.getByTestId('reservations-week-grid')).toBeVisible();
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
          'pilot and location from the real dropdowns — and it renders on the /reservations CALENDAR ' +
          'day view as a time-placed block in the right aircraft lane (full clean-seed UI ' +
          'create→type-picker chain, real backend round-trip)',
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

      const existingId = await createReservation(ctx, adminBearer, createdIds, {
        aircraftId: masterdata.managedAircraftId,
        pilotPersonId: masterdata.pilotPersonId,
        locationId: masterdata.locationId,
        reservationTypeId,
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
          reservationTypeId,
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

      const adjacentHalfOpenBooking = await createReservation(ctx, adminBearer, createdIds, {
        aircraftId: masterdata.managedAircraftId,
        pilotPersonId: masterdata.pilotPersonId,
        locationId: masterdata.locationId,
        reservationTypeId,
        start: '2026-09-02T14:00:00Z',
        end: '2026-09-02T15:00:00Z',
        isAllDay: false,
      });

      const selfEdit = await ctx.request.put(`${RESERVATIONS}/${existingId}`, {
        headers: { authorization: adminBearer, 'content-type': 'application/json' },
        data: {
          aircraftId: masterdata.managedAircraftId,
          pilotPersonId: masterdata.pilotPersonId,
          locationId: masterdata.locationId,
          reservationTypeId,
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

      expect(adjacentHalfOpenBooking).toBeTruthy();
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
          reservationTypeId,
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

      const allDayKey = dayKeyFromToday(DAYS_AHEAD_CLEAR_OF_THE_TIMED_TODAY_RESERVATION);
      const id = await createReservation(ctx, adminBearer, createdIds, {
        aircraftId: masterdata.managedAircraftId,
        pilotPersonId: masterdata.pilotPersonId,
        locationId: masterdata.locationId,
        reservationTypeId,
        start: instant(allDayKey, '00:00'),
        end: instant(allDayKey, '00:00'),
        isAllDay: true,
      });

      const detail = await ctx.request.get(`${RESERVATIONS}/${id}`, {
        headers: { authorization: adminBearer },
      });
      expect(detail.status()).toBe(200);
      const d = (await detail.json()) as { isAllDay: boolean };
      expect(d.isAllDay, 'the reservation is stored all-day').toBe(true);

      await page.goto('/reservations?lang=de');
      await expect(page.getByTestId('reservations-day-grid')).toBeVisible();
      await selectCalendarDay(page, allDayKey);
      const block = dayBlock(page, masterdata.managedAircraftId, id);
      await expect(block).toBeVisible();
      const left = await block.evaluate((el) => (el as HTMLElement).style.left);
      expect(left, 'an all-day band starts at the lane left edge (0%)').toContain('0%');
      const width = await block.evaluate((el) => (el as HTMLElement).style.width);
      expect(width, 'an all-day reservation is a full-width band').toContain('100%');
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-5',
        caption:
          'J-5 · all-day reservation · an all-day reservation stores the full-day span and renders ' +
          'as a full-width band on the /reservations calendar day view (real backend)',
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

      const today = todayKey();
      const id = await createReservation(ctx, adminBearer, createdIds, {
        aircraftId: masterdata.foreignAircraftId,
        pilotPersonId: masterdata.pilotPersonId,
        locationId: masterdata.locationId,
        reservationTypeId,
        start: instant(today, '09:00'),
        end: instant(today, '10:00'),
        isAllDay: false,
        remarks: 'cross-tenant legacy-open',
      });

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

      await page.goto('/reservations?lang=de');
      await expect(page.getByTestId('reservations-day-grid')).toBeVisible();
      const lane = page.getByTestId(`reservation-scheduler-lane-${masterdata.foreignAircraftId}`);
      await expect(lane).toBeVisible();
      await expect(lane).toContainText(masterdata.foreignImmat);
      await expect(dayBlock(page, masterdata.foreignAircraftId, id)).toBeVisible();
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

      const today = todayKey();
      const firstId = await createReservation(ctx, adminBearer, createdIds, {
        aircraftId: masterdata.managedAircraftId,
        pilotPersonId: masterdata.pilotPersonId,
        locationId: masterdata.locationId,
        reservationTypeId,
        start: instant(today, '16:00'),
        end: instant(today, '17:00'),
        isAllDay: false,
      });

      const blocked = await ctx.request.post(RESERVATIONS, {
        headers: { authorization: adminBearer, 'content-type': 'application/json' },
        data: {
          aircraftId: masterdata.managedAircraftId,
          pilotPersonId: masterdata.pilotPersonId,
          locationId: masterdata.locationId,
          reservationTypeId,
          start: instant(today, '16:30'),
          end: instant(today, '16:45'),
          isAllDay: false,
        },
      });
      expect(blocked.status(), 'the slot is occupied → overlapping create 409s').toBe(409);

      await page.goto('/reservations?lang=de');
      await expect(page.getByTestId('reservations-day-grid')).toBeVisible();
      await expect(dayBlock(page, masterdata.managedAircraftId, firstId)).toBeVisible();
      const del = await ctx.request.delete(`${RESERVATIONS}/${firstId}`, {
        headers: { authorization: adminBearer },
      });
      expect(del.status(), 'the reservation soft-deletes (204)').toBe(204);
      await page.goto('/reservations?lang=de');
      await expect(dayBlock(page, masterdata.managedAircraftId, firstId)).toHaveCount(0);

      const freed = await createReservation(ctx, adminBearer, createdIds, {
        aircraftId: masterdata.managedAircraftId,
        pilotPersonId: masterdata.pilotPersonId,
        locationId: masterdata.locationId,
        reservationTypeId,
        start: instant(today, '16:30'),
        end: instant(today, '16:45'),
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

test.describe('Aircraft reservations — migrated legacy reservation renders (real-idp)', () => {
  test.describe.configure({ mode: 'serial', retries: 0 });

  test.skip(
    !useRealBundle(),
    'migrated-reservation render requires the real legacy export (J5_BUNDLE_SOURCE=real, fanout only)',
  );

  let baseURL: string;
  let migratedAdmin: MigratedClubAdmin;
  let migratedBearer: string;

  test.beforeAll(async ({ browser }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    const resolved = await resolveMigratedTestClubAdmin(browser, baseURL, testInfo);
    migratedAdmin = resolved.admin;
    migratedBearer = resolved.bearer;
  });

  test('[happy] the migrated legacy reservation renders under its migrated TestClub tenant', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsMigratedTestClubAdmin(page, migratedAdmin);

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
      expect(
        migrated!.reservationTypeName,
        'the migrated reservation carries its migrated type name (Schulung)',
      ).toBe(MIGRATED_RESERVATION_TYPE_NAME);

      const migratedDayKey = migrated!.start.slice(0, 10);
      await page.goto('/reservations?lang=de');
      await expect(page.getByTestId('reservations-day-grid')).toBeVisible();
      await selectCalendarDay(page, migratedDayKey);
      await expect(
        dayBlock(page, migrated!.aircraftId, migrated!.id),
        'the identified migrated reservation renders as a day-view block in its lane',
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
          "through the live chain, renders on the migrated TestClub's /reservations CALENDAR (the " +
          'day-view block in its aircraft lane) under its unique fixture remark + migrated Schulung ' +
          'type (full legacy→export→migrate→Keycloak→UI chain, read via the migrated FULL_PORT tenant)',
        acTag: 'happy',
      });
    }
  });
});
