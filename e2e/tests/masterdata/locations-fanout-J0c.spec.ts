/**
 * J-0c T-04 — LEGACY half of the fan-out migration parity proof.
 *
 * This is the "before" side of the side-by-side parity video. In the legacy
 * flsweb UI it:
 *   1. logs in (as the TestClub admin),
 *   2. creates a Location with a RANDOM unique name (`J0C-<rand>`) via
 *      `masterdata/locations/`,
 *   3. makes that one global Location referenced by TWO clubs — the fan-out
 *      trigger — by setting it as each club's `HomebaseId`,
 *   4. records the whole flow as the legacy parity video.
 *
 * Why this proves the fan-out: legacy shares ONE global `Location` row across
 * clubs. The `LocationMapper` SELECT fans that single row out into N distinct
 * per-club AlpenFlight rows based on the union of references —
 * `Clubs.HomebaseId` ∪ `Flights.Start/LdgLocationId` ∪ `Aircrafts.HomebaseId`
 * (J-0c carve). Pointing 2 clubs' `HomebaseId` at the same Location is the
 * most direct UI path to 2 references, so migration must fan it to 2 rows.
 *
 * Legacy-oracle grounding (verified against flsweb + flsserver + FLSTest seed):
 *   - Location create:  route `#/masterdata/locations/new`
 *     (`LocationsModule.js:49`), form `name="locationForm"`
 *     (`locations-edit.html:10`), text inputs `#LocationName` / `#IcaoCode`
 *     / `#Description` (`location-form-fields.html:6,12,182`), required
 *     selectizes `#locationType` + `#Country` bound to `location.LocationTypeId`
 *     / `location.CountryId` (`location-form-fields.html:18-29,36-47`). Submit
 *     = `form[name="locationForm"] button[type="submit"]`, `ng-disabled=
 *     "locationForm.$invalid || !location.CanUpdateRecord"` (new → true). On
 *     success `save()` → `cancel()` → `$location.path('/masterdata/locations')`
 *     (`LocationsEditController.js:102-123`). Locations are GLOBAL — the list
 *     endpoint has no club filter (`LocationsServices.js:110`), which is the
 *     legacy share mechanism the migration fans out.
 *   - Homebase on a club:  route `#/masterdata/clubs/:id`
 *     (`ClubsEditController.js`), form `name="clubForm"`
 *     (`clubs-edit.html:6`), selectize `#HomebaseId` bound to `club.HomebaseId`
 *     with `valueField: 'LocationId'`, `options="md.locations"`
 *     (`club-form-fields.html:20-29`). Submit = `form[name="clubForm"]
 *     button[type="submit"]`, `ng-disabled="clubForm.$invalid ||
 *     !club.CanUpdateRecord"`. On success → `/masterdata/clubs`
 *     (`ClubsEditController.js:84-102`).
 *   - Two clubs, two admins:  both `testclubadmin` (TestClub) and
 *     `othertestadmin` (OtherClub) are role `ClubAdministrator` only
 *     (`_test-fixture.sql:155` + `4 or 5 Insert Test Data.sql`). A club-admin
 *     can edit ONLY their own club (`CanUpdateRecord` is true only there —
 *     see clubs-crud.spec.ts scope note). So the 2-club reference is built by
 *     logging in as each admin in turn and setting their OWN club's homebase
 *     to the SAME global Location. Password for both is the single letter `s`.
 *
 * Selectize widgets are hostile to clicking (TEST_WRITING.md §6) — set the
 * `$scope`-bound value + `$apply()` directly, exactly as locations-crud.spec.ts
 * does for its Country / LocationType dropdowns.
 *
 * STRUCTURAL STATUS (2026-06-01): the legacy stack (Mono `flsserver` + Node 8
 * `flsweb` + MSSQL) only runs in `nightly` CI and does not run live on the dev
 * box. This spec is authored against the REAL legacy selectors above and is
 * structurally validated (tsc + `playwright test --list` discovers it). Its
 * first LIVE green is T-05's dedicated legacy proof workflow, which brings up
 * the stack, runs this spec, and retains/publishes the video to the gallery.
 */

import { test, expect, gotoRoute, loginViaUi, waitForLoggedInState, screenshot } from '../../fixtures';
import type { APIRequestContext, Page } from '@playwright/test';

// Record the create flow as the legacy parity video regardless of pass/fail.
// T-05's workflow retains this artifact + publishes it to the proof gallery
// (T-06). Authored here so the spec is self-describing; the gate workflow may
// also set it at the project level.
test.use({ video: 'on' });

const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';

// The two seeded club-administrators, each scoped to their OWN club.
// Password is the single letter `s` for both (_test-fixture.sql convention).
const ADMINS = [
  { username: 'testclubadmin', password: 's', label: 'TestClub' },
  { username: 'othertestadmin', password: 's', label: 'OtherClub' },
] as const;

/**
 * Resolve the logged-in admin's own ClubId from the hydrated ngStorage-user.
 *
 * Timing quirk this works around: `waitForLoggedInState` only polls
 * `ngStorage-loginResult` for the `access_token` (the FIRST step of the SPA's
 * login chain). But `myClub` is written by the LAST step. `AuthService.login`
 * (AuthService.js:67-93) is a 4-call promise chain: POST /Token →
 * `storage.loginResult`, GET /users/my → `storage.user`, GET /userroles →
 * `storage.userRoles`, then `Clubs.getMyClub()` (GET /clubs/my) →
 * `storage.user.myClub = club`. So immediately after the token lands,
 * `ngStorage-user` is either still absent (the /users/my write hasn't fired)
 * or present-but-without-`myClub` (the /clubs/my round-trip hasn't resolved),
 * and a single read races to empty — the original "expected myClub.ClubId in
 * ngStorage-user after UI login" failure.
 *
 * The injected-sessionStorage `loggedInPage` fixture (used by the passing
 * locations-crud / clubs-crud specs) never hits this: it seeds a fully-formed
 * `ngStorage-user` (with `myClub` set by fetchAuthData's own /clubs/my call)
 * via addInitScript, so `myClub.ClubId` is present before the first read.
 * Driving the REAL UI login here, we must instead WAIT for the SPA's final
 * digest to write `myClub` — poll sessionStorage, same philosophy as
 * `waitForLoggedInState`. The shape is identical (`myClub.ClubId`), it just
 * lands ~3 async /api round-trips after the token.
 */
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

/** Read the bearer token the SPA persisted, for API-side verification. */
async function bearer(page: Page): Promise<string> {
  const token = await page.evaluate(() => {
    const raw = sessionStorage.getItem('ngStorage-loginResult');
    try { return raw ? (JSON.parse(raw).access_token as string) : null; } catch { return null; }
  });
  expect(token, 'expected access_token in ngStorage-loginResult').toBeTruthy();
  return token as string;
}

/**
 * Drop any leftover Location with this name BEFORE creating it, so the create
 * never collides on `UNIQUE_Locations_LocationName`.
 *
 * Why this matters here (the actual T-11 root cause): the chain pins ONE name
 * via `J0C_LOCATION_NAME` for the whole CI run, and that pin survives Playwright
 * retries. The first attempt creates the Location successfully; if the attempt
 * then fails LATER (e.g. the post-`ctxB.close()` readback raced the context
 * teardown), Playwright retries the WHOLE test against the SAME name — and the
 * legacy server rejects the second INSERT with `Violation of UNIQUE KEY
 * constraint 'UNIQUE_Locations_LocationName'` (legacy server log, run
 * 26790752624: insert J0C-… at 00:34:04 OK, re-insert at 00:34:15 → DB
 * exception → form shows "Failed to insert location" → no navigation →
 * `waitForURL` 15s timeout). So the create looked like a validation problem but
 * was a duplicate-name problem on retry. Mirrors locations-crud.spec.ts's
 * `ensureLocationDeleted` (list-by-name → soft-DELETE via the override header).
 */
async function ensureLocationDeleted(page: Page, token: string, name: string): Promise<void> {
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

/**
 * Read a club's `HomebaseId` from a request context that OUTLIVES the page
 * contexts. The original spec called `pageB.request.post(...)` AFTER
 * `ctxB.close()`, so the readback raced the context teardown and threw
 * "Target page, context or browser has been closed" (run 26790752624, line
 * 326). Using a standalone `playwright.request` context — created up front,
 * disposed in `finally` — decouples the API readback from the browser-context
 * lifecycle entirely.
 */
async function clubHomebaseId(api: APIRequestContext, token: string, clubId: string): Promise<string> {
  const res = await api.get(`${API_BASE}/api/v1/clubs/${clubId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(res.ok(), `GET club ${clubId}: ${res.status()}`).toBeTruthy();
  const club = (await res.json()) as { HomebaseId?: string };
  return (club.HomebaseId ?? '').toLowerCase();
}

/**
 * Wait until the club edit form's `md.locations` master-data list has loaded
 * and contains the target Location, then set `club.HomebaseId` to it via the
 * $scope (selectize is not clickable — TEST_WRITING.md §6).
 */
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

/**
 * Fill the Location create form's two required selectizes (LocationType +
 * Country) via $scope, mirroring locations-crud.spec.ts. Without these the
 * submit stays `ng-disabled` (locationForm.$invalid).
 */
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

// Multi-step (create + two sequential UI logins + two club saves) on the
// Mono/MSSQL legacy stack; give it real headroom like the other masterdata
// flows.
test.setTimeout(120_000);

test('J-0c fan-out: legacy Location created + referenced by 2 clubs (parity video)', async ({ browser, playwright }, testInfo) => {
  // Random unique name = the freshness guarantee: proves data actually flowed
  // through the chain (T-05), not pre-seeded. Kept ICAO-short + uppercase so
  // the IcaoCode field (fixed-width) is happy.
  //
  // T-05 full chain: the proof workflow generates the random name ONCE and
  // pins it via `J0C_LOCATION_NAME`, so the SAME name created here in the
  // legacy UI is the one the AlpenFlight parity spec later asserts on (the
  // workflow forwards it as `J0C_REAL_LOCATION_NAME`). Standalone / inner-loop
  // runs (no env var) fall back to a fresh local random — still a valid
  // freshness guarantee, just not cross-process-pinned.
  const envName = process.env['J0C_LOCATION_NAME'];
  const rand = (envName?.replace(/^J0C-/, '') ?? Math.random().toString(36).slice(2, 8))
    .toUpperCase()
    .slice(0, 6);
  const LOCATION_NAME = envName ?? `J0C-${rand}`;
  const ICAO = `J${rand.slice(0, 3)}`;

  // ---------------------------------------------------------------------------
  // Admin 1 (TestClub): create the global Location + set it as TestClub's
  // homebase. Same browser context throughout so the video is one continuous
  // recording of the create + first reference.
  // ---------------------------------------------------------------------------
  const ctxA = await browser.newContext({
    viewport: { width: 1280, height: 800 },
    recordVideo: { dir: testInfo.outputPath('video'), size: { width: 1280, height: 800 } },
  });
  const pageA = await ctxA.newPage();

  // Standalone API context for the cross-context readback at the end. Created
  // up front and disposed in `finally` so it never races a browser-context
  // close (the original spec read back via `pageB.request` AFTER `ctxB.close()`
  // → "context has been closed").
  const api = await playwright.request.newContext();

  try {
  await loginViaUi(pageA, ADMINS[0].username, ADMINS[0].password);
  await waitForLoggedInState(pageA);
  const clubAId = await myClubId(pageA);
  const tokenA = await bearer(pageA);

  // Idempotency: a pinned `J0C_LOCATION_NAME` survives Playwright retries, and
  // legacy enforces `UNIQUE_Locations_LocationName`. Soft-delete any leftover
  // of this name before the UI create so a retry can't collide (T-11 fix).
  await ensureLocationDeleted(pageA, tokenA, LOCATION_NAME);

  // CREATE the Location via the UI form.
  await gotoRoute(pageA, '/masterdata/locations/new');
  await pageA.locator('#LocationName').waitFor({ state: 'visible' });
  await pageA.locator('#LocationName').fill(LOCATION_NAME);
  await pageA.locator('#IcaoCode').fill(ICAO);
  await pageA.locator('#Description').fill('J-0c fan-out parity (legacy create)');
  await fillLocationRequiredDropdowns(pageA);

  const locationSubmit = pageA.locator('form[name="locationForm"] button[type="submit"]');
  await expect(locationSubmit).toBeEnabled();
  await locationSubmit.click();
  await pageA.waitForURL('**/#/masterdata/locations', { timeout: 15_000 });
  await pageA.waitForLoadState('domcontentloaded');
  await screenshot(pageA, 'fanout-J0c-01-location-created');

  // Resolve the new LocationId via the (global) list endpoint — the same path
  // the migration export reads, and the value we feed both clubs' HomebaseId.
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

  // REFERENCE 1: set the new Location as TestClub's homebase.
  await gotoRoute(pageA, `/masterdata/clubs/${clubAId}`);
  await pageA.locator('form[name="clubForm"] #ClubName').waitFor({ state: 'visible' });
  await setHomebaseViaScope(pageA, locationId);
  const clubASubmit = pageA.locator('form[name="clubForm"] button[type="submit"]');
  await expect(clubASubmit).toBeEnabled();
  await clubASubmit.click();
  await pageA.waitForURL(/#\/masterdata\/clubs(?:\?.*)?$/, { timeout: 15_000 });
  await screenshot(pageA, 'fanout-J0c-02-club-a-homebase');

  await ctxA.close();

  // ---------------------------------------------------------------------------
  // Admin 2 (OtherClub): set the SAME global Location as OtherClub's homebase.
  // Separate context = clean session for the second admin (no shared token).
  // ---------------------------------------------------------------------------
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

  // REFERENCE 2: set the new Location as OtherClub's homebase.
  await gotoRoute(pageB, `/masterdata/clubs/${clubBId}`);
  await pageB.locator('form[name="clubForm"] #ClubName').waitFor({ state: 'visible' });
  await setHomebaseViaScope(pageB, locationId);
  const clubBSubmit = pageB.locator('form[name="clubForm"] button[type="submit"]');
  await expect(clubBSubmit).toBeEnabled();
  await clubBSubmit.click();
  await pageB.waitForURL(/#\/masterdata\/clubs(?:\?.*)?$/, { timeout: 15_000 });
  await screenshot(pageB, 'fanout-J0c-03-club-b-homebase');

  // ---------------------------------------------------------------------------
  // Verify the fan-out trigger holds in the legacy DB: BOTH clubs' HomebaseId
  // now point at the one global Location. This is the exact precondition the
  // migration (T-05) reads to fan it out into 2 distinct per-club rows.
  //
  // CRITICAL: do every API readback through the standalone `api` context (NOT
  // `pageB.request`) and BEFORE `ctxB.close()`. The original spec re-tokened +
  // read back via `pageB.request` AFTER closing `ctxB`, which races the context
  // teardown ("Target page, context or browser has been closed", run
  // 26790752624). `api` outlives both browser contexts and is disposed in the
  // outer `finally`.
  // ---------------------------------------------------------------------------
  const clubBHomebase = await clubHomebaseId(api, tokenB, clubBId);
  expect(
    clubBHomebase,
    'OtherClub.HomebaseId should be the new Location after save',
  ).toBe(locationId.toLowerCase());

  // Re-check club A from a fresh token so the assertion that BOTH reference the
  // same Location is airtight (the row that drives the 2-way fan-out). tokenA
  // came from ctxA (now closed) — mint a fresh one on the standalone context.
  const reAuthA = await api.post(`${API_BASE}/Token`, {
    form: { grant_type: 'password', username: ADMINS[0].username, password: ADMINS[0].password },
  });
  expect(reAuthA.ok(), `re-token A: ${reAuthA.status()}`).toBeTruthy();
  const reTokenA = (await reAuthA.json()).access_token as string;
  const clubAHomebase = await clubHomebaseId(api, reTokenA, clubAId);
  expect(
    clubAHomebase,
    'TestClub.HomebaseId should be the new Location too — 2 clubs, 1 Location = fan-out trigger',
  ).toBe(locationId.toLowerCase());

  await ctxB.close();
  } finally {
    await api.dispose();
  }
});
