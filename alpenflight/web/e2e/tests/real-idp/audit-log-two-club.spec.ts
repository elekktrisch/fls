import {
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
  type TestInfo,
} from '@playwright/test';
import { test, expect, watchConsoleErrors, allowConsoleErrors } from '../_helpers/console-guard';

import { enterViaNav } from '../_helpers/nav';
import { selectAfOption } from '../_helpers/af-select';
import {
  loginAsClubAdmin,
  provisionTwoClubs,
  type TwoClubFixture,
} from './_helpers/two-club-fixture';
import { fillKcLogin } from './_helpers/kc-form';
import { proofVideo } from './_helpers/proof-video';

const AUDIT_LOGS_PATH = '/system/logs';
const START_PATH = '/start';

const AUDIT_PAGE_SIZE = 50;
const SUCCESS_ROW_HTTP_STATUS_PLACEHOLDER = '—';

const CH_COUNTRY_LABEL = 'Switzerland';

const PILOT_USER = 'pilot1@example.com';
const PILOT_PASSWORD = 'pilot1-dev-2026!';

const TESTIDS = {
  table: 'audit-logs-table',
  row: 'audit-row',
  rowAction: 'audit-row-action',
  rowTarget: 'audit-row-target',
  rowActor: 'audit-row-actor',
  rowStatus: 'audit-row-status',
  rowTime: 'audit-row-time',
  rowDetail: 'audit-row-detail',
  filterAction: 'audit-filter-action',
  filterTarget: 'audit-filter-target',
  filterFrom: 'audit-filter-from',
  filterTo: 'audit-filter-to',
  clearFilters: 'audit-clear-filters',
  pagerNext: 'audit-pager-next',
  pagerPrev: 'audit-pager-prev',
  pagerOffset: 'audit-pager-offset',
  empty: 'audit-logs-empty',
} as const;

async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  const context = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  return context;
}

async function selectSwitzerland(page: Page): Promise<void> {
  await page.getByTestId('locations-country-select').locator('nz-select').click();
  await page.keyboard.type(CH_COUNTRY_LABEL);
  await page
    .locator('nz-option-item')
    .filter({ hasText: new RegExp(`^${CH_COUNTRY_LABEL}$`) })
    .click();
}

async function createLocationViaUi(page: Page, opts: { name: string }): Promise<string> {
  const created = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      new URL(r.url()).pathname === '/api/v1/locations' &&
      r.status() === 201,
  );
  await page.goto('/locations');
  await page.getByRole('button', { name: 'New location' }).click();
  await expect(page).toHaveURL('/locations/new');
  await page.locator('#LocationName').fill(opts.name);
  await selectSwitzerland(page);
  await page.getByTestId('locations-type-select').locator('nz-select').click();
  await page.locator('nz-option-item').first().click();
  const saveButton = page.getByTestId('locations-save-button');
  await expect(saveButton.locator('button')).toBeEnabled();
  await saveButton.click();
  await created;
  await expect(page).toHaveURL('/locations');

  const row = page.locator('[data-testid^="location-row-"]').filter({ hasText: opts.name });
  await expect(row).toBeVisible();
  const rowTestId = await row.getAttribute('data-testid');
  expect(rowTestId, 'created Location row must carry a location-row-<id> testid').toBeTruthy();
  const id = rowTestId!.replace(/^location-row-/, '');
  expect(id, `derived Location id must be loc-<uuid>, got "${id}"`).toMatch(/^loc-[0-9a-f-]{36}$/);
  return id;
}

async function renameLocationViaUi(page: Page, locId: string, newName: string): Promise<void> {
  const updated = page.waitForResponse(
    (r) =>
      r.request().method() === 'PUT' &&
      new URL(r.url()).pathname === `/api/v1/locations/${locId}` &&
      r.status() === 200,
  );
  await page.goto('/locations');
  await page.locator(`[data-testid="location-row-${locId}"]`).click();
  await expect(page).toHaveURL(`/locations/${locId}/edit`);
  await page.locator('#LocationName').fill(newName);
  await page.getByTestId('locations-save-button').click();
  await updated;
  await expect(page).toHaveURL('/locations');
}

async function createAircraftViaUi(page: Page, immatriculation: string): Promise<void> {
  const created = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      new URL(r.url()).pathname === '/api/v1/aircraft' &&
      r.status() === 201,
  );
  await page.goto('/aircraft');
  await page.getByTestId('aircraft-new-button').locator('button').click();
  await expect(page).toHaveURL('/aircraft/new');
  await page.locator('#Immatriculation').fill(immatriculation);
  await page.getByTestId('aircraft-type-select').locator('nz-select').click();
  await page.locator('nz-option-item').filter({ hasText: 'Glider' }).first().click();
  const saveButton = page.getByTestId('aircraft-save-button');
  await expect(saveButton.locator('button')).toBeEnabled();
  await saveButton.locator('button').click();
  await created;
  await expect(page).toHaveURL('/aircraft');
}

async function bearerFor(page: Page): Promise<string> {
  const reqPromise = page.waitForRequest(
    (req) =>
      req.url().includes('/api/v1/') &&
      typeof req.headers()['authorization'] === 'string' &&
      /^Bearer /i.test(req.headers()['authorization']!),
  );
  await page.goto(START_PATH);
  const req = await reqPromise;
  return req.headers()['authorization']!;
}

async function seedAuditEvents(
  api: APIRequestContext,
  bearer: string,
  count: number,
): Promise<void> {
  for (let i = 0; i < count; i++) {
    const res = await api.delete(`/api/v1/locations/loc-${randomUuid()}`, {
      headers: { authorization: bearer },
    });
    expect(
      res.status(),
      `audit-seed DELETE #${i} should 404 (non-existent id under tenant scope)`,
    ).toBe(404);
  }
}

function randomUuid(): string {
  return globalThis.crypto.randomUUID();
}

function auditRowsWithTarget(page: Page, targetType: string) {
  return page.getByTestId(TESTIDS.row).filter({ has: page.getByText(targetType, { exact: true }) });
}

async function filterToCreatedLocations(page: Page): Promise<void> {
  await selectAfOption(page, TESTIDS.filterAction, 'CREATE');
  await page.getByTestId(TESTIDS.filterTarget).locator('input').fill('Location');
  await expect(page.getByTestId(TESTIDS.rowTarget).first()).toHaveText('Location');
  await expect(page.getByTestId(TESTIDS.rowAction).first()).toHaveText('Created');
}

async function enterAuditLogs(page: Page): Promise<void> {
  await page.goto(START_PATH);
  await expect(page).toHaveURL(START_PATH);
  await enterViaNav(page, AUDIT_LOGS_PATH);
  await expect(page).toHaveURL(AUDIT_LOGS_PATH);
  await expect(page.getByTestId(TESTIDS.table)).toBeVisible();
}

test.describe('Audit-log viewer — two-club tenant isolation (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let fixture: TwoClubFixture;
  let baseURL: string;

  test.beforeAll(async ({ browser }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    fixture = await provisionTwoClubs(browser, baseURL, 'audit');
  });

  test.afterAll(async () => {
    await fixture?.dispose();
  });

  test('[happy] club-A admin: the audit row for a real write names an actor, and every row on the page names one', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    const nonce = randomUuid().slice(0, 8);
    const attributedLocationName = `Audit attribution ${nonce}`;
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      await createLocationViaUi(page, { name: attributedLocationName });

      await enterAuditLogs(page);
      await filterToCreatedLocations(page);

      const newestCreateRow = page.getByTestId(TESTIDS.row).first();
      await expect(newestCreateRow).toBeVisible();
      await expect(
        newestCreateRow.getByTestId(TESTIDS.rowActor),
        'the row for this write must name the actor who made it',
      ).not.toHaveText('');

      const actorCells = page.getByTestId(TESTIDS.rowActor);
      const actorCellCount = await actorCells.count();
      expect(
        actorCellCount,
        'the CREATE + Location filter must leave at least one row to attribute',
      ).toBeGreaterThan(0);
      for (let i = 0; i < actorCellCount; i++) {
        await expect(
          actorCells.nth(i),
          'every audit row names an actor or names the system',
        ).not.toHaveText('');
      }

      await newestCreateRow.click();
      await expect(page.getByTestId(TESTIDS.rowDetail)).toContainText(attributedLocationName);

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-audit-actor-attribution.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-32',
        caption:
          'J-32 · actor attribution · A club-A administrator creates a Location in the real ' +
          'application. At /system/logs the row for that write names an actor, and each row on ' +
          'the page names an actor. The expanded row carries the created name.',
        acTag: 'happy',
      });
    }
  });

  test('[edge] club-A admin: expanding an audit row renders the field-level snapshot table', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    const nonce = randomUuid().slice(0, 8);
    const snapshotLocationName = `Audit snapshot ${nonce}`;
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      await createLocationViaUi(page, { name: snapshotLocationName });

      await enterAuditLogs(page);
      await filterToCreatedLocations(page);

      const newestCreateRow = page.getByTestId(TESTIDS.row).first();
      await expect(newestCreateRow).toBeVisible();
      await newestCreateRow.click();

      const detail = page.getByTestId(TESTIDS.rowDetail);
      await expect(detail).toBeVisible();
      await expect(detail).toContainText(snapshotLocationName);

      const snapshotFieldRows = detail.locator('tbody tr');
      await expect(snapshotFieldRows.first()).toBeVisible();
      expect(
        await snapshotFieldRows.count(),
        'the expanded row must render one line for each field of the written entity',
      ).toBeGreaterThan(1);

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-audit-row-detail.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-32',
        caption:
          'J-32 · audit row detail · A club-A administrator creates a Location in the real ' +
          'application. At /system/logs the expanded row shows the field-level snapshot table, ' +
          'with one line for each field of the written Location.',
        acTag: 'edge',
      });
    }
  });

  test('[happy] club-A admin: a real create+edit surfaces at /system/logs with action, target, actor, time, status; filters + row-detail diff', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    const nonce = randomUuid().slice(0, 8);
    const clubALocationName = `Audit A ${nonce}`;
    const editedName = `Audit A edited ${nonce}`;
    const aircraftImmat = `AU-${nonce.toUpperCase()}`;
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      const locId = await createLocationViaUi(page, { name: clubALocationName });
      await renameLocationViaUi(page, locId, editedName);

      await createAircraftViaUi(page, aircraftImmat);

      await enterAuditLogs(page);

      const locationRow = auditRowsWithTarget(page, 'Location').first();
      await expect(locationRow).toBeVisible();
      await expect(locationRow.getByTestId(TESTIDS.rowAction)).toBeVisible();
      await expect(locationRow.getByTestId(TESTIDS.rowTarget)).toHaveText('Location');
      await expect(locationRow.getByTestId(TESTIDS.rowActor)).toBeVisible();
      await expect(locationRow.getByTestId(TESTIDS.rowStatus)).toHaveText(
        SUCCESS_ROW_HTTP_STATUS_PLACEHOLDER,
      );
      await expect(locationRow.getByTestId(TESTIDS.rowTime)).toBeVisible();

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-audit-logs-list.png`,
        fullPage: true,
      });

      await selectAfOption(page, TESTIDS.filterAction, 'UPDATE');
      await expect(page.getByTestId(TESTIDS.table)).toBeVisible();
      const updateRows = page.getByTestId(TESTIDS.row);
      await expect(updateRows.first()).toBeVisible();
      const updateActionCells = page.getByTestId(TESTIDS.rowAction);
      const updateCount = await updateActionCells.count();
      for (let i = 0; i < updateCount; i++) {
        await expect(updateActionCells.nth(i)).toHaveText('Updated');
      }

      const aircraftTargetRows = () =>
        page
          .getByTestId(TESTIDS.rowTarget)
          .filter({ has: page.getByText('Aircraft', { exact: true }) });

      await page.getByTestId(TESTIDS.clearFilters).click();
      await expect(page.getByTestId(TESTIDS.table)).toBeVisible();

      await page.getByTestId(TESTIDS.filterTarget).locator('input').fill('Aircraft');
      await expect(page.getByTestId(TESTIDS.rowTarget).first()).toHaveText('Aircraft');
      await expect(
        aircraftTargetRows().first(),
        'the seeded Aircraft audit row must exist under a targetEntityType=Aircraft filter',
      ).toBeVisible();

      await page.getByTestId(TESTIDS.filterTarget).locator('input').fill('Location');
      await expect(page.getByTestId(TESTIDS.rowTarget).first()).toHaveText('Location');
      const locationTargets = page.getByTestId(TESTIDS.rowTarget);
      const locationCount = await locationTargets.count();
      for (let i = 0; i < locationCount; i++) {
        await expect(locationTargets.nth(i)).toHaveText('Location');
      }
      await expect(
        aircraftTargetRows(),
        'a targetEntityType=Location filter must EXCLUDE the seeded Aircraft row',
      ).toHaveCount(0);

      const narrowedRows = await page.getByTestId(TESTIDS.row).count();
      await page.getByTestId(TESTIDS.clearFilters).click();
      await expect(page.getByTestId(TESTIDS.table)).toBeVisible();
      await expect
        .poll(async () => page.getByTestId(TESTIDS.row).count(), { timeout: 15_000 })
        .toBeGreaterThanOrEqual(narrowedRows);
      await expect(page.getByTestId(TESTIDS.rowAction).first()).toBeVisible();

      await selectAfOption(page, TESTIDS.filterAction, 'UPDATE');
      await page.getByTestId(TESTIDS.filterTarget).locator('input').fill('Location');
      await expect(page.getByTestId(TESTIDS.rowTarget).first()).toHaveText('Location');
      await expect(page.getByTestId(TESTIDS.row).first()).toBeVisible();
      const locationUpdateRows = page.getByTestId(TESTIDS.row);
      const locationUpdateRowCount = await locationUpdateRows.count();
      const detail = page.getByTestId(TESTIDS.rowDetail);
      let foundRowCarryingThisRenamesEditedName = false;
      for (let i = 0; i < locationUpdateRowCount; i++) {
        await locationUpdateRows.nth(i).click();
        await expect(detail).toBeVisible();
        if (((await detail.textContent()) ?? '').includes(editedName)) {
          foundRowCarryingThisRenamesEditedName = true;
          break;
        }
        await locationUpdateRows.nth(i).click();
      }
      expect(
        foundRowCarryingThisRenamesEditedName,
        `an UPDATE/Location audit row must carry this rename's editedName`,
      ).toBe(true);
      await expect(detail).toContainText(editedName);
      await expect(detail).toContainText(clubALocationName);

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-audit-logs-form.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-30',
        caption:
          'J-30 · audit filter genuinely excludes · a club-A admin creates a Location AND an Aircraft ' +
          'through the real chrome, then at /system/logs filters targetEntityType=Location: the Location ' +
          'row is present and the Aircraft row is ABSENT (adversarially seeded — the excluded row is ' +
          'created and asserted absent, not merely "the visible rows are Location"); expanding the UPDATE ' +
          'row shows the real before→after field diff',
        acTag: 'happy',
      });
    }
  });

  test('[happy] time-range filter narrows to events in range', async ({ browser }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      await enterAuditLogs(page);

      await expect(page.getByTestId(TESTIDS.row).first()).toBeVisible();

      const req = page.waitForRequest(
        (r) =>
          new URL(r.url()).pathname === '/api/v1/admin/audit-events' &&
          new URL(r.url()).searchParams.has('occurredFrom'),
      );
      await pickDate(page, TESTIDS.filterFrom, tomorrowDisplay());
      const issued = await req;
      expect(new URL(issued.url()).searchParams.get('occurredFrom')).toBeTruthy();
      await expect(page.getByTestId(TESTIDS.empty)).toBeVisible();
      await expect(page.getByTestId(TESTIDS.row)).toHaveCount(0);

      await page.getByTestId(TESTIDS.clearFilters).click();
      await expect(page.getByTestId(TESTIDS.row).first()).toBeVisible();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-13',
        caption:
          'J-13 · time-range filter · a from-date bound issues a REAL occurredFrom request and narrows ' +
          'the audit list to events in range (a future bound empties it); clearing restores the list',
        acTag: 'happy',
      });
    }
  });

  test('[happy] pagination — default page size 50; advancing the cursor fetches the next offset', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      const bearer = await bearerFor(page);
      await seedAuditEvents(ctx.request, bearer, AUDIT_PAGE_SIZE + 5);

      await enterAuditLogs(page);

      await expect(page.getByTestId(TESTIDS.row).first()).toBeVisible();
      const firstPageCount = await page.getByTestId(TESTIDS.row).count();
      expect(firstPageCount, 'default page renders at most pageSize=50 rows').toBeLessThanOrEqual(
        AUDIT_PAGE_SIZE,
      );
      expect(firstPageCount, 'seed guarantees a full first page').toBe(AUDIT_PAGE_SIZE);
      const pagerNext = page.getByTestId(TESTIDS.pagerNext).locator('button');
      await expect(pagerNext).toBeEnabled();
      await expect(page.getByTestId(TESTIDS.pagerPrev).locator('button')).toBeDisabled();
      const offsetBefore = (await page.getByTestId(TESTIDS.pagerOffset).textContent())?.trim();

      const statusCells = page.getByTestId(TESTIDS.rowStatus);
      await expect(statusCells.first()).toHaveText('404');

      const nextReq = page.waitForRequest(
        (r) =>
          new URL(r.url()).pathname === '/api/v1/admin/audit-events' &&
          new URL(r.url()).searchParams.get('pageOffset') === String(AUDIT_PAGE_SIZE),
      );
      await pagerNext.click();
      await nextReq;
      await expect(page.getByTestId(TESTIDS.row).first()).toBeVisible();
      await expect(page.getByTestId(TESTIDS.pagerPrev).locator('button')).toBeEnabled();
      await expect
        .poll(async () => (await page.getByTestId(TESTIDS.pagerOffset).textContent())?.trim())
        .not.toBe(offsetBefore);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-13',
        caption:
          'J-13 · pagination · with >50 real audit events the default page renders 50 rows, the Next ' +
          'pager is enabled (hasMore) and advancing issues a REAL pageOffset=50 request that fetches ' +
          'the next page (nextOffset cursor)',
        acTag: 'happy',
      });
    }
  });

  test('[edge] tenant isolation — a club-A admin never sees a club-B audit event', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    const nonce = randomUuid().slice(0, 8);
    const clubBTargetName = `Audit B ${nonce}`;
    try {
      await loginAsClubAdmin(page, fixture.clubB);
      const bLocId = await createLocationViaUi(page, { name: clubBTargetName });
      expect(bLocId).toBeTruthy();
    } finally {
      await page.close();
    }

    const ctxA = await newRecordedContext(browser, baseURL, testInfo);
    const pageA = await ctxA.newPage();
    try {
      await loginAsClubAdmin(pageA, fixture.clubA);
      await enterAuditLogs(pageA);

      await selectAfOption(pageA, TESTIDS.filterAction, 'CREATE');
      await pageA.getByTestId(TESTIDS.filterTarget).locator('input').fill('Location');
      await expect(pageA.getByTestId(TESTIDS.table)).toBeVisible();
      await expect(pageA.getByTestId(TESTIDS.row).filter({ hasText: clubBTargetName })).toHaveCount(
        0,
      );

      const bearerA = await bearerFor(pageA);
      const res = await ctxA.request.get('/api/v1/admin/audit-events?pageSize=200', {
        headers: { authorization: bearerA },
      });
      expect(res.status(), await res.text()).toBe(200);
      const body = (await res.json()) as { items: { tenantClubId?: string }[] };
      for (const item of body.items) {
        expect(item.tenantClubId, 'club A read must never surface a club-B row').not.toBe(
          fixture.clubB.clubId,
        );
      }
    } finally {
      await ctxA.close();
      await ctx.close();
      await proofVideo(pageA, testInfo, {
        journey: 'J-32',
        caption:
          'J-32 · tenant isolation · Club B writes a Location CREATE audit event. The club-A ' +
          'administrator does not see that event at /system/logs. A direct API read with the ' +
          'real club-A token returns no club-B row.',
        acTag: 'edge',
      });
    }
  });

  test('[key-error] a plain PILOT is denied /system/logs — guard redirect, nav entry absent, endpoint 403', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      allowConsoleErrors(testInfo, /Failed to load resource.*audit-events.*403/i, /\b403\b/);

      await loginAsSeeded(page, PILOT_USER, PILOT_PASSWORD);
      await page.waitForURL(/\/start$/, { timeout: 30_000 });

      await page.goto(AUDIT_LOGS_PATH);
      await expect(page).not.toHaveURL(new RegExp(`${AUDIT_LOGS_PATH}$`));
      await expect(page.getByTestId(TESTIDS.table)).toHaveCount(0);

      const masterdata = page.getByTestId('af-nav-group-masterdata');
      await expect(masterdata, 'a pilot still sees the Masterdata nav group').toBeVisible();
      await masterdata.click();
      await expect(
        page.getByTestId('af-nav-section-/aircraft'),
        'a pilot-visible Masterdata child renders once the group is open',
      ).toBeVisible();
      await expect(
        page.getByTestId(`af-nav-section-${AUDIT_LOGS_PATH}`),
        'a pilot has no Audit-logs entry under the opened Masterdata group',
      ).toHaveCount(0);
      await page.keyboard.press('Escape');

      const bearer = await bearerFor(page);
      const res = await ctx.request.get('/api/v1/admin/audit-events', {
        headers: { authorization: bearer },
      });
      expect(res.status(), 'GET /api/v1/admin/audit-events must 403 a plain PILOT').toBe(403);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-13',
        caption:
          'J-13 · pilot denied · a real low-privilege PILOT is redirected off /system/logs by the ' +
          'clubAdminGuard, has no Audit-logs nav entry, and GET /api/v1/admin/audit-events returns 403',
        acTag: 'key-error',
      });
    }
  });
});

async function loginAsSeeded(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, username, password);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
}

async function pickDate(page: Page, testId: string, displayDate: string): Promise<void> {
  const input = page.getByTestId(testId).locator('input').first();
  await input.click();
  await input.fill(displayDate);
  await input.press('Enter');
}

function tomorrowDisplay(): string {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  const dd = String(d.getDate()).padStart(2, '0');
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const yyyy = d.getFullYear();
  return `${dd}.${mm}.${yyyy}`;
}
