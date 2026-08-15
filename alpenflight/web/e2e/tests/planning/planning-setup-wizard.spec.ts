import { type Page, type Route } from '@playwright/test';
import { expect, test } from '../_helpers/console-guard';

import { selectAfOption } from '../_helpers/af-select';

const LOCATION_BERN_ID = '019e30c3-2c00-7001-8000-00000000c001';

const mockLocationsPicker = [{ id: LOCATION_BERN_ID, locationName: 'Bern-Belp', icaoCode: 'LSZB' }];

function dayKeyFromToday(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

interface RuleRequest {
  startDate: string;
  endDate: string;
  locationId: string;
  everyMonday: boolean;
  everyTuesday: boolean;
  everyWednesday: boolean;
  everyThursday: boolean;
  everyFriday: boolean;
  everySaturday: boolean;
  everySunday: boolean;
}

function selectsWeekday(rule: RuleRequest, sundayZeroDayOfWeek: number): boolean {
  return [
    rule.everySunday,
    rule.everyMonday,
    rule.everyTuesday,
    rule.everyWednesday,
    rule.everyThursday,
    rule.everyFriday,
    rule.everySaturday,
  ][sundayZeroDayOfWeek]!;
}

function setupRuleBackend(existing: Set<string>) {
  let nextId = 2000;
  return async (route: Route) => {
    const req = route.request();
    const url = new URL(req.url());
    if (req.method() !== 'POST' || url.pathname !== '/api/v1/planning-days/create/rule') {
      await route.fallback();
      return;
    }
    const rule = req.postDataJSON() as RuleRequest;
    const created: { id: string; date: string; locationId: string }[] = [];
    const start = new Date(`${rule.startDate}T00:00:00`);
    const end = new Date(`${rule.endDate}T00:00:00`);
    for (let d = new Date(start); d <= end; d.setDate(d.getDate() + 1)) {
      if (!selectsWeekday(rule, d.getDay())) continue;
      const y = d.getFullYear();
      const m = String(d.getMonth() + 1).padStart(2, '0');
      const day = String(d.getDate()).padStart(2, '0');
      const key = `${y}-${m}-${day}`;
      const dedupKey = `${key}|${rule.locationId}`;
      if (existing.has(dedupKey)) continue;
      existing.add(dedupKey);
      created.push({
        id: `019e30c3-2c00-7001-8000-${String(nextId++).padStart(12, '0')}`,
        date: key,
        locationId: rule.locationId,
      });
    }
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify(created),
    });
  };
}

async function stubReadsForkJoinedWithTheLocationPicker(page: Page): Promise<void> {
  await page.route('**/api/v1/persons', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
  await page.route('**/api/v1/aircraft/picker', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
}

async function wireWizard(page: Page, existing = new Set<string>()): Promise<void> {
  await page.route('**/api/v1/locations', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockLocationsPicker),
    }),
  );
  await stubReadsForkJoinedWithTheLocationPicker(page);
  await page.route('**/api/v1/planning-days/create/rule', setupRuleBackend(existing));
}

async function gotoDe(page: Page, path: string): Promise<void> {
  const sep = path.includes('?') ? '&' : '?';
  await page.goto(`${path}${sep}lang=de`);
}

async function checkWeekdays(page: Page, days: string[]): Promise<void> {
  for (const d of days) {
    await page.getByTestId(`planning-setup-weekday-${d}`).locator('input').check();
  }
}

async function uncheckAllWeekdays(page: Page): Promise<void> {
  for (const d of ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun']) {
    await page.getByTestId(`planning-setup-weekday-${d}`).locator('input').uncheck();
  }
}

test.describe('J-6 planning setup wizard (mock-auth inner loop)', () => {
  test('wizard: generating Sat+Sun across a range bulk-creates the matching days and routes back to the list', async ({
    page,
  }) => {
    await wireWizard(page);

    await gotoDe(page, '/planningsetup');
    await expect(page.getByTestId('planning-setup-form')).toBeVisible();

    await page.getByTestId('planning-setup-start').locator('input').fill(dayKeyFromToday(1));
    await page.getByTestId('planning-setup-end').locator('input').fill(dayKeyFromToday(21));
    await checkWeekdays(page, ['sat', 'sun']);
    await selectAfOption(page, 'planning-setup-location-select', LOCATION_BERN_ID);

    const generated = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname === '/api/v1/planning-days/create/rule' &&
        r.status() === 201,
    );
    await page.getByTestId('planning-setup-generate-button').click();
    const res = await generated;
    const sentRule = res.request().postDataJSON() as RuleRequest;
    expect(sentRule.everySaturday).toBe(true);
    expect(sentRule.everySunday).toBe(true);
    expect(sentRule.everyMonday).toBe(false);
    const createdDays = (await res.json()) as { date: string }[];
    for (const cd of createdDays) {
      const dow = new Date(`${cd.date}T00:00:00`).getDay();
      expect([0, 6]).toContain(dow);
    }
    expect(createdDays.length).toBeGreaterThan(0);

    await expect(page).toHaveURL('/planning');
    await page.screenshot({
      path: 'screenshots/planning/04-setup-generated.png',
      fullPage: true,
    });
  });

  test('wizard: generating with no weekday checked creates zero days (no error)', async ({
    page,
  }) => {
    await wireWizard(page);

    await gotoDe(page, '/planningsetup');
    await uncheckAllWeekdays(page);
    await page.getByTestId('planning-setup-start').locator('input').fill(dayKeyFromToday(1));
    await page.getByTestId('planning-setup-end').locator('input').fill(dayKeyFromToday(21));
    await selectAfOption(page, 'planning-setup-location-select', LOCATION_BERN_ID);

    const generated = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname === '/api/v1/planning-days/create/rule' &&
        r.status() === 201,
    );
    await page.getByTestId('planning-setup-generate-button').click();
    const res = await generated;
    const sentRule = res.request().postDataJSON() as RuleRequest;
    expect(sentRule.everySaturday).toBe(false);
    expect(sentRule.everySunday).toBe(false);
    const createdDays = (await res.json()) as unknown[];
    expect(createdDays).toHaveLength(0);
  });

  test.fixme('wizard: re-running over an already-populated range skips the existing days idempotently', async ({
    page,
  }) => {
    const existing = new Set<string>();
    await wireWizard(page, existing);

    await gotoDe(page, '/planningsetup');
    await page.getByTestId('planning-setup-start').locator('input').fill(dayKeyFromToday(1));
    await page.getByTestId('planning-setup-end').locator('input').fill(dayKeyFromToday(21));
    await checkWeekdays(page, ['sat', 'sun']);
    await selectAfOption(page, 'planning-setup-location-select', LOCATION_BERN_ID);
    const first = page.waitForResponse(
      (r) =>
        new URL(r.url()).pathname === '/api/v1/planning-days/create/rule' && r.status() === 201,
    );
    await page.getByTestId('planning-setup-generate-button').click();
    const firstCount = ((await (await first).json()) as unknown[]).length;
    expect(firstCount).toBeGreaterThan(0);

    await gotoDe(page, '/planningsetup');
    await page.getByTestId('planning-setup-start').locator('input').fill(dayKeyFromToday(1));
    await page.getByTestId('planning-setup-end').locator('input').fill(dayKeyFromToday(21));
    await checkWeekdays(page, ['sat', 'sun']);
    await selectAfOption(page, 'planning-setup-location-select', LOCATION_BERN_ID);
    const second = page.waitForResponse(
      (r) =>
        new URL(r.url()).pathname === '/api/v1/planning-days/create/rule' && r.status() === 201,
    );
    await page.getByTestId('planning-setup-generate-button').click();
    const secondCount = ((await (await second).json()) as unknown[]).length;
    expect(secondCount, 'a second pass over the same range creates no new days').toBe(0);
  });

  test('wizard: cancel returns to the planning list without generating days', async ({ page }) => {
    await wireWizard(page);

    await gotoDe(page, '/planningsetup');
    await page.getByTestId('planning-setup-cancel-button').click();
    await expect(page).toHaveURL('/planning');
  });
});
