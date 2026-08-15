import { type Browser, type BrowserContext, type Page, type TestInfo } from '@playwright/test';
import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';

import { selectAfOption } from '../_helpers/af-select';
import { enterViaNav } from '../_helpers/nav';
import { fillKcLogin } from './_helpers/kc-form';
import {
  loginAsReservationAdmin,
  captureReservationAdminBearer,
} from './_helpers/reservation-parity-fixture';
import {
  seedPlanningMasterdata,
  type PlanningMasterdata,
} from './_helpers/planning-parity-fixture';
import { proofVideo } from './_helpers/proof-video';


interface SeededPrincipal {
  username: string;
  password: string;
}

const CLUB_ADMIN1: SeededPrincipal = {
  username: 'clubadmin1@example.com',
  password: 'clubadmin1-dev-2026!',
};
const SYSADMIN: SeededPrincipal = {
  username: 'sysadmin@example.com',
  password: 'sysadmin-dev-2026!',
};

async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  const context = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  return context;
}

async function loginAs(page: Page, principal: SeededPrincipal): Promise<void> {
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, principal.username, principal.password);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
  await expect(page.getByTestId('landing-topbar-sign-in')).toHaveCount(0);
}

async function captureBearer(page: Page, warmPath: string): Promise<string> {
  const bearerPromise = page.waitForRequest(
    (req) =>
      req.url().includes('/api/v1/') &&
      typeof req.headers()['authorization'] === 'string' &&
      /^Bearer /i.test(req.headers()['authorization']!),
    { timeout: 15_000 },
  );
  await page.goto(warmPath);
  return (await bearerPromise).headers()['authorization']!;
}

test.describe('J-6b nav role-gating (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let baseURL: string;
  test.beforeAll(async ({}, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
  });

  test('[happy] a real CLUB_ADMINISTRATOR sees Reservations + Users in the nav, navigates to the calendar — and does NOT see Clubs', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAs(page, CLUB_ADMIN1);
      await page.goto('/start?lang=de');

      const reservations = page.getByTestId('af-nav-section-/reservations');
      await expect(reservations, 'a club admin sees the Reservations nav entry').toBeVisible();

      const masterdata = page.getByTestId('af-nav-group-masterdata');
      await expect(masterdata, 'a club admin sees the Masterdata nav group').toBeVisible();
      await masterdata.click();
      await expect(
        page.getByTestId('af-nav-section-/users'),
        'the Users entry is reachable under the opened Masterdata group',
      ).toBeVisible();
      await page.keyboard.press('Escape');
      await expect(page.getByTestId('af-nav-section-/users')).toHaveCount(0);

      await expect(
        page.getByTestId('af-nav-section-/clubs'),
        'a club admin does NOT see the Clubs nav entry (sysadmin-only)',
      ).toHaveCount(0);

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-nav-clubadmin.png`,
        fullPage: true,
      });

      await reservations.click();
      await expect(page).toHaveURL(/\/reservations(\?|$)/);
      await expect(page.getByTestId('reservations-view-toggle')).toBeVisible();
      await expect(page.getByTestId('reservations-day-grid')).toBeVisible();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-6b',
        caption:
          'J-6b · nav · a real CLUB_ADMINISTRATOR (clubadmin1) logs in via real Keycloak — the nav ' +
          'shows the new Reservations entry (routes to the /reservations Day/Week calendar) and ' +
          'Users, but does NOT show Clubs (the new operator decision to make /clubs sysadmin-only; ' +
          'the mock admin-everything principal would have hidden this role gate)',
        acTag: 'edge',
      });
    }
  });

  test('[happy] a real SYSTEM_ADMINISTRATOR DOES see Clubs (the role-gate positive control)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAs(page, SYSADMIN);
      await page.goto('/clubs?lang=de');

      await expect(
        page.getByTestId('af-nav-section-/clubs'),
        'a sysadmin DOES see the Clubs nav entry',
      ).toBeVisible();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-6b',
        caption:
          'J-6b · nav · a real SYSTEM_ADMINISTRATOR sees the Clubs nav entry — the positive control ' +
          'proving the Clubs hide for a club admin is role-gated, not a blanket removal',
        acTag: 'happy',
      });
    }
  });
});

test.describe('J-6b clubadmin1 reads render (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let baseURL: string;
  test.beforeAll(async ({}, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
  });

  test('[happy] clubadmin1 opens the Users menu and the list renders (no 400)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAs(page, CLUB_ADMIN1);

      await page.goto('/start?lang=de');

      const usersResp = page.waitForResponse(
        (r) => r.request().method() === 'GET' && new URL(r.url()).pathname === '/api/v1/users',
        { timeout: 15_000 },
      );
      await enterViaNav(page, '/users');
      await expect(page).toHaveURL(/\/users(\?|$)/);
      const resp = await usersResp;

      const diagBody = resp.ok() ? 'ok' : await resp.text().catch(() => '<evicted>');
      expect(
        resp.status(),
        `GET /api/v1/users must render for clubadmin1, not 400 — got ${resp.status()}: ${diagBody}`,
      ).toBe(200);

      await expect(page.getByTestId('af-page-error')).toHaveCount(0);
      await expect(
        page.locator('[data-testid^="user-row-"]').first(),
        'the Users list renders ≥1 user row for clubadmin1 (no 400, no empty)',
      ).toBeVisible();

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-users-list.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-6b',
        caption:
          'J-6b · users · clubadmin1 opens the Users menu and the list renders (GET /api/v1/users ' +
          '→ 200, ≥1 row) — the T-15 AC: no 400 for the exact dev principal (the menu-broken ' +
          'symptom was the missing tenant row, restored by the V8 seed)',
        acTag: 'happy',
      });
    }
  });

  test('[happy] the V36 dev-seed gives clubadmin1’s club ≥1 row in the Persons + Aircraft lists', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAs(page, CLUB_ADMIN1);
      const bearer = await captureBearer(page, '/persons?lang=de');

      await expect(
        page.getByTestId('persons-table'),
        'the Persons list renders for clubadmin1 (V36 seeded ≥1 person-club)',
      ).toBeVisible();
      await expect(
        page.locator('[data-testid^="person-row-"]').first(),
        'the Persons list shows ≥1 row (was empty pre-V36 — the operator symptom)',
      ).toBeVisible();

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-persons-list.png`,
        fullPage: true,
      });

      const aircraftRes = await ctx.request.get('/api/v1/aircraft', {
        headers: { authorization: bearer },
      });
      expect(aircraftRes.status(), 'GET /api/v1/aircraft renders for clubadmin1').toBe(200);
      const aircraft = (await aircraftRes.json()) as unknown[];
      expect(
        aircraft.length,
        'the Aircraft aggregate has ≥1 row for clubadmin1’s club (V36 seed)',
      ).toBeGreaterThanOrEqual(1);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-6b',
        caption:
          'J-6b · seed · clubadmin1’s club has ≥1 row in every user-facing aggregate list — the ' +
          'Persons list (the operator’s reported empty, restored by the V36 dev seed) renders a ' +
          'row, and the Aircraft aggregate read returns ≥1 (the empirical-sweep DoD: zero empty ' +
          'cells for any aggregate a testuser should own)',
        acTag: 'happy',
      });
    }
  });
});

test.describe('J-6b reservations calendar (real-idp)', () => {
  let baseURL: string;
  test.beforeAll(async ({}, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
  });

  test('[happy] the Day/Week calendar renders the selected toggle legibly + a DD.MM.YYYY period label (day) / range (week)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAs(page, CLUB_ADMIN1);
      await page.goto('/reservations?lang=de');
      await expect(page.getByTestId('reservations-day-grid')).toBeVisible();

      const dayBtn = page.getByTestId('reservations-view-day');
      const weekBtn = page.getByTestId('reservations-view-week');
      await expect(dayBtn).toHaveAttribute('data-selected', 'true');

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-reservations-calendar-day.png`,
        fullPage: true,
      });

      const lumOf = (el: ReturnType<Page['getByTestId']>, prop: 'backgroundColor' | 'color') =>
        el.evaluate((node, p) => {
          const raw = getComputedStyle(node as HTMLElement)[p as 'backgroundColor' | 'color'];
          const probe = document.createElement('span');
          probe.style.backgroundColor = raw;
          document.body.appendChild(probe);
          const rgb = getComputedStyle(probe).backgroundColor;
          probe.remove();
          const [r, g, b] = rgb.match(/\d+(\.\d+)?/g)!.map(Number);
          return 0.2126 * r! + 0.7152 * g! + 0.0722 * b!;
        }, prop);
      const selBg = await lumOf(dayBtn, 'backgroundColor');
      const selFg = await lumOf(dayBtn, 'color');
      expect(selBg, 'the selected toggle ground is dark').toBeLessThan(96);
      expect(selFg, 'the selected toggle text is light (legible, not blacked-out)').toBeGreaterThan(
        160,
      );

      const label = page.getByTestId('reservations-period-label');
      await expect(label).toHaveText(/^\d{2}\.\d{2}\.\d{4}$/);

      await weekBtn.click();
      await expect(weekBtn).toHaveAttribute('data-selected', 'true');
      await expect(page.getByTestId('reservations-week-grid')).toBeVisible();
      await expect(label).toHaveText(/^\d{2}\.\d{2}\.\d{4}\s*[–-]\s*\d{2}\.\d{2}\.\d{4}$/);

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-reservations-calendar-week.png`,
        fullPage: true,
      });

      const before = (await label.textContent())?.trim() ?? '';
      await page.getByTestId('reservations-next-week').click();
      await expect(label).not.toHaveText(before);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-6b',
        caption:
          'J-6b · reservations calendar (greenfield, no legacy parity) · the /reservations Day/Week ' +
          'calendar renders the selected toggle LEGIBLY (dark ground + white text, not the ' +
          'blacked-out bug) with a DD.MM.YYYY period label in day view and a DD.MM.YYYY – ' +
          'DD.MM.YYYY range that pages by weeks in week view (real Keycloak + backend)',
        acTag: 'happy',
      });
    }
  });
});

test.describe('J-6b planning read-only + edit-mode (real-idp)', () => {
  let baseURL: string;
  test.beforeAll(async ({}, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
  });

  test('[happy] a seeded planning day opens read-only (all fields disabled) + an Edit toggle flips it to edit mode', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAs(page, CLUB_ADMIN1);
      const bearer = await captureBearer(page, '/planning?lang=de');

      const paged = await ctx.request.post('/api/v1/planning-days/page/0/50', {
        headers: { authorization: bearer, 'content-type': 'application/json' },
        data: { sorting: { planningDate: 'asc' } },
      });
      expect(paged.status(), 'clubadmin1 can page its planning days').toBe(200);
      const body = (await paged.json()) as { items: { id: string }[] };
      expect(
        body.items.length,
        'the V34 dev seed gives seed-club-1 ≥1 future planning day to open read-only',
      ).toBeGreaterThanOrEqual(1);
      const dayId = body.items[0]!.id;

      await page.goto(`/planning/${dayId}/view?lang=de`);
      await expect(page.getByTestId('planning-edit-form')).toBeVisible();

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-planning-edit-form.png`,
        fullPage: true,
      });

      await expect(
        page.getByTestId('planning-date').locator('input'),
        'the date field is disabled in read-only mode (CVA setDisabledState, T-09)',
      ).toBeDisabled();
      await expect(page.getByTestId('planning-remarks').locator('input')).toBeDisabled();
      await expect(page.getByTestId('planning-save-button')).toHaveCount(0);

      const editToggle = page.getByTestId('planning-edit-toggle');
      await expect(editToggle).toBeVisible();
      await editToggle.locator('button').click();
      await expect(page).toHaveURL(new RegExp(`/planning/${dayId}/edit`));
      await expect(page.getByTestId('planning-date').locator('input')).toBeEnabled();
      await expect(page.getByTestId('planning-save-button')).toBeVisible();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-6b',
        caption:
          'J-6b · planning · a seeded planning day opens in READ-ONLY mode with EVERY field ' +
          'disabled (not merely Save hidden — the operator’s #10 bug, the CVA setDisabledState ' +
          'no-op T-09 fixed); the Edit affordance flips it to edit mode (fields editable, Save ' +
          'returns) — driven against the real backend as clubadmin1',
        acTag: 'happy',
      });
    }
  });
});


function dayKeyFromToday(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

const REAL_PLANNING_DUPLICATE_MESSAGE = 'A planning day already exists for this date and location.';

test.describe('J-6b inline server-side validation — real chain (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let baseURL: string;
  let adminBearer: string;
  let masterdata: PlanningMasterdata;
  let duplicateDate: string;
  const createdIds: string[] = [];
  let cleanupCtx: BrowserContext;

  test.beforeAll(async ({ browser, request }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    createdIds.length = 0;
    adminBearer = await captureReservationAdminBearer(browser, baseURL);
    masterdata = await seedPlanningMasterdata(request, adminBearer);

    duplicateDate = dayKeyFromToday(11);
    const created = await request.post('/api/v1/planning-days', {
      headers: { authorization: adminBearer, 'content-type': 'application/json' },
      data: { planningDate: duplicateDate, locationId: masterdata.locationId },
    });
    expect(
      created.status(),
      `seeding the duplicate-target planning day must 201 — got ${created.status()}: ${await created.text()}`,
    ).toBe(201);
    const loc = created.headers()['location'];
    expect(loc, 'seed create must return a 201 Location header').toBeTruthy();
    const id = new URL(loc!, 'http://localhost').pathname.split('/').pop() ?? '';
    expect(id, `Location "${loc}" must end in a planning-day UUID`).toMatch(/^[0-9a-f-]{36}$/);
    createdIds.push(id);

    cleanupCtx = await browser.newContext({ baseURL });
  });

  test.afterAll(async () => {
    for (const id of createdIds) {
      try {
        await cleanupCtx.request.delete(`/api/v1/planning-days/${id}`, {
          headers: { authorization: adminBearer },
        });
      } catch (err) {
        console.warn(`[J-6b] afterAll cleanup: delete ${id} failed (${(err as Error).message})`);
      }
    }
    await cleanupCtx?.close();
  });

  test('[happy] the planning-edit form server-validates (date, location) uniqueness against the REAL /validate endpoint — the real prose surfaces inline, clears on a free date, and blocks submit', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);
      await page.goto('/planning/new/edit?lang=de');
      await expect(page.getByTestId('planning-edit-form')).toBeVisible();

      const validateConflict = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new URL(r.url()).pathname === '/api/v1/planning-days/validate' &&
          r.status() === 200,
        { timeout: 15_000 },
      );

      await page.getByTestId('planning-date').locator('input').fill(duplicateDate);
      await selectAfOption(
        page,
        'planning-location-select',
        masterdata.locationId,
        masterdata.locationName,
      );

      const conflictResp = await validateConflict;
      const conflictBody = (await conflictResp.json()) as {
        valid: boolean;
        field?: string;
        message?: string;
      };
      expect(conflictBody.valid, 'the real /validate reports the duplicate as invalid').toBe(false);
      expect(conflictBody.field, 'the offending field is planningDate').toBe('planningDate');
      expect(
        conflictBody.message,
        'the real backend returns the duplicate PROSE (not the mock i18n key)',
      ).toBe(REAL_PLANNING_DUPLICATE_MESSAGE);

      await expect(
        page.getByTestId('planning-date-server-error'),
        'the inline server-error surfaces from the REAL /validate result',
      ).toBeVisible();

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-planning-inline-validate.png`,
        fullPage: true,
      });

      await expect(
        page.getByTestId('planning-save-button').locator('button'),
        'submit is blocked while the duplicate stands',
      ).toBeDisabled();

      const validateFree = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new URL(r.url()).pathname === '/api/v1/planning-days/validate' &&
          r.status() === 200,
        { timeout: 15_000 },
      );
      await page.getByTestId('planning-date').locator('input').fill(dayKeyFromToday(23));
      const freeBody = (await (await validateFree).json()) as { valid: boolean };
      expect(freeBody.valid, 'a free (date, location) validates clean against the real rule').toBe(
        true,
      );
      await expect(
        page.getByTestId('planning-date-server-error'),
        'the inline server-error clears when the date becomes free',
      ).toHaveCount(0);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-6b',
        caption:
          'J-6b · inline validation (the ≥60% feature, AC1/2/3) · a club admin types a (date, ' +
          'location) that duplicates a REAL seeded planning day into the planning-edit form — the ' +
          'REAL POST /planning-days/validate endpoint reports the clash over the wire (real bearer, ' +
          'real ux_pln_club_date_loc rule, real {valid,field,message} envelope carrying the backend ' +
          'PROSE, not the mock i18n key) and its message surfaces inline on the date field WITHOUT a ' +
          'save; changing to a free date clears it. The whole FE→real-endpoint→real-rule→inline ' +
          'vertical, proven end-to-end (the mock inner-loop never hit a real endpoint)',
        acTag: 'happy',
      });
    }
  });
});
