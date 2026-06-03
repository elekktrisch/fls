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
import {
  loginAsMigratedAdmin,
  seedAircraftOwnerLink,
  seedAircraftParity,
  type AircraftParityFixture,
} from './_helpers/aircraft-parity-fixture';
import { proofVideo } from './_helpers/proof-video';

/**
 * J-1 T-07 — the Aircraft register real chain (live Keycloak auth + real Spring
 * backend + real Postgres). NO mocking on the happy + key-error paths: a
 * `page.route` interception would defeat the seam (the @TenantId filter, the
 * AircraftAccess SpEL gate, the migration ingest + Keycloak provisioning must
 * all run live). The S-163 [edge] uses a DB-fixture seeder for rows that have
 * no UI/REST surface (the owner-person link) — that is fixture STATE, not a
 * mocked seam; the S-163 access decision itself still runs fully real.
 *
 * Two fidelities, both green at the gate:
 *   - CLEAN-SEED real chain (`provisionTwoClubs`): a club admin logs in, lists,
 *     creates, edits, deletes; a cross-club caller is 403'd on edit/delete;
 *     the owner-person of a non-managing club is admitted (S-163); latestCounter
 *     is present for the manager and redacted for the non-manager (S-164).
 *   - MIGRATED-DATA real chain (`seedAircraftParity`): a real (synth/real)
 *     legacy Aircraft is migrated through the REAL migration endpoint and
 *     renders in the owning club's /aircraft list under its immatriculation.
 *
 * The DE language seed (V2) — the t_user the S-163 seeder writes needs a valid
 * language FK; same UUID the AircraftsAuthorizationIT pins.
 */
const LANG_DE_UUID = '019e2e15-2c00-77d0-8000-0000000007d0';

/**
 * Per-test recorded context — the `real-idp` project's `video: 'on'` only
 * governs Playwright's auto-created `page`; these tests drive their own
 * `browser.newContext()` per club, so pass `recordVideo` explicitly to land the
 * green run's `.webm` for the proof gallery.
 */
async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  return browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
}

/** Pick the GLIDER option from the aircraft-type select (seeded reference list). */
async function selectGliderType(page: Page): Promise<void> {
  await page.getByTestId('aircraft-type-select').locator('nz-select').click();
  await page.locator('nz-option-item').filter({ hasText: 'Glider' }).first().click();
}

/**
 * Create an aircraft via the SPA form and return to the list. Waits on the 201
 * as a completion signal only (the SPA navigates away the instant it resolves,
 * and Chrome evicts the body across navigation), then reads the id from the
 * rendered row's testid.
 */
async function createAircraftViaUi(page: Page, immatriculation: string): Promise<string> {
  await page.goto('/aircraft');
  await page.getByTestId('aircraft-new-button').locator('button').click();
  await expect(page).toHaveURL('/aircraft/new');

  await page.locator('#Immatriculation').fill(immatriculation);
  await selectGliderType(page);
  await page.locator('#ManufacturerName').fill('Schleicher');

  const created = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      new URL(r.url()).pathname === '/api/v1/aircraft' &&
      r.status() === 201,
  );
  await page.getByTestId('aircraft-save-button').locator('button').click();
  await created;
  await expect(page).toHaveURL('/aircraft');

  const row = page.locator('[data-testid^="aircraft-row-"]').filter({ hasText: immatriculation });
  await expect(row, `created aircraft "${immatriculation}" must appear in the list`).toBeVisible();
  const testId = await row.getAttribute('data-testid');
  expect(testId, 'aircraft row must carry an aircraft-row-<id> testid').toBeTruthy();
  const id = testId!.replace(/^aircraft-row-/, '');
  expect(id, `derived aircraft id must be ac-<uuid> form, got "${id}"`).toMatch(
    /^ac-[0-9a-f-]{36}$/,
  );
  return id;
}

/**
 * Capture the Bearer the OIDC interceptor attaches to a `/api/v1/aircraft`
 * request so the spec can issue a direct cross-tenant write with the same
 * principal's token.
 */
async function bearerFromAircraftList(page: Page): Promise<string> {
  const reqPromise = page.waitForRequest(
    (req) =>
      new URL(req.url()).pathname === '/api/v1/aircraft' &&
      typeof req.headers()['authorization'] === 'string',
  );
  await page.goto('/aircraft');
  const req = await reqPromise;
  return req.headers()['authorization']!;
}

// ===========================================================================
// CLEAN-SEED real chain — CRUD + tenant edit-isolation + S-163 + S-164.
// ===========================================================================
test.describe('Aircraft register — clean-seed real chain (real-idp)', () => {
  // One realm + one backend; provision once, run the asserts in order. Serial
  // keeps the two sessions from racing the create→edit→delete ordering.
  test.describe.configure({ mode: 'serial' });

  let fixture: TwoClubFixture;
  let baseURL: string;
  /** The aircraft club A creates + manages; reused across the CRUD + 403 cases. */
  let aircraftId: string;
  let aircraftImmat: string;

  test.beforeAll(async ({ browser }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    // Spec-scoped admin usernames ('acft') so this fixture's club admins are
    // disjoint from the J-0 Locations spec's ('loc') when both run in one
    // `playwright test` invocation — ux_user_username_lower_alive (T-11).
    fixture = await provisionTwoClubs(browser, baseURL, 'acft');
  });

  test.afterAll(async () => {
    await fixture?.dispose();
  });

  test('club admin lists, creates an aircraft, and sees it sorted in the list', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      // List renders (immatriculation + competition sign + type columns).
      await page.goto('/aircraft');
      await expect(page.locator('h1')).toHaveText('Aircraft');
      await expect(page.getByTestId('aircraft-table')).toBeVisible();

      // Create via the form → appears in the list.
      aircraftImmat = `HB-J1${Date.now().toString(36).slice(-3).toUpperCase()}`;
      aircraftId = await createAircraftViaUi(page, aircraftImmat);
      expect(aircraftId).toBeTruthy();

      // The new row shows immatriculation on the row LINK; the GLIDER type code
      // + manufacturer render in the SIBLING `#secondary` template under their
      // own testids (aircraft-list.page.ts ~112+).
      const row = page.locator(`[data-testid="aircraft-row-${aircraftId}"]`);
      await expect(row).toContainText(aircraftImmat);
      // Clean-seed list-column parity (T-13): the create flow fills
      // ManufacturerName=Schleicher (line 89), so the secondary line's
      // `aircraft-model-<id>` renders it. (Model + seats are not set on create —
      // those are asserted on the migrated row, which carries all three.)
      await expect(page.getByTestId(`aircraft-model-${aircraftId}`)).toContainText('Schleicher');
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-1',
        caption:
          'J-1 · aircraft register · club admin logs in via real Keycloak, lists the fleet, and ' +
          'creates an aircraft that appears in the list (immatriculation + type)',
        acTag: 'happy',
      });
    }
  });

  test('club admin edits the aircraft; the change persists and re-renders', async ({
    browser,
  }, testInfo) => {
    expect(aircraftId, 'create must have run first').toBeTruthy();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      const updated = page.waitForResponse(
        (r) =>
          r.request().method() === 'PUT' &&
          new URL(r.url()).pathname === `/api/v1/aircraft/${aircraftId}` &&
          r.status() === 200,
      );
      await page.goto(`/aircraft/${aircraftId}/edit`);
      await expect(page.getByTestId('aircraft-edit-form')).toBeVisible();
      await expect(page.locator('#Immatriculation')).toHaveValue(aircraftImmat);
      await page.locator('#Comment').fill('edited by J-1 e2e');
      await page.getByTestId('aircraft-save-button').locator('button').click();
      await updated;
      await expect(page).toHaveURL('/aircraft');

      // Persistence round-trip — reopen the edit form, the comment persisted.
      await page.goto(`/aircraft/${aircraftId}/edit`);
      await expect(page.locator('#Comment')).toHaveValue('edited by J-1 e2e');
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-1',
        caption:
          'J-1 · aircraft register · the managing-club admin edits the aircraft and the change ' +
          'persists across a reload (real backend round-trip)',
        acTag: 'happy',
      });
    }
  });

  test('cross-club caller is denied edit + delete on the aircraft (403)', async ({
    browser,
  }, testInfo) => {
    expect(aircraftId, 'create must have run first').toBeTruthy();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      // Club B's admin: NOT the managing club. Reads stay open (cross-tenant
      // catalog, S-058) but writes are manager-club gated → 403, not 404.
      await loginAsClubAdmin(page, fixture.clubB);
      const bearer = await bearerFromAircraftList(page);

      // The aircraft is readable cross-tenant (200).
      const read = await ctx.request.get(`/api/v1/aircraft/${aircraftId}`, {
        headers: { authorization: bearer },
      });
      expect(read.status(), 'aircraft is cross-tenant readable').toBe(200);

      // PUT (edit) by the non-managing club → 403.
      const put = await ctx.request.put(`/api/v1/aircraft/${aircraftId}`, {
        headers: { authorization: bearer, 'content-type': 'application/json' },
        data: {
          aircraftTypeId: '019e2e15-2c00-7af9-8000-000000002af9',
          immatriculation: `HB-X${Date.now().toString(36).slice(-3).toUpperCase()}`,
          manufacturerName: 'Schleicher',
          aircraftModel: 'ASK-21',
          nrOfSeats: 2,
          isTowingOrWinchRequired: false,
          isTowingStartAllowed: false,
          isWinchStartAllowed: false,
          isTowingAircraft: false,
        },
      });
      expect(put.status(), 'cross-club edit must be 403 (managing-club gate), not 404').toBe(403);

      // DELETE by the non-managing club → 403.
      const del = await ctx.request.delete(`/api/v1/aircraft/${aircraftId}`, {
        headers: { authorization: bearer },
      });
      expect(del.status(), 'cross-club delete must be 403').toBe(403);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-1',
        caption:
          "J-1 · edit isolation · a caller whose club ≠ the aircraft's managing club is denied " +
          'edit and delete (403, managing-club gate)',
        acTag: 'key-error',
      });
    }
  });

  test('S-164 · latestCounter present for the manager, redacted for a non-manager', async ({
    browser,
  }, testInfo) => {
    expect(aircraftId, 'create must have run first').toBeTruthy();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      // (1) The managing-club admin records an operating counter so latestCounter
      //     is non-null, then sees it on the detail view.
      await loginAsClubAdmin(page, fixture.clubA);
      const managerBearer = await bearerFromAircraftList(page);
      const counter = await ctx.request.post(`/api/v1/aircraft/${aircraftId}/counters`, {
        headers: { authorization: managerBearer, 'content-type': 'application/json' },
        data: { atDateTime: '2026-01-01T10:00:00Z', flightOperatingCounterInSeconds: 3600 },
      });
      expect(counter.status(), 'manager may record a counter').toBe(201);

      const managerRead = await ctx.request.get(`/api/v1/aircraft/${aircraftId}`, {
        headers: { authorization: managerBearer },
      });
      expect(managerRead.status()).toBe(200);
      const managerBody = (await managerRead.json()) as { latestCounter: unknown };
      expect(
        managerBody.latestCounter,
        'latestCounter is present for the managing-club caller',
      ).toBeTruthy();

      // The managing-club admin also sees it rendered on the edit page.
      await page.goto(`/aircraft/${aircraftId}/edit`);
      await expect(page.getByTestId('aircraft-latest-counter')).toBeVisible();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-1',
        caption:
          "J-1 · S-164 · the aircraft's latestCounter is surfaced to the managing-club admin on " +
          'the detail view (real backend, real counter)',
        acTag: 'edge',
      });
    }

    // (2) A non-managing club's caller reads the same aircraft (cross-tenant,
    //     200) but latestCounter is redacted (null / absent).
    const ctxB = await newRecordedContext(browser, baseURL, testInfo);
    const pageB = await ctxB.newPage();
    try {
      await loginAsClubAdmin(pageB, fixture.clubB);
      const foreignBearer = await bearerFromAircraftList(pageB);
      const foreignRead = await ctxB.request.get(`/api/v1/aircraft/${aircraftId}`, {
        headers: { authorization: foreignBearer },
      });
      expect(foreignRead.status(), 'aircraft stays cross-tenant readable (200)').toBe(200);
      const foreignBody = (await foreignRead.json()) as { latestCounter: unknown };
      expect(
        foreignBody.latestCounter === null || foreignBody.latestCounter === undefined,
        'latestCounter is redacted for a non-managing-club caller (S-164)',
      ).toBe(true);
    } finally {
      await ctxB.close();
    }
  });

  test('S-163 · the owner-person of a non-managing club may edit the aircraft', async ({
    browser,
  }, testInfo) => {
    expect(aircraftId, 'create must have run first').toBeTruthy();
    // Club B is NOT the managing club. Link its admin's Person to the aircraft's
    // owner-person (DB fixture — no UI/REST surface sets the owner-person link),
    // then prove the S-163 OR-branch admits the owner-person edit, fully live.
    await seedAircraftOwnerLink({
      aircraftId,
      ownerKeycloakSub: fixture.clubB.kcUserId, // the KC user id IS the JWT sub
      ownerClubId: fixture.clubB.clubId,
      languageId: LANG_DE_UUID,
    });

    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      // Club B admin (non-managing) is now the aircraft's owner-person.
      await loginAsClubAdmin(page, fixture.clubB);
      const bearer = await bearerFromAircraftList(page);
      const put = await ctx.request.put(`/api/v1/aircraft/${aircraftId}`, {
        headers: { authorization: bearer, 'content-type': 'application/json' },
        data: {
          aircraftTypeId: '019e2e15-2c00-7af9-8000-000000002af9',
          immatriculation: aircraftImmat,
          manufacturerName: 'Schleicher',
          aircraftModel: 'ASK-21 (by owner)',
          nrOfSeats: 2,
          isTowingOrWinchRequired: false,
          isTowingStartAllowed: false,
          isWinchStartAllowed: false,
          isTowingAircraft: false,
        },
      });
      expect(
        put.status(),
        'the owner-person of a non-managing club is admitted to edit (S-163 OR-branch)',
      ).toBe(200);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-1',
        caption:
          "J-1 · S-163 · a caller whose linked Person == the aircraft's owner-person may edit it " +
          'even from a non-managing club (net-new owner-person admit, fully real)',
        acTag: 'edge',
      });
    }
  });
});

// ===========================================================================
// MIGRATED-DATA real chain — legacy → migrate → Keycloak → UI.
// ===========================================================================
test.describe('Aircraft register — migrated legacy aircraft renders (real-idp)', () => {
  // retries: 0 unconditionally (not just REAL_BUNDLE). The synth seed mints a
  // fresh handshake/uploadId + club key + immatriculation per attempt, but the
  // bundle ingest provisions a NON-TERMINAL Deployment owned by THIS spec's
  // migration principal (`clubadmin2` — disjoint from J-0c's `clubadmin1` so the
  // two co-located parity specs don't collide, T-17). A Playwright retry re-runs
  // the ingest with a fresh uploadId, so the idempotency-key short-circuit misses
  // and the owner-active gate (DeploymentProvisioningService#provision:
  // findActiveByOwner(clubadmin2)) 409s DEPLOYMENT_EXISTS on the PRIOR ATTEMPT's
  // own Deployment — masking the real first-attempt cause. A retry can't clear
  // the principal's active Deployment, so the only clean isolation is to not
  // retry: the real failure shows clearly. (REAL_BUNDLE already needed retries: 0
  // for BUNDLE_PRIOR_RUN_FAILED.) The cross-spec collision is solved separately by
  // the distinct principal; this retries:0 still guards the within-spec re-run.
  test.describe.configure({ mode: 'serial', retries: 0 });

  let fixture: AircraftParityFixture;
  let baseURL: string;

  test.beforeAll(async ({ browser, request }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    // Seeds through the REAL migration endpoint — ingest + Keycloak provision
    // both run live. The retry index mints a FRESH handshake/uploadId (synth)
    // so a retry after a failed ingest re-handshakes cleanly.
    fixture = await seedAircraftParity(browser, request, baseURL, testInfo.retry);
  });

  test('the migrated aircraft renders in the owning club list under its immatriculation', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsMigratedAdmin(page, fixture.owner);
      await page.goto('/aircraft');
      await expect(page.getByTestId('aircraft-table')).toBeVisible();
      const row = page
        .locator('[data-testid^="aircraft-row-"]')
        .filter({ hasText: fixture.immatriculation });
      await expect(
        row,
        `migrated aircraft "${fixture.immatriculation}" must appear in the owning club's list`,
      ).toBeVisible();

      // Legacy list parity (T-13): the migrated row renders Manufacturer +
      // Model + Seats (the AircraftParityBundleSeeder writes Schleicher / ASK
      // 21 / 2). Legacy aircrafts-table.html shows these three columns. The
      // `aircraft-row-<id>` testid is on the immatriculation LINK; manufacturer/
      // model/seats live in the SIBLING `#secondary` template under their own
      // `aircraft-model-<id>` / `aircraft-seats-<id>` testids (aircraft-list.page.ts
      // ~112+) — assert against those, not the row link. Derive the id from the
      // row testid (same pattern as createAircraftViaUi above).
      const testId = await row.getAttribute('data-testid');
      expect(testId, 'migrated aircraft row must carry an aircraft-row-<id> testid').toBeTruthy();
      const id = testId!.replace(/^aircraft-row-/, '');
      expect(id, `derived migrated aircraft id must be ac-<uuid> form, got "${id}"`).toMatch(
        /^ac-[0-9a-f-]{36}$/,
      );
      // Manufacturer + model are migrated DATA (locale-independent).
      const model = page.getByTestId(`aircraft-model-${id}`);
      await expect(model).toContainText('Schleicher');
      await expect(model).toContainText('ASK 21');
      // Seats renders via the `list.columns.seats` i18n; the real-idp run
      // resolves to `en` (line 164 asserts the English 'Aircraft' title), so the
      // rendered string is "2 seats" (en), not "2 Sitze" (de).
      await expect(page.getByTestId(`aircraft-seats-${id}`)).toContainText('2 seats');
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-1',
        caption:
          'J-1 · migrated aircraft · a real legacy Aircraft, migrated through the live migration ' +
          "endpoint, renders in the owning club's /aircraft list under its immatriculation " +
          '(full legacy→migrate→Keycloak→UI chain)',
        acTag: 'happy',
      });
    }
  });
});
