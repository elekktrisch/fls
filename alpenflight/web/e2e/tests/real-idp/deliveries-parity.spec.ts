import { execFile } from 'node:child_process';
import { resolve } from 'node:path';
import { promisify } from 'node:util';

import {
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type TestInfo,
} from '@playwright/test';
import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';

import { enterViaNav } from '../_helpers/nav';

import { seedFlightMasterdata, type FlightMasterdata } from './_helpers/flight-parity-fixture';
import { proofVideo } from './_helpers/proof-video';
import {
  loginAsReservationAdmin,
  captureReservationAdminBearer,
  SEED_CLUB_A_ID,
} from './_helpers/reservation-parity-fixture';
import {
  provisionTwoClubs,
  loginAsClubAdmin,
  type TwoClubFixture,
} from './_helpers/two-club-fixture';

/**
 * J-10 — Deliveries (invoice-draft) read screen, real chain (live Keycloak auth +
 * real Spring backend + real Postgres). The journey's `parity_test` (the real-chain
 * done-bar) — proves the READ-ONLY `/deliveries` viewer end to end over CLEAN-SEED
 * data (the Delivery migration is deferred to J-10b — it needs J-11's ARTICLE first).
 * NO `page.route` mocking on any path: the paged list, the view-by-id, the
 * `@TenantId` filter, and the CLUB_ADMINISTRATOR `@PreAuthorize` gate all run live.
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
  const context = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
  // Guard every page this context opens, not just the fixture-injected one.
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  return context;
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
