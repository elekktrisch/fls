import {
  test,
  expect,
  type Browser,
  type BrowserContext,
  type Page,
  type TestInfo,
} from '@playwright/test';

import {
  loginAsClubAdmin,
  provisionTwoClubs,
  type TwoClubFixture,
} from './_helpers/two-club-fixture';
import { proofVideo } from './_helpers/proof-video';

/**
 * Create the per-test browser context with video recording wired to the
 * test's output dir. The `real-idp` project sets `video: 'on'` (J-0's
 * pass-video acceptance artifact), but that only governs Playwright's
 * auto-created `page` fixture — these tests drive their own
 * `browser.newContext()` (one isolated session per club), which bypasses
 * the project video option. Passing `recordVideo` explicitly is the
 * documented way to record a manually-created context, so the green run's
 * `.webm` lands in `test-results/` and is captured by the CI upload.
 */
async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  return browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
}

/**
 * J-0 core real-chain proof — two-club tenant isolation for Locations against
 * the REAL backend (live Keycloak auth + Spring + Postgres). No mocking: a
 * `page.route` interception here would defeat the entire point of the seam.
 *
 * Asserts the journey's load-bearing invariants (J-0 acceptance):
 *   - Two CLUB_ADMINISTRATORs in different clubs each see ONLY their own
 *     club's Locations (Hibernate `@TenantId` auto-filter).
 *   - A Location created in club A is ABSENT from club B's list, and a direct
 *     cross-tenant `GET /api/v1/locations/{clubA_id}` returns 404 (NOT 403 —
 *     the row is invisible under B's tenant scope, the IDOR gate is
 *     structural; see `LocationsController` class javadoc).
 *   - The same ICAO (LSZH) is creatable in BOTH clubs independently — per-club
 *     partial unique `(club_id, icao_code)`, not global uniqueness.
 *
 * Each club runs in its own browser context (separate storageState / session)
 * so the two CLUB_ADMINISTRATOR JWTs never bleed across the tenant boundary.
 */

const ICAO = 'LSZH';
const CH_COUNTRY_LABEL = 'Switzerland';

/**
 * Pick Switzerland from the country `nz-select`. Against the REAL backend the
 * country catalog is the full ISO list (~250 rows, alphabetical), so the
 * target option is far below the fold and not directly clickable. The select
 * has `[showSearch]="true"` — open it, type into the auto-focused search box
 * to filter the list down to Switzerland, then click the now-visible option.
 * (The mock spec gets away with a bare click because it stubs a single-country
 * catalog; the real list needs the search-filter.)
 */
async function selectSwitzerland(page: Page): Promise<void> {
  await page.getByTestId('locations-country-select').locator('nz-select').click();
  // The opened nz-select focuses its search input; type to filter.
  await page.keyboard.type(CH_COUNTRY_LABEL);
  await page
    .locator('nz-option-item')
    .filter({ hasText: new RegExp(`^${CH_COUNTRY_LABEL}$`) })
    .click();
}

/** Drive the SPA's Location-create form to completion and return to the list. */
async function createLocationViaUi(
  page: Page,
  opts: { name: string; icao: string },
): Promise<void> {
  await page.goto('/locations');
  await page.getByRole('button', { name: 'New location' }).click();
  await expect(page).toHaveURL('/locations/new');

  await page.locator('#LocationName').fill(opts.name);
  await page.locator('#IcaoCode').fill(opts.icao);
  await selectSwitzerland(page);
  // First location type in the seeded reference list — any airfield type is
  // fine; the spec asserts isolation, not a specific type.
  await page.getByTestId('locations-type-select').locator('nz-select').click();
  await page.locator('nz-option-item').first().click();

  const created = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      new URL(r.url()).pathname === '/api/v1/locations' &&
      r.status() === 201,
  );
  await page.getByTestId('locations-save-button').click();
  await created;
  await expect(page).toHaveURL('/locations');
}

/**
 * Capture the Bearer the OIDC interceptor attaches to a `/api/v1/locations`
 * request, so the spec can issue a direct cross-tenant GET with the same
 * principal's token (raw `fetch`/`page.request` wouldn't otherwise carry it).
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

test.describe('Locations — two-club tenant isolation (real-idp)', () => {
  // One realm + one backend; provision once, run the isolation asserts in
  // order. Serial keeps the two sessions from racing KC user-exists checks.
  test.describe.configure({ mode: 'serial' });

  let fixture: TwoClubFixture;
  let baseURL: string;
  let clubALocationId: string;

  test.beforeAll(async ({ browser }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    fixture = await provisionTwoClubs(browser, baseURL);
  });

  test.afterAll(async () => {
    await fixture?.dispose();
  });

  test('club A admin creates a Location and sees it in club A list', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      // Capture the created Location id from the 201 response body.
      const created = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new URL(r.url()).pathname === '/api/v1/locations' &&
          r.status() === 201,
      );
      await page.goto('/locations');
      await page.getByRole('button', { name: 'New location' }).click();
      await page.locator('#LocationName').fill('Zurich (Club A)');
      await page.locator('#IcaoCode').fill(ICAO);
      await selectSwitzerland(page);
      await page.getByTestId('locations-type-select').locator('nz-select').click();
      await page.locator('nz-option-item').first().click();
      await page.getByTestId('locations-save-button').click();

      const body = (await (await created).json()) as { id: string };
      // LocationId external form is `loc-<uuid>`.
      clubALocationId = body.id;

      await expect(page).toHaveURL('/locations');
      await expect(page.getByTestId('locations-table')).toBeVisible();
      await expect(
        page.locator('[data-testid^="location-row-"]').filter({ hasText: 'Zurich (Club A)' }),
      ).toBeVisible();
    } finally {
      // Finalize the .webm (flushed only on close) BEFORE binding it to its
      // proof caption — see `proofVideo` / e2e/proof-gallery/README.md.
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-0',
        caption:
          "J-0 · tenant isolation · club A admin creates a Location and sees it in club A's list",
        acTag: 'happy',
      });
    }
  });

  test('club B admin does NOT see club A Location; cross-tenant GET 404s', async ({
    browser,
  }, testInfo) => {
    expect(clubALocationId, 'club A must have created its Location first').toBeTruthy();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubB);

      // (1) Club A's Location is absent from club B's list.
      await page.goto('/locations');
      await expect(page.getByTestId('locations-table')).toBeVisible();
      await expect(page.locator(`[data-testid="location-row-${clubALocationId}"]`)).toHaveCount(0);
      await expect(
        page.locator('[data-testid^="location-row-"]').filter({ hasText: 'Zurich (Club A)' }),
      ).toHaveCount(0);

      // (2) A direct cross-tenant GET-by-id 404s (NOT 403 — invisible under
      //     B's tenant scope). Reuse club B's real Bearer.
      const bearer = await bearerFromLocationsList(page);
      const res = await ctx.request.get(`/api/v1/locations/${clubALocationId}`, {
        headers: { authorization: bearer },
      });
      expect(
        res.status(),
        'cross-tenant GET-by-id must 404 (row invisible under tenant scope), not 403',
      ).toBe(404);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-0',
        caption: "J-0 · cross-tenant 404 · club B is denied club A's Location (404 not 403)",
        acTag: 'key-error',
      });
    }
  });

  test('same ICAO (LSZH) is creatable in club B — per-club, not global uniqueness', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubB);

      // Club A already holds LSZH; club B creating its own LSZH must NOT
      // 409 — the unique index is (club_id, icao_code), not global.
      await createLocationViaUi(page, { name: 'Zurich (Club B)', icao: ICAO });

      await expect(page.getByTestId('locations-save-error')).toBeHidden();
      await expect(
        page.locator('[data-testid^="location-row-"]').filter({ hasText: 'Zurich (Club B)' }),
      ).toBeVisible();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-0',
        caption: 'J-0 · per-club ICAO · the same ICAO is creatable independently in club B',
        acTag: 'edge',
      });
    }
  });
});
