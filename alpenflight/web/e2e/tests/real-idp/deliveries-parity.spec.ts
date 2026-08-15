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
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  return context;
}

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
  const externalFlightId = new URL(loc, 'http://localhost').pathname.split('/').pop()!;
  const rawFlightIdTheSeederTakes = externalFlightId.replace(/^fl-/, '');
  return rawFlightIdTheSeederTakes;
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

async function seedDelivery(
  clubId: string,
  flightId: string,
  recipientLastName: string,
  batchId: number,
): Promise<{ deliveryId: string; articleNumber: string }> {
  const line = await runSeeder([clubId, flightId, recipientLastName, String(batchId)].join(' '));
  return JSON.parse(line) as { deliveryId: string; articleNumber: string };
}

async function deleteSeededDelivery(deliveryId: string): Promise<void> {
  await runSeeder(`delete ${deliveryId}`).catch(() => undefined);
}

test.describe('Deliveries — clean-seed read chain (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let baseURL: string;
  let adminBearer: string;
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

      await page.goto('/start?lang=en');
      await enterViaNav(page, '/deliveries');
      await expect(page).toHaveURL('/deliveries');
      await expect(page.getByTestId('del-table')).toBeVisible();

      const row = page.getByTestId(`del-row-${seeded.deliveryId}`);
      await expect(row).toBeVisible();
      await expect(row).toContainText(seeded.recipientLastName);
      await expect(page.getByTestId('del-table')).toContainText(String(seeded.batchId));
      await expect(page.getByTestId(`del-state-${seeded.deliveryId}`)).toBeVisible();

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-deliveries-list.png`,
        fullPage: true,
      });

      await row.click();
      await expect(page).toHaveURL(`/deliveries/${seeded.deliveryId}`);
      await expect(page.getByTestId('del-detail')).toBeVisible();

      const detail = (await ctx.request
        .get(`${DELIVERIES}/${seeded.deliveryId}`, { headers: { authorization: adminBearer } })
        .then((r) => r.json())) as DeliveryDetail;
      expect(detail.items.length).toBe(3);
      for (const [i] of detail.items.entries()) {
        await expect(page.getByTestId(`del-item-${i}`)).toBeVisible();
      }
      await expect(page.getByTestId('del-item-0')).toContainText('Flight time tier 1');
      await expect(page.getByTestId('del-item-2')).toContainText('Landing tax');

      await expect(page.getByTestId('del-recipient-lastName')).toContainText(
        seeded.recipientLastName,
      );
      await expect(page.getByTestId('del-recipient-city')).toContainText('Zürich');

      await expect(page.getByTestId('del-flight-link')).toBeVisible();
      await expect(page.getByTestId('del-state-badge')).toBeVisible();

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
      const ownRead = await ctx.request.get(`${DELIVERIES}/${seeded.deliveryId}`, {
        headers: { authorization: adminBearer },
      });
      expect(ownRead.status(), 'the owning tenant reads its own delivery').toBe(200);

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
