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

const PLANNINGDAYS = '/api/v1/planning-days';

const REAL_PROVISIONING_BEFOREALL_TIMEOUT_MS = 180_000;

const SUBJECT_PLANNING_DAY_OK_TAKES_PLACE = 'Flugbetriebstag findet statt';
const SUBJECT_WEEK_AHEAD_ASSIGNMENT_REMINDER = 'Erinnerung: Einteilung Flugbetriebstag';

const MIGRATED_LEGACY_DAY_REMARK = 'Test3';

const GALLERY_LIST_PARITY_SHOT = 'alpenflight-planning-list.png';
const GALLERY_FORM_PARITY_SHOT_CAPTURED_BEFORE_DEEP_ASSERTS = 'alpenflight-planning-form.png';
const GALLERY_SETUP_PARITY_SHOT_CAPTURED_BEFORE_GENERATE = 'alpenflight-planning-setup-form.png';
const DIAGNOSTIC_CREATE_FORM_SHOT = 'alpenflight-planning-create-form.png';
const DIAGNOSTIC_RESERVATIONS_PANEL_SHOT = 'alpenflight-planning-reservations-panel.png';
const DIAGNOSTIC_MIGRATED_LIST_SHOT = 'alpenflight-planning-migrated-list.png';

function dayKeyFromToday(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function nextSaturdayFromToday(minDays: number): string {
  const d = new Date();
  d.setDate(d.getDate() + minDays);
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
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  return context;
}

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

interface PlanningDayRequest {
  planningDate: string;
  locationId: string;
  instructorPersonId?: string;
  towingPilotPersonId?: string;
  flightOperatorPersonId?: string;
  info?: string;
}

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

test.describe('Planning days — clean-seed real chain (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let baseURL: string;
  let adminBearer: string;
  let masterdata: PlanningMasterdata;
  let locEditCrew: { locationId: string; locationName: string };
  let locInline: { locationId: string; locationName: string };
  let locDuplicate: { locationId: string; locationName: string };
  let locWizard: { locationId: string; locationName: string };
  let locDelete: { locationId: string; locationName: string };
  let locTenant: { locationId: string; locationName: string };
  let locNotify: { locationId: string; locationName: string };
  let reservationTypeId: string;
  const createdIds: string[] = [];
  const createdReservationIds: string[] = [];
  let cleanupCtx: BrowserContext;
  let pilot: SeedClubPilot;
  let pilotBearer: string;
  let twoClubs: TwoClubFixture;

  test.beforeAll(async ({ browser, request }, testInfo) => {
    testInfo.setTimeout(REAL_PROVISIONING_BEFOREALL_TIMEOUT_MS);
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    createdIds.length = 0;
    createdReservationIds.length = 0;
    adminBearer = await captureReservationAdminBearer(browser, baseURL);
    pilot = await provisionSeedClubPilot();
    pilotBearer = await captureSeedClubPilotBearer(browser, baseURL, pilot);
    twoClubs = await provisionTwoClubs(browser, baseURL, 'pln');
    masterdata = await seedPlanningMasterdata(request, adminBearer);
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
    reservationTypeId = await fetchReservationTypeId(request, adminBearer);
    cleanupCtx = await browser.newContext({ baseURL });
  });

  test.afterAll(async () => {
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

      await page.goto('/planning?lang=de');
      await expect(page.locator('h1')).toContainText('Planung');
      await expect(page.getByTestId('planning-list')).toBeVisible();
      await expect(
        page.locator('[data-testid^="planning-row-"][data-weekend="true"]').first(),
        'the V34 weekend seed day renders flagged on the future-days list',
      ).toBeVisible();

      await page.getByTestId('planning-new-button').locator('button').click();
      await expect(page).toHaveURL('/planning/new/edit');

      await page.getByTestId('planning-date').locator('input').fill(dayKeyFromToday(5));
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

      await page.screenshot({
        path: `${testInfo.outputDir}/${DIAGNOSTIC_CREATE_FORM_SHOT}`,
        fullPage: true,
      });

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

      await expect(page).toHaveURL('/planning');
      const row = page.getByTestId(`planning-row-${id}`);
      await expect(row, 'the created planning day renders in the future-days list').toBeVisible();
      await expect(row).toContainText(masterdata.locationName);
      await expect(row).toContainText(masterdata.instructorName);
      await expect(row).toContainText(masterdata.towPilotName);
      await expect(row).toContainText(masterdata.flightOpName);

      await page.screenshot({
        path: `${testInfo.outputDir}/${GALLERY_LIST_PARITY_SHOT}`,
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

      const planningDate = dayKeyFromToday(13);
      const reservationId = await seedReservationOnPlanningDay(ctx.request, adminBearer, {
        planningDate,
        locationId: locInline.locationId,
        aircraftId: masterdata.aircraftId,
        pilotPersonId: masterdata.instructorId,
        reservationTypeId,
      });
      createdReservationIds.push(reservationId);

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

      const seededRow = page.getByTestId(`planning-reservation-${reservationId}`);
      await expect(
        seededRow,
        'the seeded reservation renders as an <af-reservation-row> in the inline list (create mode)',
      ).toBeVisible();
      await page.screenshot({
        path: `${testInfo.outputDir}/${GALLERY_FORM_PARITY_SHOT_CAPTURED_BEFORE_DEEP_ASSERTS}`,
        fullPage: true,
      });
      await page.screenshot({
        path: `${testInfo.outputDir}/${DIAGNOSTIC_RESERVATIONS_PANEL_SHOT}`,
        fullPage: true,
      });
      await expect(seededRow).toContainText(masterdata.aircraftImmat);

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
      const dupDate = nextSaturdayFromToday(15);
      const first = await createPlanningDay(ctx, adminBearer, createdIds, {
        planningDate: dupDate,
        locationId: locDuplicate.locationId,
      });
      expect(first).toBeTruthy();

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

      const ruleStart = dupDate;
      const ruleEnd = dayKeyFromToday(36);
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

      const start = dayKeyFromToday(20);
      const end = dayKeyFromToday(34);

      await page.goto('/planningsetup?lang=de');
      await expect(page.getByTestId('planning-setup-form')).toBeVisible();
      await page.getByTestId('planning-setup-start').locator('input').fill(start);
      await page.getByTestId('planning-setup-end').locator('input').fill(end);
      await selectAfOption(
        page,
        'planning-setup-location-select',
        locWizard.locationId,
        locWizard.locationName,
      );

      await page.screenshot({
        path: `${testInfo.outputDir}/${GALLERY_SETUP_PARITY_SHOT_CAPTURED_BEFORE_GENERATE}`,
        fullPage: true,
      });

      const ruleExpandCompletedBodyEvictedBySpaNav = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new URL(r.url()).pathname === `${PLANNINGDAYS}/create/rule` &&
          r.status() === 201,
      );
      await page.getByTestId('planning-setup-generate-button').click();
      await ruleExpandCompletedBodyEvictedBySpaNav;

      await expect(page).toHaveURL('/planning');

      const wizardWindowPage = await ctx.request.post(`${PLANNINGDAYS}/page/0/50`, {
        headers: { authorization: adminBearer, 'content-type': 'application/json' },
        data: { sorting: { planningDate: 'asc' }, searchFilter: { from: start } },
      });
      expect(wizardWindowPage.status(), 'the planning list pages the wizard-created days').toBe(
        200,
      );
      const wizardWindowBody = (await wizardWindowPage.json()) as { items: PlanningDayListRow[] };
      const everyDayTheWizardCreated = wizardWindowBody.items.filter(
        (d) =>
          d.locationId === locWizard.locationId && d.planningDate >= start && d.planningDate <= end,
      );
      expect(
        everyDayTheWizardCreated.length,
        'the wizard bulk-created at least one weekend day',
      ).toBeGreaterThan(0);
      for (const d of everyDayTheWizardCreated) {
        createdIds.push(d.id);
        const dow = new Date(`${d.planningDate}T00:00:00`).getDay();
        expect(dow === 0 || dow === 6, `generated day ${d.planningDate} is a Sat/Sun`).toBe(true);
      }

      await expect(
        page.getByTestId(`planning-row-${everyDayTheWizardCreated[0]!.id}`),
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

      const dayDate = dayKeyFromToday(17);
      const id = await createPlanningDay(ctx, adminBearer, createdIds, {
        planningDate: dayDate,
        locationId: locDelete.locationId,
        instructorPersonId: masterdata.instructorId,
        towingPilotPersonId: masterdata.towPilotId,
        flightOperatorPersonId: masterdata.flightOpId,
      });

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

      await expect(row, 'the deleted day no longer renders in the list').toHaveCount(0);

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
      const id = await createPlanningDay(ctx, adminBearer, createdIds, {
        planningDate: dayKeyFromToday(19),
        locationId: locTenant.locationId,
      });

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

      const ownRead = await ctx.request.get(`${PLANNINGDAYS}/${id}`, {
        headers: { authorization: adminBearer },
      });
      expect(ownRead.status(), 'the owning tenant reads its own day').toBe(200);

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

      await purgeMailpit();

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

      const weekAheadId = await createPlanningDay(ctx, adminBearer, createdIds, {
        planningDate: dayKeyFromToday(7),
        locationId: locNotify.locationId,
        instructorPersonId: masterdata.instructorId,
        towingPilotPersonId: masterdata.towPilotId,
        flightOperatorPersonId: masterdata.flightOpId,
      });
      expect(imminentId && weekAheadId).toBeTruthy();

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
      expect(
        summary.imminentMailCount,
        'the imminent pass mailed ≥1 club mail',
      ).toBeGreaterThanOrEqual(1);
      expect(
        summary.weekAheadMailCount,
        'the week-ahead pass mailed the 3 assignees',
      ).toBeGreaterThanOrEqual(3);

      const clubMail = await waitForMessageWithSubject(
        SEED_CLUB_NOTIFICATION_ADDRESS,
        SUBJECT_PLANNING_DAY_OK_TAKES_PLACE,
      );
      expect(
        clubMail.Subject,
        'the imminent club mail is the planningday-ok template (the day takes place)',
      ).toBe(SUBJECT_PLANNING_DAY_OK_TAKES_PLACE);

      const instructorMail = await waitForMessage(masterdata.instructorEmail);
      expect(instructorMail.Subject).toBe(SUBJECT_WEEK_AHEAD_ASSIGNMENT_REMINDER);
      const towPilotMail = await waitForMessage(masterdata.towPilotEmail);
      expect(towPilotMail.Subject).toBe(SUBJECT_WEEK_AHEAD_ASSIGNMENT_REMINDER);
      const flightOpMail = await waitForMessage(masterdata.flightOpEmail);
      expect(flightOpMail.Subject).toBe(SUBJECT_WEEK_AHEAD_ASSIGNMENT_REMINDER);
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

      const paged = await ctx.request.post(`${PLANNINGDAYS}/page/0/50`, {
        headers: { authorization: migratedBearer, 'content-type': 'application/json' },
        data: { sorting: { planningDate: 'asc' } },
      });
      expect(paged.status(), 'the migrated TestClub can page its migrated planning days').toBe(200);
      const body = (await paged.json()) as { items: PlanningDayListRow[]; totalRows: number };

      const migrated = body.items.find((d) => d.info === MIGRATED_LEGACY_DAY_REMARK);
      expect(
        migrated,
        `the migrated legacy planning day (remark "${MIGRATED_LEGACY_DAY_REMARK}") must be present for the migrated ` +
          `TestClub — the T-11 legacy→export→migrate→render round-trip. Got ` +
          `${body.items.length} row(s): ${JSON.stringify(body.items.map((d) => d.info))}`,
      ).toBeTruthy();

      expect(migrated!.locationId, 'the migrated day carries a resolved location FK').toBeTruthy();
      const ownLocation = await ctx.request.get(`/api/v1/locations/${migrated!.locationId}`, {
        headers: { authorization: migratedBearer },
      });
      expect(
        ownLocation.status(),
        'the migrated day’s location resolves to the migrated club’s OWN Location replica (own-club FK)',
      ).toBe(200);

      await page.goto('/planning?lang=de');
      await expect(page.getByTestId('planning-list')).toBeVisible();
      await expect(
        page.getByTestId(`planning-row-${migrated!.id}`),
        'the identified migrated planning day renders in the migrated tenant’s future-days list',
      ).toBeVisible();
      await page.screenshot({
        path: `${testInfo.outputDir}/${DIAGNOSTIC_MIGRATED_LIST_SHOT}`,
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
