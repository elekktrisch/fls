import { expect, test, type Page, type Route } from '@playwright/test';

import { selectAfOption } from '../_helpers/af-select';

/**
 * Planning-day SETUP WIZARD — J-6 INNER-LOOP spec STUB (T-01).
 *
 * SPEC SKELETON ONLY (J-6 T-01). The `/planningsetup` screen + the rule-expand
 * endpoint land in T-05 (backend) + T-09 (SPA wizard); T-16 un-fixmes + thickens
 * these from the behavior oracle. The page-driving cases are `test.fixme`
 * (parsed + typechecked, NOT run) — the deliverable is the screen shape
 * (selectors + flow + thin asserts) + the wired mock backend so un-fixme is a
 * one-line flip. Mirrors the J-5 reservations inner-loop discipline.
 *
 * ── SCREEN SHAPE (from J-6 "Spec must assert" + the behavior oracle) ─────────
 *   WIZARD `/planningsetup` — StartDate, EndDate, seven weekday checkboxes
 *     (Mon..Sun), location select (defaults to the club's HomebaseId), a
 *     "Generate Planning Days" button + cancel. POSTs
 *     `/api/v1/planningdays/create/rule` → the array of created day overviews,
 *     then routes back to `/planning`.
 *   Rule-expand semantics (oracle): inclusive date range, ONE day per matching
 *     weekday, same location, NO default crew; empty weekday flags → empty list,
 *     no error; existing (date, location) days are SKIPPED idempotently (V4
 *     unique correcting legacy's no-dedup); an absurd/unbounded range is rejected.
 */

const LOCATION_BERN_ID = '019e30c3-2c00-7001-8000-00000000c001';

const mockLocationsPicker = [{ id: LOCATION_BERN_ID, locationName: 'Bern-Belp', icaoCode: 'LSZB' }];

/** `YYYY-MM-DD` `days` days from local today. */
function dayKeyFromToday(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

/** The wire request shape the wizard POSTs to `/planningdays/create/rule`. */
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

/** 0=Sun..6=Sat → whether the rule selects that weekday. */
function selectsWeekday(rule: RuleRequest, dow: number): boolean {
  return [
    rule.everySunday,
    rule.everyMonday,
    rule.everyTuesday,
    rule.everyWednesday,
    rule.everyThursday,
    rule.everyFriday,
    rule.everySaturday,
  ][dow]!;
}

/**
 * Mock the rule-expand endpoint: inclusive [startDate, endDate], one created day
 * per matching weekday, skipping any (date, location) already present. Returns
 * the created-day overview array (the wire contract the wizard reads to count).
 */
function setupRuleBackend(existing: Set<string>) {
  let nextId = 2000;
  return async (route: Route) => {
    const req = route.request();
    const url = new URL(req.url());
    if (req.method() !== 'POST' || url.pathname !== '/api/v1/planningdays/create/rule') {
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
      if (existing.has(dedupKey)) continue; // skip-existing idempotent
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

async function wireWizard(page: Page, existing = new Set<string>()): Promise<void> {
  await page.route('**/api/v1/locations', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockLocationsPicker),
    }),
  );
  await page.route('**/api/v1/planningdays/create/rule', setupRuleBackend(existing));
}

async function gotoDe(page: Page, path: string): Promise<void> {
  const sep = path.includes('?') ? '&' : '?';
  await page.goto(`${path}${sep}lang=de`);
}

/** Tick the given weekday checkboxes (`mon`..`sun`) on the wizard. */
async function checkWeekdays(page: Page, days: string[]): Promise<void> {
  for (const d of days) {
    await page.getByTestId(`planning-setup-weekday-${d}`).locator('input').check();
  }
}

// ── setup-wizard suite — drives the (not-yet-built) /planningsetup screen ─────
// `test.fixme` until T-09 lands the wizard + T-16 thickens from the oracle.
test.describe('J-6 planning setup wizard (mock-auth inner loop)', () => {
  // ── AC[happy]: the wizard bulk-creates days across a range filtered by weekday
  test.fixme('wizard: generating Sat+Sun across a range bulk-creates the matching days and routes back to the list', async ({
    page,
  }) => {
    await wireWizard(page);

    await gotoDe(page, '/planningsetup');
    await expect(page.getByTestId('planning-setup-form')).toBeVisible();

    // A ~3-week range, every Saturday + Sunday, at Bern.
    await page.getByTestId('planning-setup-start').locator('input').fill(dayKeyFromToday(1));
    await page.getByTestId('planning-setup-end').locator('input').fill(dayKeyFromToday(21));
    await checkWeekdays(page, ['sat', 'sun']);
    await selectAfOption(page, 'planning-setup-location-select', LOCATION_BERN_ID);

    // The wizard POSTs the rule + reads the created-day array to count.
    const generated = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname === '/api/v1/planningdays/create/rule' &&
        r.status() === 201,
    );
    await page.getByTestId('planning-setup-generate-button').click();
    const res = await generated;
    const sentRule = res.request().postDataJSON() as RuleRequest;
    expect(sentRule.everySaturday).toBe(true);
    expect(sentRule.everySunday).toBe(true);
    expect(sentRule.everyMonday).toBe(false);
    const createdDays = (await res.json()) as { date: string }[];
    // Every created day is a Sat or Sun (the rule-expand weekday filter).
    for (const cd of createdDays) {
      const dow = new Date(`${cd.date}T00:00:00`).getDay();
      expect([0, 6]).toContain(dow);
    }
    expect(createdDays.length).toBeGreaterThan(0);

    // The wizard routes back to the list, where the generated days show.
    await expect(page).toHaveURL('/planning');
    await page.screenshot({
      path: 'screenshots/planning/04-setup-generated.png',
      fullPage: true,
    });
  });

  // ── AC[edge]: empty weekday flags → no days created, no error ───────────────
  test.fixme('wizard: generating with no weekday checked creates zero days (no error)', async ({
    page,
  }) => {
    await wireWizard(page);

    await gotoDe(page, '/planningsetup');
    await page.getByTestId('planning-setup-start').locator('input').fill(dayKeyFromToday(1));
    await page.getByTestId('planning-setup-end').locator('input').fill(dayKeyFromToday(21));
    await selectAfOption(page, 'planning-setup-location-select', LOCATION_BERN_ID);

    const generated = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname === '/api/v1/planningdays/create/rule' &&
        r.status() === 201,
    );
    await page.getByTestId('planning-setup-generate-button').click();
    const res = await generated;
    const createdDays = (await res.json()) as unknown[];
    expect(createdDays).toHaveLength(0);
  });

  // ── AC[key-error]: re-running the wizard SKIPS existing (date, location) days —
  //    idempotent, correcting legacy's no-dedup bug ────────────────────────────
  test.fixme('wizard: re-running over an already-populated range skips the existing days idempotently', async ({
    page,
  }) => {
    // Pre-populate the existing-day set so the second pass dedups.
    const existing = new Set<string>();
    await wireWizard(page, existing);

    await gotoDe(page, '/planningsetup');
    // First pass: generate, recording the days into `existing`.
    await page.getByTestId('planning-setup-start').locator('input').fill(dayKeyFromToday(1));
    await page.getByTestId('planning-setup-end').locator('input').fill(dayKeyFromToday(21));
    await checkWeekdays(page, ['sat', 'sun']);
    await selectAfOption(page, 'planning-setup-location-select', LOCATION_BERN_ID);
    const first = page.waitForResponse(
      (r) => new URL(r.url()).pathname === '/api/v1/planningdays/create/rule' && r.status() === 201,
    );
    await page.getByTestId('planning-setup-generate-button').click();
    const firstCount = ((await (await first).json()) as unknown[]).length;
    expect(firstCount).toBeGreaterThan(0);

    // Second pass over the SAME range → all days already exist → zero created.
    await gotoDe(page, '/planningsetup');
    await page.getByTestId('planning-setup-start').locator('input').fill(dayKeyFromToday(1));
    await page.getByTestId('planning-setup-end').locator('input').fill(dayKeyFromToday(21));
    await checkWeekdays(page, ['sat', 'sun']);
    await selectAfOption(page, 'planning-setup-location-select', LOCATION_BERN_ID);
    const second = page.waitForResponse(
      (r) => new URL(r.url()).pathname === '/api/v1/planningdays/create/rule' && r.status() === 201,
    );
    await page.getByTestId('planning-setup-generate-button').click();
    const secondCount = ((await (await second).json()) as unknown[]).length;
    expect(secondCount, 'a second pass over the same range creates no new days').toBe(0);
  });

  // ── AC: cancel returns to the list without creating anything ────────────────
  test.fixme('wizard: cancel returns to the planning list without generating days', async ({
    page,
  }) => {
    await wireWizard(page);

    await gotoDe(page, '/planningsetup');
    await page.getByTestId('planning-setup-cancel-button').click();
    await expect(page).toHaveURL('/planning');
  });
});
