import { test, expect, gotoRoute, loginViaUi, waitForLoggedInState, screenshot } from '../../fixtures';
import type { APIRequestContext, Page } from '@playwright/test';

test.use({ video: 'on' });

const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';

const ADMINS = [
  { username: 'testclubadmin', password: 's', label: 'TestClub' },
  { username: 'othertestadmin', password: 's', label: 'OtherClub' },
] as const;

async function myClubId(page: Page): Promise<string> {
  await page.waitForFunction(() => {
    const raw = sessionStorage.getItem('ngStorage-user');
    if (!raw) return false;
    try { return !!JSON.parse(raw)?.myClub?.ClubId; } catch { return false; }
  }, undefined, { timeout: 15_000 });
  const id = await page.evaluate(() => {
    const raw = sessionStorage.getItem('ngStorage-user');
    if (!raw) return null;
    try { return JSON.parse(raw)?.myClub?.ClubId ?? null; } catch { return null; }
  });
  expect(id, 'expected myClub.ClubId in ngStorage-user after UI login').toBeTruthy();
  return id as string;
}

async function bearer(page: Page): Promise<string> {
  const token = await page.evaluate(() => {
    const raw = sessionStorage.getItem('ngStorage-loginResult');
    try { return raw ? (JSON.parse(raw).access_token as string) : null; } catch { return null; }
  });
  expect(token, 'expected access_token in ngStorage-loginResult').toBeTruthy();
  return token as string;
}

async function ensureLocationNameFreeForRetry(page: Page, token: string, name: string): Promise<void> {
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  const listRes = await page.request.post(`${API_BASE}/api/v1/locations/page/0/100`, {
    headers,
    data: { Sorting: {}, SearchFilter: { LocationName: name } },
  });
  if (!listRes.ok()) return;
  const body = (await listRes.json()) as { Items?: { LocationId: string; LocationName: string }[] };
  for (const row of body.Items ?? []) {
    if (row.LocationName !== name) continue;
    await page.request.post(`${API_BASE}/api/v1/locations/${row.LocationId}`, {
      headers: { ...headers, 'X-HTTP-Method-Override': 'DELETE' },
    });
  }
}

async function clubHomebaseId(
  apiOutlivingBrowserContexts: APIRequestContext,
  token: string,
  clubId: string,
): Promise<string> {
  const res = await apiOutlivingBrowserContexts.get(`${API_BASE}/api/v1/clubs/${clubId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(res.ok(), `GET club ${clubId}: ${res.status()}`).toBeTruthy();
  const club = (await res.json()) as { HomebaseId?: string };
  return (club.HomebaseId ?? '').toLowerCase();
}

async function setHomebaseViaScope(page: Page, locationId: string): Promise<void> {
  await page.waitForFunction((id) => {
    const w = window as unknown as {
      angular: { element: (n: Element) => { scope: () => unknown } };
    };
    const form = document.querySelector('form[name="clubForm"]');
    if (!form) return false;
    const s = w.angular.element(form).scope() as {
      md?: { locations?: { LocationId: string }[] };
    };
    const locs = s.md?.locations;
    return Array.isArray(locs) && locs.some((l) => l.LocationId === id);
  }, locationId, { timeout: 15_000 });

  await page.evaluate((id) => {
    const w = window as unknown as {
      angular: { element: (n: Element) => { scope: () => unknown } };
    };
    const form = document.querySelector('form[name="clubForm"]')!;
    const s = w.angular.element(form).scope() as {
      club?: { HomebaseId?: string };
      $apply: (fn?: () => void) => void;
    };
    if (!s.club) return;
    s.club.HomebaseId = id;
    s.$apply();
  }, locationId);
}

async function fillLocationRequiredDropdowns(page: Page): Promise<void> {
  await page.waitForFunction(() => {
    const w = window as unknown as {
      angular: { element: (n: Element) => { scope: () => unknown } };
    };
    const form = document.querySelector('form[name="locationForm"]');
    if (!form) return false;
    const s = w.angular.element(form).scope() as {
      md?: { countries?: unknown[]; locationTypes?: unknown[] };
    };
    return Array.isArray(s.md?.countries) && (s.md?.countries.length ?? 0) > 0
      && Array.isArray(s.md?.locationTypes) && (s.md?.locationTypes.length ?? 0) > 0;
  }, undefined, { timeout: 15_000 });

  await page.evaluate(() => {
    const w = window as unknown as {
      angular: { element: (n: Element) => { scope: () => unknown } };
    };
    const form = document.querySelector('form[name="locationForm"]')!;
    const s = w.angular.element(form).scope() as {
      location?: { CountryId?: string; LocationTypeId?: string };
      md: {
        countries: { CountryId: string; CountryName: string }[];
        locationTypes: { LocationTypeId: string; LocationTypeName: string }[];
      };
      $apply: (fn?: () => void) => void;
    };
    if (!s.location) return;
    s.location.CountryId =
      s.md.countries.find((c) => c.CountryName === 'Schweiz')?.CountryId
      ?? s.md.countries[0].CountryId;
    s.location.LocationTypeId = s.md.locationTypes[0].LocationTypeId;
    s.$apply();
  });
}

test.setTimeout(120_000);

test('J-0c fan-out: legacy Location created + referenced by 2 clubs (parity video)', async ({ browser, playwright }, testInfo) => {
  const pinnedLocationNameFromProofWorkflow = process.env['J0C_LOCATION_NAME'];
  const freshNameSuffix = (
    pinnedLocationNameFromProofWorkflow?.replace(/^J0C-/, '') ??
    Math.random().toString(36).slice(2, 8)
  )
    .toUpperCase()
    .slice(0, 6);
  const LOCATION_NAME = pinnedLocationNameFromProofWorkflow ?? `J0C-${freshNameSuffix}`;
  const legacyIcaoDeterministicallyOutsideTheAlpenFlightPattern = `J0${freshNameSuffix.slice(0, 2)}`;

  const ctxA = await browser.newContext({
    viewport: { width: 1280, height: 800 },
    recordVideo: { dir: testInfo.outputPath('video'), size: { width: 1280, height: 800 } },
  });
  const pageA = await ctxA.newPage();

  const apiOutlivingBrowserContexts = await playwright.request.newContext();

  try {
  await loginViaUi(pageA, ADMINS[0].username, ADMINS[0].password);
  await waitForLoggedInState(pageA);
  const clubAId = await myClubId(pageA);
  const tokenA = await bearer(pageA);

  await ensureLocationNameFreeForRetry(pageA, tokenA, LOCATION_NAME);

  await gotoRoute(pageA, '/masterdata/locations/new');
  await pageA.locator('#LocationName').waitFor({ state: 'visible' });
  await pageA.locator('#LocationName').fill(LOCATION_NAME);
  await pageA.locator('#IcaoCode').fill(legacyIcaoDeterministicallyOutsideTheAlpenFlightPattern);
  await pageA.locator('#Description').fill('J-0c fan-out parity (legacy create)');
  await fillLocationRequiredDropdowns(pageA);

  const locationSubmit = pageA.locator('form[name="locationForm"] button[type="submit"]');
  await expect(locationSubmit).toBeEnabled();
  await locationSubmit.click();
  await pageA.waitForURL('**/#/masterdata/locations', { timeout: 15_000 });
  await pageA.waitForLoadState('domcontentloaded');
  await screenshot(pageA, 'fanout-J0c-01-location-created');

  const authA = { Authorization: `Bearer ${tokenA}`, 'Content-Type': 'application/json' };
  const listRes = await pageA.request.get(`${API_BASE}/api/v1/locations`, { headers: authA });
  expect(
    listRes.ok(),
    `GET /locations: ${listRes.status()}: ${(await listRes.text().catch(() => '')).slice(0, 200)}`,
  ).toBeTruthy();
  const allLocations = (await listRes.json()) as Array<{ LocationId: string; LocationName: string }>;
  const created = allLocations.find((l) => l.LocationName === LOCATION_NAME);
  expect(created, `created Location "${LOCATION_NAME}" should be in the global /locations list`).toBeTruthy();
  const locationId = created!.LocationId;

  await gotoRoute(pageA, `/masterdata/clubs/${clubAId}`);
  await pageA.locator('form[name="clubForm"] #ClubName').waitFor({ state: 'visible' });
  await setHomebaseViaScope(pageA, locationId);
  const clubASubmit = pageA.locator('form[name="clubForm"] button[type="submit"]');
  await expect(clubASubmit).toBeEnabled();
  await clubASubmit.click();
  await pageA.waitForURL(/#\/masterdata\/clubs(?:\?.*)?$/, { timeout: 15_000 });
  await screenshot(pageA, 'fanout-J0c-02-club-a-homebase');

  await ctxA.close();

  const ctxB = await browser.newContext({
    viewport: { width: 1280, height: 800 },
    recordVideo: { dir: testInfo.outputPath('video'), size: { width: 1280, height: 800 } },
  });
  const pageB = await ctxB.newPage();

  await loginViaUi(pageB, ADMINS[1].username, ADMINS[1].password);
  await waitForLoggedInState(pageB);
  const clubBId = await myClubId(pageB);
  const tokenB = await bearer(pageB);
  expect(clubBId, 'the second admin must own a DIFFERENT club').not.toBe(clubAId);

  await gotoRoute(pageB, `/masterdata/clubs/${clubBId}`);
  await pageB.locator('form[name="clubForm"] #ClubName').waitFor({ state: 'visible' });
  await setHomebaseViaScope(pageB, locationId);
  const clubBSubmit = pageB.locator('form[name="clubForm"] button[type="submit"]');
  await expect(clubBSubmit).toBeEnabled();
  await clubBSubmit.click();
  await pageB.waitForURL(/#\/masterdata\/clubs(?:\?.*)?$/, { timeout: 15_000 });
  await screenshot(pageB, 'fanout-J0c-03-club-b-homebase');

  const clubBHomebase = await clubHomebaseId(apiOutlivingBrowserContexts, tokenB, clubBId);
  expect(
    clubBHomebase,
    'OtherClub.HomebaseId should be the new Location after save',
  ).toBe(locationId.toLowerCase());

  const reAuthA = await apiOutlivingBrowserContexts.post(`${API_BASE}/Token`, {
    form: { grant_type: 'password', username: ADMINS[0].username, password: ADMINS[0].password },
  });
  expect(reAuthA.ok(), `re-token A: ${reAuthA.status()}`).toBeTruthy();
  const reTokenA = (await reAuthA.json()).access_token as string;
  const clubAHomebase = await clubHomebaseId(apiOutlivingBrowserContexts, reTokenA, clubAId);
  expect(
    clubAHomebase,
    'TestClub.HomebaseId should be the new Location too — 2 clubs, 1 Location = fan-out trigger',
  ).toBe(locationId.toLowerCase());

  await ctxB.close();
  } finally {
    await apiOutlivingBrowserContexts.dispose();
  }
});
