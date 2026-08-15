
import { expect, gotoRoute, screenshot, test } from '../../fixtures';
import type { Page } from '@playwright/test';

const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';
const SETTINGS_KEY = 'AircraftIdsToDisplayInScheduler';

async function getBearerToken(page: Page): Promise<string> {
  const token = await page.evaluate(() => {
    const raw = sessionStorage.getItem('ngStorage-loginResult');
    if (!raw) return null;
    try { return JSON.parse(raw).access_token as string; } catch { return null; }
  });
  expect(token, 'expected access_token in sessionStorage from loggedInPage').toBeTruthy();
  return token!;
}

async function getCurrentUserId(page: Page): Promise<string> {
  const userId = await page.evaluate(() => {
    const raw = sessionStorage.getItem('ngStorage-user');
    if (!raw) return null;
    try { return JSON.parse(raw).UserId as string; } catch { return null; }
  });
  expect(userId, 'expected UserId in sessionStorage from loggedInPage').toBeTruthy();
  return userId!;
}

async function waitForSchedulerLoaded(page: Page): Promise<void> {
  await page.waitForFunction(
    () => document.querySelectorAll('.cssload-loader').length === 0,
    undefined,
    { timeout: 30_000 },
  );
}

test.skip('reservation-scheduler renders aircraft row, headers, and a seeded event', async ({
  loggedInPage,
}) => {

  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  loggedInPage.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(msg.text());
  });
  loggedInPage.on('pageerror', (err) => pageErrors.push(err.message));

  const token = await getBearerToken(loggedInPage);
  const userId = await getCurrentUserId(loggedInPage);
  const authHeader = { Authorization: `Bearer ${token}` };

  const overviewRes = await loggedInPage.request.get(`${API_BASE}/api/v1/aircrafts/overview`, { headers: authHeader });
  expect(overviewRes.ok(), `aircrafts/overview -> ${overviewRes.status()}`).toBeTruthy();
  const aircrafts = (await overviewRes.json()) as Array<{ AircraftId: string; Immatriculation: string }>;
  expect(aircrafts.length, 'expected at least one seeded aircraft').toBeGreaterThan(0);
  const aircraftIds = aircrafts.map((a) => a.AircraftId);
  const expectedImmatriculations = aircrafts.map((a) => a.Immatriculation);

  const saveRes = await loggedInPage.request.post(`${API_BASE}/api/v1/settings`, {
    headers: authHeader,
    data: { UserId: userId, SettingKey: SETTINGS_KEY, SettingValue: JSON.stringify(aircraftIds) },
  });
  expect(saveRes.ok(), `settings POST -> ${saveRes.status()}`).toBeTruthy();

  await gotoRoute(loggedInPage, '/reservation-scheduler');
  await waitForSchedulerLoaded(loggedInPage);

  const container = loggedInPage.locator('.scroll-container .container');
  await expect(container, 'scheduler grid container').toBeVisible();
  const containerWidth = await container.evaluate((el) => (el as HTMLElement).getBoundingClientRect().width);
  expect(containerWidth, 'grid should span multiple days').toBeGreaterThan(500);

  const legendText = (await loggedInPage.locator('.left-header-area svg text').allTextContents()).join(' ');
  const someImmatVisible = expectedImmatriculations.some((im) => legendText.includes(im));
  expect(someImmatVisible, `expected one of ${expectedImmatriculations.join(', ')} in legend: ${legendText}`).toBeTruthy();

  const gridHeaderTexts = await loggedInPage
    .locator('.scroll-container .container svg > text')
    .allTextContents();
  const dayHeaders = gridHeaderTexts.map((s) => s.trim()).filter((s) => /^\d{2}\.\d{2}\.\d{4}$/.test(s));
  expect(dayHeaders.length, `expected several day headers, got: ${JSON.stringify(gridHeaderTexts)}`).toBeGreaterThan(5);

  const eventCount = await loggedInPage.locator('g.event-group').count();
  expect(eventCount, 'expected at least one rendered reservation event').toBeGreaterThan(0);

  expect(pageErrors, `page errors: ${pageErrors.join(' | ')}`).toEqual([]);
  expect(consoleErrors, `console errors: ${consoleErrors.join(' | ')}`).toEqual([]);
  await screenshot(loggedInPage, 'scheduler-01');
});
