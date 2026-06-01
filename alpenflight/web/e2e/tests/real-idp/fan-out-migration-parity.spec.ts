import {
  test,
  expect,
  type Browser,
  type BrowserContext,
  type Page,
  type TestInfo,
} from '@playwright/test';

import {
  loginAsMigratedAdmin,
  seedFanOutParity,
  type FanOutParityFixture,
} from './_helpers/fan-out-parity-fixture';
import { proofVideo } from './_helpers/proof-video';

/**
 * J-0c T-03 — the AlpenFlight UI half of the fan-out migration parity proof,
 * against the REAL stack (live Keycloak auth + Spring + Postgres). The seed is
 * a SYNTHESIZED migrated bundle ingested through the REAL
 * `POST /api/v1/migrations/{id}/bundle` endpoint (no legacy stack yet — T-05
 * swaps in the real legacy-produced bundle). NO mocking: a `page.route`
 * interception here would defeat the seam (the fan-out keying + the Keycloak
 * provision-on-migrate slice must run live).
 *
 * The fixture (`seedFanOutParity`) ingests one shared legacy Location with a
 * RANDOM unique name (`J0C-<rand>`) referenced by 2 clubs → the ingest fans it
 * out to 2 `t_location` rows (one per provisioned club) and provisions one
 * loginable Keycloak club-admin per migrated club (T-02). This spec then
 * asserts the journey's load-bearing contract THROUGH THE REAL UI:
 *
 *   - [happy] Club-A admin logs in via real Keycloak → `/locations` shows the
 *     migrated Location under its random name; club-B admin logs in → sees its
 *     OWN copy under the SAME name (fan-out: each club has a copy).
 *   - [key-behavior] Renaming club-A's copy leaves club-B's copy UNCHANGED —
 *     proving the two are DISTINCT fanned-out rows, not one shared row.
 *   - [key-error] A direct cross-tenant `GET /api/v1/locations/{clubB_id}` while
 *     authenticated as club-A returns 404 (tenant isolation holds on migrated
 *     data — the row is invisible under A's scope, not 403).
 *
 * Each club runs in its own browser context (separate storageState / session)
 * so the two migrated CLUB_ADMINISTRATOR JWTs never bleed across the tenant
 * boundary.
 */

const RENAMED_SUFFIX = ' (renamed by club A)';

/**
 * Per-test recorded context — the `real-idp` project's `video: 'on'` only
 * governs Playwright's auto-created `page`; these tests drive their own
 * `browser.newContext()` per club, so pass `recordVideo` explicitly to land
 * the green run's `.webm` for the proof gallery. Mirrors
 * `locations-crud-tenant-isolation.spec.ts`.
 */
async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  return browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
}

/**
 * Read the migrated Location's external id (`loc-<uuid>`) from its rendered
 * list row's `data-testid="location-row-<id>"`, scoped by the random name.
 * Asserts the row is present (= this club has its own fanned-out copy).
 */
async function locationIdByName(page: Page, name: string): Promise<string> {
  await page.goto('/locations');
  await expect(page.getByTestId('locations-table')).toBeVisible();
  const row = page.locator('[data-testid^="location-row-"]').filter({ hasText: name });
  await expect(row, `migrated Location "${name}" must appear in this club's list`).toBeVisible();
  const testId = await row.getAttribute('data-testid');
  expect(testId, 'migrated Location row must carry a location-row-<id> testid').toBeTruthy();
  const id = testId!.replace(/^location-row-/, '');
  expect(id, `derived migrated location id must be loc-<uuid> form, got "${id}"`).toMatch(
    /^loc-[0-9a-f-]{36}$/,
  );
  return id;
}

/**
 * Capture the Bearer the OIDC interceptor attaches to a `/api/v1/locations`
 * request so the spec can issue a direct cross-tenant GET with the same
 * principal's token.
 */
async function bearerFromLocationsList(page: Page): Promise<string> {
  const reqPromise = page.waitForRequest(
    (req) =>
      new URL(req.url()).pathname === '/api/v1/locations' &&
      typeof req.headers()['authorization'] === 'string',
  );
  await page.goto('/locations');
  const req = await reqPromise;
  return req.headers()['authorization']!;
}

test.describe('Fan-out migration parity — migrated Location, two clubs (real-idp)', () => {
  // One realm + one backend; seed once, run the parity asserts in order.
  // Serial keeps the two migrated sessions from racing the rename/isolation
  // ordering (club A must rename BEFORE club B re-checks its copy).
  test.describe.configure({ mode: 'serial' });

  let fixture: FanOutParityFixture;
  let baseURL: string;
  let clubBLocationId: string;

  test.beforeAll(async ({ browser, request }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    // Seeds through the REAL migration endpoint — fan-out + Keycloak provision
    // both run live. ~30-60s (Gradle seeder + ingest); covered by the
    // real-idp project's 60s timeout.
    fixture = await seedFanOutParity(browser, request, baseURL);
  });

  test('club-A admin sees the migrated Location under its random name', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsMigratedAdmin(page, fixture.clubA);
      const id = await locationIdByName(page, fixture.locationName);
      expect(id).toBeTruthy();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-0c',
        caption:
          'J-0c · fan-out migration · club-A admin logs in via real Keycloak and sees its ' +
          'migrated Location under the random name',
        acTag: 'happy',
      });
    }
  });

  test('club-B admin sees its OWN copy of the migrated Location, same name', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsMigratedAdmin(page, fixture.clubB);
      // Club B has its OWN fanned-out copy under the SAME random name. Record
      // its id for the rename-isolation + cross-tenant asserts below.
      clubBLocationId = await locationIdByName(page, fixture.locationName);
      expect(clubBLocationId).toBeTruthy();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-0c',
        caption:
          'J-0c · fan-out migration · club-B admin sees its OWN copy of the migrated Location ' +
          'under the same name (distinct fanned-out row)',
        acTag: 'happy',
      });
    }
  });

  test('renaming club-A copy leaves club-B copy unchanged (distinct rows)', async ({
    browser,
  }, testInfo) => {
    expect(clubBLocationId, 'club B must have located its copy first').toBeTruthy();
    const renamed = fixture.locationName + RENAMED_SUFFIX;
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      // (1) Club A renames its copy via the edit form.
      await loginAsMigratedAdmin(page, fixture.clubA);
      const clubAId = await locationIdByName(page, fixture.locationName);
      const updated = page.waitForResponse(
        (r) =>
          r.request().method() === 'PUT' &&
          new URL(r.url()).pathname === `/api/v1/locations/${clubAId}` &&
          r.status() === 200,
      );
      await page.goto(`/locations/${clubAId}/edit`);
      await expect(page.getByTestId('locations-edit-form')).toBeVisible();
      await page.locator('#LocationName').fill(renamed);
      await page.getByTestId('locations-save-button').click();
      await updated;
      await expect(page).toHaveURL('/locations');
      // Club A's list now shows the renamed copy.
      await expect(
        page.locator(`[data-testid="location-row-${clubAId}"]`).filter({ hasText: renamed }),
      ).toBeVisible();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-0c',
        caption:
          'J-0c · edit isolation · renaming club-A’s migrated copy does not touch ' +
          'club-B’s copy (the two are DISTINCT fanned-out rows)',
        acTag: 'happy',
      });
    }

    // (2) Club B's copy is UNCHANGED — still the original random name, same id.
    const ctxB = await newRecordedContext(browser, baseURL, testInfo);
    const pageB = await ctxB.newPage();
    try {
      await loginAsMigratedAdmin(pageB, fixture.clubB);
      await pageB.goto('/locations');
      await expect(pageB.getByTestId('locations-table')).toBeVisible();
      // The original name still resolves to club B's SAME row id.
      const stillThere = pageB
        .locator(`[data-testid="location-row-${clubBLocationId}"]`)
        .filter({ hasText: fixture.locationName });
      await expect(
        stillThere,
        'club B copy must still carry the ORIGINAL name (rename did not leak across the fan-out)',
      ).toBeVisible();
      // And the renamed text must NOT appear in club B's list.
      await expect(
        pageB.locator('[data-testid^="location-row-"]').filter({ hasText: RENAMED_SUFFIX }),
      ).toHaveCount(0);
    } finally {
      await ctxB.close();
    }
  });

  test('cross-tenant GET of club-B Location as club-A 404s (isolation on migrated data)', async ({
    browser,
  }, testInfo) => {
    expect(clubBLocationId, 'club B id must be known from the earlier assert').toBeTruthy();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsMigratedAdmin(page, fixture.clubA);
      // Reuse club A's real Bearer to GET club B's migrated Location id.
      const bearer = await bearerFromLocationsList(page);
      const res = await ctx.request.get(`/api/v1/locations/${clubBLocationId}`, {
        headers: { authorization: bearer },
      });
      expect(
        res.status(),
        'cross-tenant GET of the other club’s migrated Location must 404 ' +
          '(invisible under tenant scope), not 403',
      ).toBe(404);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-0c',
        caption:
          'J-0c · cross-tenant 404 · club-A is denied club-B’s migrated Location ' +
          '(tenant isolation holds on migrated data)',
        acTag: 'key-error',
      });
    }
  });
});
