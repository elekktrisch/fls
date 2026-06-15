import { execFile } from 'node:child_process';
import { resolve } from 'node:path';
import { promisify } from 'node:util';

import {
  test,
  expect,
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type TestInfo,
} from '@playwright/test';

import { enterViaNav } from '../_helpers/nav';

import { seedFlightMasterdata, type FlightMasterdata } from './_helpers/flight-parity-fixture';
import { proofVideo } from './_helpers/proof-video';
import {
  loginAsReservationAdmin,
  captureReservationAdminBearer,
  resolveMigratedTestClubAdmin,
  loginAsMigratedTestClubAdmin,
  useRealBundle,
  SEED_CLUB_A_ID,
  type MigratedClubAdmin,
} from './_helpers/reservation-parity-fixture';
import {
  provisionTwoClubs,
  loginAsClubAdmin,
  type TwoClubFixture,
} from './_helpers/two-club-fixture';

/**
 * J-10 — Deliveries (invoice-draft) read screen, real chain (live Keycloak auth +
 * real Spring backend + real Postgres). The journey's `parity_test` (the real-chain
 * done-bar) — proves the READ-ONLY `/deliveries` viewer end to end. NO `page.route`
 * mocking on any path: the paged list, the view-by-id, the `@TenantId` filter, and
 * the CLUB_ADMINISTRATOR `@PreAuthorize` gate all run live.
 *
 * ── PRINCIPAL (CLUB_ADMINISTRATOR, every delivery endpoint is admin-gated) ─────
 * Drives `clubadmin4` (V29 seed), a REAL CLUB_ADMINISTRATOR bound to seed-club-1 —
 * NOT the mock-admin everything-principal that hides a role-authz gap
 * ([[project_real_idp_real_roles_catches_authz_gaps]]). The cross-tenant 404 probe
 * drives a REAL second club + admin (`provisionTwoClubs`).
 *
 * ── CLEAN-SEED (the Delivery write side ships in J-10b) ───────────────────────
 * There is NO create REST surface for Delivery this iteration, so the read
 * screen's clean-seed input is materialized directly against the live Postgres via
 * the Gradle `seedDelivery` task (the established DB-fixture seam, mirroring
 * `seedAircraftOwnerLink`): a Delivery + three line items + the frozen recipient,
 * under seed-club-1, linked to a Flight this spec creates over the REAL flight API.
 * The read endpoint + `@TenantId` scope + cross-tenant 404 then run fully real off
 * those rows — the seed is fixture STATE, not a mocked seam.
 *
 * ── REAL-IDP HYGIENE (hard-won) ──────────────────────────────────────────────
 *   - ENTER via the masterdata nav dropdown (`enterViaNav`), NOT a bare goto for
 *     the chrome-reachability assertion;
 *   - prefer WARM in-app navigation; do NOT `clearCookies` (kills session restore)
 *     ([[project_real_idp_goto_reboot_renew_stall]]);
 *   - read a created flight id off the 201 `Location` header
 *     ([[project_spa_nav_evicts_post_response_body]]);
 *   - track every seeded delivery + `afterAll` DELETE it so a Playwright retry
 *     starts on a clean seed-club-1.
 */

const FLIGHTS = '/api/v1/flights';
const DELIVERIES = '/api/v1/deliveries';

const CREW_TYPE_PILOT = '019e2e15-2c00-76b0-8000-0000000036b0';

const SERVER_DIR = resolve(__dirname, '../../../../server');
const GRADLEW = resolve(SERVER_DIR, 'gradlew');
const execFileAsync = promisify(execFile);

interface SeededDelivery {
  deliveryId: string;
  articleNumber: string;
  flightId: string;
  recipientLastName: string;
  batchId: number;
}

interface DeliveryDetail {
  id: string;
  deliveryNumber: number | null;
  batchId: number;
  processStateId: number;
  recipient: { firstName?: string | null; lastName?: string | null; city?: string | null };
  flight: { flightId: string } | null;
  items: {
    position: number;
    articleNumber: string;
    itemText?: string | null;
    quantity: number;
    unitType: string;
  }[];
}

async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  return browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
}

/**
 * Create a GLIDER flight (90-min, 1 landing, pilot crew) under the caller's tenant
 * via the REAL flight API; return its RAW uuid (the seeder UPDATEs the PK). The
 * delivery's `flight_id` FK is RESTRICT, so a real flight must exist first.
 */
async function seedFlight(api: APIRequestContext, bearer: string): Promise<string> {
  const md: FlightMasterdata = await seedFlightMasterdata(api, bearer);
  const res = await api.post(FLIGHTS, {
    headers: { authorization: bearer, 'content-type': 'application/json' },
    data: {
      flightAircraftType: 'GLIDER',
      aircraftId: md.gliderAircraftId,
      flightDate: '2026-05-15',
      startDateTime: '2026-05-15T08:00:00Z',
      ldgDateTime: '2026-05-15T09:30:00Z',
      startLocationId: md.locationId,
      ldgLocationId: md.locationId,
      flightTypeId: md.gliderFlightTypeId,
      nrOfLdgs: 1,
      nrOfLdgsOnStartLocation: 1,
      noStartTimeInformation: false,
      noLdgTimeInformation: false,
      isSoloFlight: false,
      crew: [{ personId: md.pilotPersonId, flightCrewTypeId: CREW_TYPE_PILOT }],
    },
  });
  expect(res.status(), `flight create must 201 — got ${res.status()}: ${await res.text()}`).toBe(
    201,
  );
  const loc = res.headers()['location']!;
  const external = new URL(loc, 'http://localhost').pathname.split('/').pop()!;
  // The flight id arrives external (`fl-<uuid>`); the seeder takes the raw uuid.
  return external.replace(/^fl-/, '');
}

async function runSeeder(seederArgs: string): Promise<string> {
  const { stdout } = await execFileAsync(
    GRADLEW,
    ['--quiet', 'seedDelivery', `-PseederArgs=${seederArgs}`],
    { cwd: SERVER_DIR, maxBuffer: 4 * 1024 * 1024 },
  );
  const line = stdout
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l.startsWith('{') && l.endsWith('}'))
    .pop();
  if (!line) {
    throw new Error(`delivery seeder produced no JSON result line; stdout was:\n${stdout}`);
  }
  return line;
}

/** Run the Gradle `seedDelivery` task — a Delivery + items + frozen recipient. */
async function seedDelivery(
  clubId: string,
  flightId: string,
  recipientLastName: string,
  batchId: number,
): Promise<{ deliveryId: string; articleNumber: string }> {
  const line = await runSeeder([clubId, flightId, recipientLastName, String(batchId)].join(' '));
  return JSON.parse(line) as { deliveryId: string; articleNumber: string };
}

/** Remove a seeded delivery + its items (retry pre-clean / afterAll cleanup). */
async function deleteSeededDelivery(deliveryId: string): Promise<void> {
  await runSeeder(`delete ${deliveryId}`).catch(() => undefined);
}

// ===========================================================================
// CLEAN-SEED real chain: nav-entry + list → view (line items + frozen recipient
// + flight link, NO write actions) → cross-tenant 404. seed-club-1 / clubadmin4.
// ===========================================================================
test.describe('Deliveries — clean-seed read chain (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let baseURL: string;
  /** clubadmin4's Bearer (seed-club-1, the @TenantId club). */
  let adminBearer: string;
  /** A real second club + admin (club B) — the cross-tenant-404 probe. */
  let twoClubs: TwoClubFixture;
  let seeded: SeededDelivery;
  let cleanupCtx: BrowserContext;

  test.beforeAll(async ({ browser }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    adminBearer = await captureReservationAdminBearer(browser, baseURL);
    twoClubs = await provisionTwoClubs(browser, baseURL, 'dlv');
    cleanupCtx = await browser.newContext({ baseURL });
    const flightId = await seedFlight(cleanupCtx.request, adminBearer);
    const recipientLastName = `Pilot${Date.now().toString(36).slice(-4)}`;
    const batchId = Number(Date.now().toString().slice(-7));
    const { deliveryId, articleNumber } = await seedDelivery(
      SEED_CLUB_A_ID,
      flightId,
      recipientLastName,
      batchId,
    );
    seeded = { deliveryId, articleNumber, flightId, recipientLastName, batchId };
  });

  test.afterAll(async () => {
    if (seeded) {
      await deleteSeededDelivery(seeded.deliveryId);
    }
    await cleanupCtx?.close();
    await twoClubs?.dispose();
  });

  test('[happy] nav → list renders the seeded delivery → view shows line items + frozen recipient + flight link, NO write actions', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);

      // ENTER via the chrome nav (the chrome-reachability AC): open the Masterdata
      // dropdown, click the nested Deliveries entry, the list renders.
      await page.goto('/start?lang=en');
      await enterViaNav(page, '/deliveries');
      await expect(page).toHaveURL('/deliveries');
      await expect(page.getByTestId('del-table')).toBeVisible();

      // The list carries the seeded delivery row (number-or-unbooked · recipient),
      // the batch, and the state badge — all tenant-scoped to seed-club-1.
      const row = page.getByTestId(`del-row-${seeded.deliveryId}`);
      await expect(row).toBeVisible();
      await expect(row).toContainText(seeded.recipientLastName);
      await expect(page.getByTestId('del-table')).toContainText(String(seeded.batchId));
      await expect(page.getByTestId(`del-state-${seeded.deliveryId}`)).toBeVisible();

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-deliveries-list.png`,
        fullPage: true,
      });

      // Click into the view — read-only line items + the frozen recipient + the
      // read-only flight link; NO book/delete affordance this iteration.
      await row.click();
      await expect(page).toHaveURL(`/deliveries/${seeded.deliveryId}`);
      await expect(page.getByTestId('del-detail')).toBeVisible();

      // The three seeded engine-shaped line items (position / article / text /
      // qty / unit) render read-only.
      const detail = (await ctx.request
        .get(`${DELIVERIES}/${seeded.deliveryId}`, { headers: { authorization: adminBearer } })
        .then((r) => r.json())) as DeliveryDetail;
      expect(detail.items.length).toBe(3);
      for (const [i] of detail.items.entries()) {
        await expect(page.getByTestId(`del-item-${i}`)).toBeVisible();
      }
      await expect(page.getByTestId('del-item-0')).toContainText('Flight time tier 1');
      await expect(page.getByTestId('del-item-2')).toContainText('Landing tax');

      // The frozen recipient snapshot renders read-only (empty fields are
      // suppressed, so assert on the populated ones the seed wrote).
      await expect(page.getByTestId('del-recipient-lastName')).toContainText(
        seeded.recipientLastName,
      );
      await expect(page.getByTestId('del-recipient-city')).toContainText('Zürich');

      // The read-only flight link + state badge.
      await expect(page.getByTestId('del-flight-link')).toBeVisible();
      await expect(page.getByTestId('del-state-badge')).toBeVisible();

      // READ-ONLY: there is NO book / delete affordance anywhere on the view.
      await expect(page.getByTestId('del-book-button')).toHaveCount(0);
      await expect(page.getByTestId('del-delete-button')).toHaveCount(0);

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-deliveries-form.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-10',
        caption:
          'J-10 · deliveries · a club admin logs in via real Keycloak, ENTERS via the masterdata nav, ' +
          'the /deliveries list renders the club’s invoice-draft delivery (recipient · batch · state) ' +
          'tenant-scoped, and the view shows the read-only engine line items + the frozen OR Art. 957a ' +
          'recipient snapshot + the linked flight — NO book/delete affordance (the read screen, J-10b ' +
          'owns the write side)',
        acTag: 'happy',
      });
    }
  });

  test('[edge] tenant isolation: club B cannot read seed-club-1’s delivery (cross-tenant GET → 404)', async ({
    browser,
  }) => {
    const ctx = await browser.newContext({ baseURL });
    try {
      // seed-club-1's admin reads it fine (the positive control — the id is valid).
      const ownRead = await ctx.request.get(`${DELIVERIES}/${seeded.deliveryId}`, {
        headers: { authorization: adminBearer },
      });
      expect(ownRead.status(), 'the owning tenant reads its own delivery').toBe(200);

      // Capture club B's admin Bearer through real Keycloak (a DIFFERENT tenant).
      const bCtx = await browser.newContext({ baseURL });
      const bPage = await bCtx.newPage();
      let clubBBearer: string;
      try {
        const reqPromise = bPage.waitForRequest(
          (req) =>
            req.url().includes('/api/v1/') &&
            typeof req.headers()['authorization'] === 'string' &&
            /^Bearer /i.test(req.headers()['authorization']!),
        );
        await loginAsClubAdmin(bPage, twoClubs.clubB);
        await bPage.goto('/deliveries');
        clubBBearer = (await reqPromise).headers()['authorization']!;
      } finally {
        await bCtx.close();
      }

      // Club B's admin CANNOT read it — the @TenantId-scoped finder never returns
      // another club's row → 404 (NOT 403; the row is invisible, not forbidden).
      const crossTenant = await ctx.request.get(`${DELIVERIES}/${seeded.deliveryId}`, {
        headers: { authorization: clubBBearer },
      });
      expect(
        crossTenant.status(),
        `club B must NOT read club A's delivery (cross-tenant 404) — got ${crossTenant.status()}`,
      ).toBe(404);
    } finally {
      await ctx.close();
    }
  });
});

// ===========================================================================
// MIGRATED-DATA real chain — the migrated done-bar (binds the Delivery +
// DeliveryItem mappers). The fanout's REAL legacy export migrates the TestClub's
// legacy Delivery/DeliveryItem rows; this block lists them under the migrated
// TestClub tenant and asserts the line items + frozen recipient render. Runs ONLY
// when the fanout's real export ran (J5_BUNDLE_SOURCE=real) — the synth bundle
// carries no Delivery rows, so a per-push run skips rather than false-greens.
// ===========================================================================
test.describe('Deliveries — migrated legacy deliveries render (real-idp)', () => {
  test.describe.configure({ mode: 'serial', retries: 0 });

  test.skip(
    !useRealBundle(),
    'migrated-delivery render requires the real legacy export (J5_BUNDLE_SOURCE=real, fanout only)',
  );

  let baseURL: string;
  let migratedAdmin: MigratedClubAdmin;
  let migratedBearer: string;

  test.beforeAll(async ({ browser }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    // Resolve the loginable admin of the migrated TestClub by OWNERSHIP (the
    // J-5/J-8 migrated-read pattern) — the migrated CLUB gets a fresh provisioned
    // UUID, so it can never be hardcoded.
    const resolved = await resolveMigratedTestClubAdmin(browser, baseURL);
    migratedAdmin = resolved.admin;
    migratedBearer = resolved.bearer;
  });

  test('[migration/parity] the migrated TestClub’s deliveries render with line items + frozen recipient', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsMigratedTestClubAdmin(page, migratedAdmin);

      // Page the migrated TestClub's deliveries (the paged-read the list issues).
      const pageRes = await ctx.request.post(`${DELIVERIES}/page/0/50`, {
        headers: { authorization: migratedBearer, 'content-type': 'application/json' },
        data: {},
      });
      expect(pageRes.status(), 'the migrated TestClub lists its migrated deliveries').toBe(200);
      const body = (await pageRes.json()) as {
        items: { id: string; deliveryNumber: number | null; recipientName: string }[];
      };
      expect(
        body.items.length,
        'the migrated TestClub must carry ≥1 migrated legacy delivery (the migrated done-bar — ' +
          'binds the Delivery + DeliveryItem mappers)',
      ).toBeGreaterThan(0);

      // Locate the deterministic seed (101 Insert Deliveries.sql): DeliveryNumber
      // 2026001 parses to the integer delivery_number, recipient "Wegmueller". Find
      // it by its known number rather than list position — ordering is not asserted.
      const seededRow = body.items.find((d) => d.deliveryNumber === 2026001);
      expect(
        seededRow,
        'the seeded TestClub delivery (delivery_number 2026001, recipient Wegmueller) must migrate',
      ).toBeTruthy();
      expect(seededRow!.recipientName).toContain('Wegmueller');

      // The migrated delivery's view round-trips its frozen line items + recipient
      // bit-exactly (a migrated invoice-draft is engine output frozen at delivery
      // time, never re-resolved). Re-GET, not the paged body, for the detail.
      const detail = (await ctx.request
        .get(`${DELIVERIES}/${seededRow!.id}`, { headers: { authorization: migratedBearer } })
        .then((r) => r.json())) as DeliveryDetail;

      // process_state_id 10 (Prepared): the source flight is Valid (legacy 30),
      // IsFurtherProcessed 0 → the producer's CASE falls through to 10.
      expect(detail.processStateId).toBe(10);

      // The three seeded line items migrate position-ordered, article-resolved.
      expect(detail.items.length).toBe(3);
      const byPosition = [...detail.items].sort((a, b) => a.position - b.position);
      expect(byPosition.map((i) => i.position)).toEqual([10, 20, 30]);
      expect(byPosition.map((i) => i.articleNumber)).toEqual(['5001', '6001', '5001']);
      expect(byPosition.map((i) => i.unitType)).toEqual(['Min', 'Ldgs', 'Min']);
      expect(byPosition.map((i) => Number(i.quantity))).toEqual([47, 1, 5]);
      expect(byPosition[0]!.itemText).toBe('Glider flight minutes');
      expect(byPosition[1]!.itemText).toBe('Landegebuehr LSZK');

      // The frozen 9-field recipient snapshot (OR Art. 957a passthrough).
      expect(detail.recipient.lastName).toBe('Wegmueller');
      expect(detail.recipient.city).toBe('Bern');

      // Render the list + view in the real screen.
      await page.goto(`/deliveries?lang=en`);
      await expect(page.getByTestId('del-table')).toBeVisible();
      await expect(page.getByTestId(`del-row-${seededRow!.id}`)).toBeVisible();
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-deliveries-migrated-list.png`,
        fullPage: true,
      });

      await page.getByTestId(`del-row-${seededRow!.id}`).click();
      await expect(page).toHaveURL(`/deliveries/${seededRow!.id}`);
      await expect(page.getByTestId('del-detail')).toBeVisible();
      await expect(page.getByTestId('del-item-0')).toBeVisible();
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-deliveries-migrated-view.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-10',
        caption:
          'J-10 · migrated deliveries · the /deliveries viewer, under the migrated TestClub tenant, ' +
          'renders the migrated legacy invoice-draft deliveries — the list rows + the view’s read-only ' +
          'line items + the frozen OR Art. 957a recipient snapshot — proving the Delivery + DeliveryItem ' +
          'migration mappers bind end to end over real migrated data (the migrated done-bar)',
        acTag: 'happy',
      });
    }
  });
});
