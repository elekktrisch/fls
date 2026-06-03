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
 * J-2 T-01 — the Flight list+edit real chain STUB (live Keycloak auth + real
 * Spring backend + real Postgres). This commits the SCREEN SHAPE: structure,
 * selectors, and flow steps with THIN assertions only (presence / navigation,
 * not deep value checks). The build tasks fill in behavior (T-02 time-gate,
 * T-04 412 conflict-diff, T-05 /airmovements, T-07/T-08 migration); T-09
 * thickens these assertions to the full oracle set. It is fine if this stub
 * does not fully pass yet.
 *
 * Mirrors the J-1 `aircraft-migration-parity.spec.ts` harness shape:
 *   - CLEAN-SEED real chain (`provisionTwoClubs`): a club admin logs in, lists
 *     /flights, creates a GLIDER flight via the 3-step wizard (Launch → Glider
 *     → Tow when start-type = aerotow, with the tow created + linked in the SAME
 *     save), creates a MOTOR flight at /airmovements, edits, deletes; a
 *     cross-tenant flight GET 404s; the time-gate rejects a too-recent
 *     Valid→Locked transition and allows a past-threshold one; a DeliveryBooked
 *     flight is read-only (edit/delete → 4xx); a stale PUT (412) opens the
 *     inline per-field conflict diff.
 *   - MIGRATED-DATA real chain: a migrated legacy flight (crew + tow link)
 *     renders in the owning club's /flights list. Gated on the T-08 flight
 *     parity fixture (not yet authored) — see the `test.fixme` block below.
 *
 * Tenancy/gate parity is resolved in J-2-flight-list-edit.md § Parity decisions
 * (legacy-oracle 2026-06-03 + operator): single-flight GET/PUT/DELETE scope by
 * @TenantId → cross-tenant 404 (NOT the aircraft 403 managing-club shape, which
 * is a catalog read); read-only targets DeliveryBooked(60), NOT Locked; the
 * time-gate keys on flight_date (≥2d lock) / locked_at (≥3d bill), a deliberate
 * divergence from legacy CreatedOn; 412 is a net-new affordance, not parity.
 */

/**
 * Per-test recorded context — the `real-idp` project's `video: 'on'` only
 * governs Playwright's auto-created `page`; these tests drive their own
 * `browser.newContext()` per club, so pass `recordVideo` explicitly to land the
 * green run's `.webm` for the proof gallery (mirrors the J-1 aircraft spec).
 */
async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  return browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
}

/**
 * Capture the Bearer the OIDC interceptor attaches to a `/api/v1/flights`
 * request so a case can issue a direct cross-tenant / stale-version write with
 * the same principal's token (mirrors `bearerFromAircraftList`).
 */
async function bearerFromFlightsList(page: Page): Promise<string> {
  const reqPromise = page.waitForRequest(
    (req) =>
      new URL(req.url()).pathname === '/api/v1/flights' &&
      typeof req.headers()['authorization'] === 'string',
  );
  await page.goto('/flights');
  const req = await reqPromise;
  return req.headers()['authorization']!;
}

/**
 * Create a flight via the 3-step wizard and return to the list. Walks
 * Launch → Glider → (Tow when start-type = aerotow). When the wizard's
 * new-template defaults the start type to aerotow (`needsTowplane`), the Tow
 * step renders and the tow flight is created + linked in the SAME save (the
 * paired glider↔tow save, S-063 parity — asserted thinly here via list
 * presence; the request-order + If-Match assertions land in T-09).
 *
 * THIN: returns the created flight's id from the rendered row testid; the deep
 * column-parity + towFlightId-link checks come in T-09.
 *
 * TODO(T-02/T-09): the wizard's start-type select drives whether the Tow step
 * renders. This stub takes whatever new-template defaults to; T-09 must drive
 * the start type to AEROTOW explicitly to exercise the paired-tow branch
 * deterministically (the clean realm's new-template default is not yet pinned).
 */
async function createFlightViaWizard(page: Page, comment: string): Promise<string> {
  await page.goto('/flights/new');
  await expect(page.getByTestId('flight-form')).toBeVisible();
  await expect(page.getByTestId('flight-step-launch')).toBeVisible();

  // Step 1 → 2 (Glider). Aircraft / pilot / flight-type default from the
  // new-template; stamp a comment so the edit/persistence case has a value.
  await page.getByTestId('flight-step-next').click();
  await expect(page.getByTestId('flight-step-glider')).toBeVisible();
  await page.getByTestId('flight-edit-glider-comment').locator('input').fill(comment);

  // When start-type = aerotow the Tow step renders (flight-step-2); the paired
  // tow is created + linked in the same save. THIN: just advance through it if
  // present; T-09 fills the tow aircraft/pilot + asserts the linked TOW row.
  if (
    await page
      .getByTestId('flight-step-tow')
      .isVisible()
      .catch(() => false)
  ) {
    await page.getByTestId('flight-step-next').click();
    await expect(page.getByTestId('flight-step-tow')).toBeVisible();
  }

  await page.getByTestId('flight-submit-header').click();
  await expect(page).toHaveURL(/\/flights$/);

  // The new flight appears in the list. THIN: derive the id from the first
  // row's testid (T-09 matches the specific created flight by its attributes).
  const row = page.locator('[data-testid^="flights-row-"]').first();
  await expect(row, 'created flight must appear in the list').toBeVisible();
  const testId = await row.getAttribute('data-testid');
  expect(testId, 'flight row must carry a flights-row-<id> testid').toBeTruthy();
  return testId!.replace(/^flights-row-/, '');
}

// ===========================================================================
// CLEAN-SEED real chain — glider+tow+motor CRUD + tenant 404 + time-gate + 412.
// ===========================================================================
test.describe('Flight list+edit — clean-seed real chain (real-idp)', () => {
  // One realm + one backend; provision once, run the asserts in order. Serial
  // keeps the two sessions from racing the create→edit→delete ordering (mirrors
  // the J-1 aircraft spec).
  test.describe.configure({ mode: 'serial' });

  let fixture: TwoClubFixture;
  let baseURL: string;
  /** The glider flight club A creates + manages; reused across CRUD + tenant cases. */
  let flightId: string;

  test.beforeAll(async ({ browser }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    // Spec-scoped admin usernames ('flt') so this fixture's club admins are
    // disjoint from the J-0 ('loc') / J-1 ('acft') specs when several run in one
    // `playwright test` invocation — ux_user_username_lower_alive.
    fixture = await provisionTwoClubs(browser, baseURL, 'flt');
  });

  test.afterAll(async () => {
    await fixture?.dispose();
  });

  test('club admin lists /flights and creates a glider flight via the 3-step wizard', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      // List renders (the logbook heading + the table shell).
      await page.goto('/flights');
      await expect(page.locator('h1')).toHaveText('Flights');
      await expect(page.getByTestId('flights-table')).toBeVisible();

      // Create a glider flight via the wizard → it appears in the list. The
      // paired-tow save (when start-type = aerotow) is exercised inside the
      // wizard helper; T-09 asserts the distinct linked TOW row + towFlightId.
      flightId = await createFlightViaWizard(page, 'created by J-2 e2e');
      expect(flightId).toBeTruthy();

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-flights-list.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-2',
        caption:
          'J-2 · flight logbook · club admin logs in via real Keycloak, lists /flights, and ' +
          'creates a glider flight via the 3-step wizard that appears in the list',
        acTag: 'happy',
      });
    }
  });

  test('club admin creates a motor flight at /airmovements (MOTOR filter, no tow)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      // /airmovements is the SAME shared list/form parameterized by the MOTOR
      // filter (S-064 — no legacy-style duplication). The route + the motor
      // create flow are built in T-05; this stub asserts the screen mounts and
      // a created motor flight lands in the motor list.
      // TODO(T-05): the /airmovements route does not exist yet (flights.routes.ts
      // has no `airmovements` entry) — T-05 adds the route + nav entry + the
      // motor-filter param on the shared list/form. Until then this navigation
      // 404s in the SPA; the assertions below pin the intended shape.
      await page.goto('/airmovements');
      await expect(page.locator('h1')).toHaveText('Air movements');
      await expect(page.getByTestId('flights-table')).toBeVisible();

      // Motor create reuses the wizard with the Tow step suppressed (no tow
      // pairing for MOTOR). THIN: create + assert list presence.
      await createFlightViaWizard(page, 'motor by J-2 e2e');
      await expect(page.locator('[data-testid^="flights-row-"]').first()).toBeVisible();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-2',
        caption:
          'J-2 · air movements · a motor flight is created at /airmovements (same Flight backend, ' +
          'MOTOR filter, no tow pairing) and renders in the motor list',
        acTag: 'happy',
      });
    }
  });

  test('club admin edits the glider flight; the change persists and re-renders', async ({
    browser,
  }, testInfo) => {
    expect(flightId, 'create must have run first').toBeTruthy();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      await page.goto(`/flights/${flightId}/edit`);
      await expect(page.getByTestId('flight-form')).toBeVisible();

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-flights-form.png`,
        fullPage: true,
      });

      // Edit the glider-step comment + save. THIN: assert we return to the list;
      // T-09 reopens the edit form and asserts the persisted value round-trips.
      await page.getByTestId('flight-step-next').click();
      await page
        .getByTestId('flight-edit-glider-comment')
        .locator('input')
        .fill('edited by J-2 e2e');
      await page.getByTestId('flight-submit-header').click();
      await expect(page).toHaveURL(/\/flights$/);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-2',
        caption:
          'J-2 · flight logbook · the club admin edits a flight and the change persists across a ' +
          'reload (real backend round-trip)',
        acTag: 'happy',
      });
    }
  });

  test('club admin deletes a flight; it leaves the list', async ({ browser }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      await page.goto('/flights');

      // Create a throwaway flight to delete (keeps the suite's primary flightId
      // intact for the tenant/gate cases below).
      const victimId = await createFlightViaWizard(page, 'to-delete by J-2 e2e');

      // Kebab → Delete → confirm dialog → confirm. THIN: assert the row leaves
      // the list (T-09 asserts the If-Match version on the DELETE).
      await page.goto('/flights');
      await page.getByTestId(`flights-kebab-${victimId}`).click();
      await page.getByTestId(`flights-delete-${victimId}`).click();
      await expect(page.getByTestId('af-dialog-title')).toContainText('Delete this flight?');
      await page.getByTestId('af-dialog-confirm').click();

      await expect(page.getByTestId(`flights-row-${victimId}`)).toHaveCount(0);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-2',
        caption:
          'J-2 · flight logbook · the club admin deletes a flight (CLUB_ADMINISTRATOR gate) and it ' +
          'leaves the list',
        acTag: 'happy',
      });
    }
  });

  test('cross-tenant flight GET 404s (single-flight @TenantId scope, oracle #24)', async ({
    browser,
  }, testInfo) => {
    expect(flightId, 'create must have run first').toBeTruthy();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      // Club B's admin: a different tenant. Flights are NOT a cross-tenant
      // catalog (unlike aircraft, which 200s the read + 403s the write) — a
      // single-flight GET from another tenant must 404 (structural @TenantId
      // scope, ADR 0008), closing the legacy GetFlight(id) no-ClubId-filter leak.
      await loginAsClubAdmin(page, fixture.clubB);
      const bearer = await bearerFromFlightsList(page);

      const read = await ctx.request.get(`/api/v1/flights/${flightId}`, {
        headers: { authorization: bearer },
      });
      expect(read.status(), 'a cross-tenant flight GET must 404 (not 403/200)').toBe(404);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-2',
        caption:
          "J-2 · tenant scope · a caller from a different club cannot read another tenant's flight " +
          '(GET 404, structural @TenantId scope)',
        acTag: 'key-error',
      });
    }
  });

  test('time-gate: a too-recent flight cannot lock; a past-threshold flight can (S-061)', async ({
    browser,
  }, testInfo) => {
    expect(flightId, 'create must have run first').toBeTruthy();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      const bearer = await bearerFromFlightsList(page);

      // Operator divergence (J-2 Parity decisions): the lock gate keys on
      // `flight_date ≤ clock.today()-2d` (NOT legacy CreatedOn). The freshly
      // created flight's flight_date defaults to "today" (clock-anchored in the
      // e2e fixture), so a Valid→Locked PATCH must be REJECTED as too-recent.
      // TODO(T-02): the PATCH `/{id}/process-state` gate + the injected Clock +
      // the `locked_at` column do not exist yet — T-02 builds FlightGatePolicy
      // and wires it into FlightStateTransitionService. THIN here: assert the
      // too-recent lock is rejected (4xx). T-09 adds the past-threshold ALLOW
      // (a flight whose flight_date the seed sets ≥2d in the past) + the exact
      // day-boundary (1d-before reject / on-or-after allow) once T-08 seeds it.
      const lockTooRecent = await ctx.request.patch(`/api/v1/flights/${flightId}/process-state`, {
        headers: { authorization: bearer, 'content-type': 'application/json' },
        data: { processState: 'LOCKED' },
      });
      expect(
        lockTooRecent.status(),
        'a too-recent flight (flight_date within the 2d gate) cannot transition Valid→Locked',
      ).toBeGreaterThanOrEqual(400);
      expect(lockTooRecent.status()).toBeLessThan(500);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-2',
        caption:
          'J-2 · time-gate · a flight whose flight_date is within the 2-day lock window cannot ' +
          'transition Valid→Locked (S-061, operator divergence keying on flight_date)',
        acTag: 'key-error',
      });
    }
  });

  test('a DeliveryBooked flight is read-only: edit + delete return 4xx', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      const bearer = await bearerFromFlightsList(page);

      // The read-only rule targets DeliveryBooked(60), NOT Locked (oracle #12 —
      // a Locked flight is still editable). A DeliveryBooked flight rejects
      // edit + delete with a 4xx state-gate error.
      // TODO(T-08): no DeliveryBooked flight exists in a clean realm — the
      // T-08 flight parity bundle / legacy seed must produce one (the seeder
      // declares "a DeliveryBooked read-only flight"). Until then this case has
      // no subject; it is `fixme`'d so the stub compiles + documents the shape.
      // T-09 resolves the seeded DeliveryBooked flight id and asserts PUT→4xx +
      // DELETE→4xx (and that the edit form renders read-only in the SPA).
      void bearer;
      test.fixme(
        true,
        'needs the T-08 flight parity seed (a DeliveryBooked flight); thicken in T-09',
      );
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-2',
        caption:
          'J-2 · read-only state · a DeliveryBooked flight rejects edit + delete (4xx state-gate; ' +
          'Locked stays editable per oracle #12)',
        acTag: 'key-error',
      });
    }
  });

  test('412 optimistic concurrency: a stale PUT opens the inline per-field conflict diff', async ({
    browser,
  }, testInfo) => {
    expect(flightId, 'create must have run first').toBeTruthy();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      // Net-new affordance (NOT legacy parity — legacy Flight is last-write-wins,
      // oracle #25). Open the edit form, then race a second writer so the form's
      // in-hand version goes stale; saving issues a PUT with the old If-Match →
      // 412 → the inline per-field conflict diff opens (keep-mine / keep-theirs,
      // no auto-retry). A 409 state-gate reject instead shows a "reload latest"
      // toast (S-062h) — asserted in T-09.
      // TODO(T-04): the conflict-diff dialog does not exist yet. T-04 builds
      // `flight-conflict-prompt.component.ts` and wires FlightStore.save's 412
      // branch to open it (the store currently only *tracks* saveConflict). The
      // testids below (`flight-conflict-prompt`, the per-field keep-mine/
      // keep-theirs controls) MUST be added by T-04 — they do not exist in the
      // edit page today. T-09 drives the stale-write race deterministically
      // (e.g. a direct PUT bumps the version between load and save) and asserts
      // the per-field diff + the no-auto-retry behavior.
      await page.goto(`/flights/${flightId}/edit`);
      await expect(page.getByTestId('flight-form')).toBeVisible();
      test.fixme(
        true,
        'needs the T-04 412 conflict-diff dialog (flight-conflict-prompt) + FlightStore.save wiring; thicken in T-09',
      );
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-2',
        caption:
          'J-2 · optimistic concurrency · a stale PUT (old If-Match) returns 412 and opens the ' +
          'inline per-field keep-mine/keep-theirs conflict diff (no auto-retry; net-new affordance)',
        acTag: 'key-error',
      });
    }
  });
});

// ===========================================================================
// MIGRATED-DATA real chain — legacy → migrate → Keycloak → UI.
// ===========================================================================
// The migrated-flight render is the J-2 done-bar (full real chain). It depends
// on the T-08 flight parity fixture (`seedFlightParity` + the legacy MSSQL
// flight seed), which mirrors the J-1 `seedAircraftParity` shape but does not
// exist yet. This describe pins the intended structure; T-09 wires it to the
// real fixture once T-07/T-08 land.
//
// TODO(T-08/T-09): author `_helpers/flight-parity-fixture.ts` (mirror
// `aircraft-parity-fixture.ts`): ingest a migrated Flight + FlightCrew bundle
// through the REAL `POST /api/v1/migrations/{id}/bundle`, resolve the owning
// club's loginable migrated admin, then assert the migrated flight (crew + tow
// link) renders in that club's /flights list. Import it here in T-09 and
// replace this `fixme` with the live render assertion.
test.describe('Flight list+edit — migrated legacy flight renders (real-idp)', () => {
  test.describe.configure({ mode: 'serial', retries: 0 });

  test('the migrated flight (crew + tow link) renders in the owning club /flights list', () => {
    test.fixme(
      true,
      'needs the T-07/T-08 Flight+FlightCrew migration bindings + flight parity fixture; wire + thicken in T-09',
    );
  });
});
