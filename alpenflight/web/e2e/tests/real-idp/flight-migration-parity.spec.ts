import { existsSync } from 'node:fs';

import {
  test,
  expect,
  type Browser,
  type BrowserContext,
  type Page,
  type TestInfo,
} from '@playwright/test';

import type { FlightDetail, FlightUpdateRequest } from '../../../src/app/api/generated/model';

import { selectAfOption } from '../_helpers/af-select';
import {
  loginAsClubAdmin,
  provisionTwoClubs,
  type TwoClubFixture,
} from './_helpers/two-club-fixture';
import {
  AEROTOW_START_TYPE_ID,
  loginAsMigratedAdmin,
  seedFlightMasterdata,
  seedFlightParity,
  type FlightMasterdata,
  type FlightParityFixture,
} from './_helpers/flight-parity-fixture';
import { proofVideo } from './_helpers/proof-video';

/**
 * J-2 T-09 — the Flight list+edit real chain (live Keycloak auth + real Spring
 * backend + real Postgres). NO mocking on the happy + key-error paths: a
 * `page.route` interception would defeat the seam (the @TenantId filter, the
 * paired glider↔tow save, the time-gate policy, the 412 conflict path, and the
 * migration ingest + Keycloak provisioning must all run live).
 *
 * Two fidelities, both green at the gate:
 *   - CLEAN-SEED real chain (`provisionTwoClubs` + `seedFlightMasterdata`): a
 *     club admin logs in, lists /flights, creates a GLIDER flight via the 3-step
 *     wizard driving start-type = AEROTOW EXPLICITLY (so the paired-tow branch is
 *     taken deterministically); the glider row + a distinct linked TOW row both
 *     appear (glider.towFlightId). A MOTOR flight is created/listed in the SAME
 *     unified /flights list (a flight with a motor aircraft + no tow) — legacy's
 *     separate /airmovements screen is NOT carried forward. Edit persists. Delete
 *     leaves the list. A cross-tenant flight GET 404s. A DeliveryBooked flight is
 *     read-only (edit/delete → 4xx). A stale If-Match PUT (412) opens the inline
 *     per-field conflict diff (keep-mine/keep-theirs, first field focused, no
 *     auto-retry).
 *   - MIGRATED-DATA real chain (`seedFlightParity`): a real (synth/real) legacy
 *     Flight + FlightCrew bundle is migrated through the REAL migration endpoint
 *     and the migrated glider (crew + tow link) renders in the owning club's
 *     /flights list under the migrated immatriculation.
 *
 * Tenancy/gate parity (J-2-flight-list-edit.md § Parity decisions, legacy-oracle
 * 2026-06-03 + operator): single-flight GET/PUT/DELETE scope by @TenantId →
 * cross-tenant 404 (NOT the aircraft 403 managing-club shape, which is a catalog
 * read); read-only targets DeliveryBooked(60), NOT Locked; the time-gate keys on
 * flight_date (≥2d lock) / locked_at (≥3d bill), a deliberate divergence from
 * legacy CreatedOn; 412 is a net-new affordance, not parity.
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
 * Drive the 3-step wizard to create a GLIDER flight with start-type = AEROTOW
 * EXPLICITLY (Launch → Glider → Tow). Because AEROTOW makes `needsTow` true, the
 * Tow step renders and the tow flight is created + linked in the SAME save (the
 * paired glider↔tow save, S-063 parity). Returns the created GLIDER flight's id
 * (read from the rendered list row matched by its resolved immatriculation).
 *
 * Selects EVERY field off the seeded masterdata so the create is deterministic
 * (the clean realm has no defaults to fall back on).
 */
async function createGliderFlightAerotow(
  page: Page,
  md: FlightMasterdata,
  comment: string,
  // J-2 T-43: when provided, capture the gallery's glider+tow WIZARD parity
  // screenshots (glider step + tow step, both populated) on the way through —
  // so the gallery shows the paired-create UX a single edit-form PNG hides.
  shots?: { testInfo: TestInfo },
): Promise<string> {
  await page.goto('/flights/new');
  await expect(page.getByTestId('flight-form')).toBeVisible();
  await expect(page.getByTestId('flight-step-launch')).toBeVisible();

  // Step 0 (Launch): drive start-type = AEROTOW explicitly so the paired-tow
  // branch is taken (the T-01 stub took whatever the new-template defaulted to;
  // the oracle requires the AEROTOW branch be exercised deterministically).
  await selectAfOption(page, 'flight-edit-startType', AEROTOW_START_TYPE_ID);
  await selectAfOption(page, 'flight-edit-startLocation', md.locationId);

  // Step 1 (Glider): aircraft / flight type / pilot / comment.
  await page.getByTestId('flight-step-next').click();
  await expect(page.getByTestId('flight-step-glider')).toBeVisible();
  await selectAfOption(page, 'flight-edit-glider-aircraft', md.gliderAircraftId);
  await selectAfOption(page, 'flight-edit-glider-flightType', md.gliderFlightTypeId);
  await selectAfOption(page, 'flight-edit-glider-pilot', md.pilotPersonId);
  await page.getByTestId('flight-edit-glider-startTime').locator('input').fill('09:00');
  await page.getByTestId('flight-edit-glider-ldgTime').locator('input').fill('10:30');
  await page.getByTestId('flight-edit-glider-comment').locator('input').fill(comment);

  // J-2 T-43 — OPTIONAL glider-step parity screenshot (populated). Captured here
  // (glider step filled, before advancing to Tow) so the gallery can show the
  // glider half of the paired-create wizard alongside the tow half below.
  if (shots) {
    await page.screenshot({
      path: `${shots.testInfo.outputDir}/alpenflight-flights-wizard-glider.png`,
      fullPage: true,
    });
  }

  // Step 2 (Tow): AEROTOW → the Tow step renders; pick the tow aircraft + pilot.
  await page.getByTestId('flight-step-next').click();
  await expect(page.getByTestId('flight-step-tow')).toBeVisible();
  await selectAfOption(page, 'flight-edit-tow-aircraft', md.towAircraftId);
  await selectAfOption(page, 'flight-edit-tow-pilot', md.towPilotPersonId);

  // J-2 T-43 — the gallery's PRIMARY paired-create parity shot: the glider+tow
  // wizard AT THE TOW STEP, populated (tow aircraft + tow pilot selected). This
  // is the legacy-form ↔ AlpenFlight-wizard pairing's AlpenFlight half — a single
  // edit-form PNG hides that create is a 3-step Launch → Glider → Tow flow, so we
  // capture the distinctive Tow step here. Written BEFORE submit so the populated
  // tow step is on the PNG (after submit the wizard navigates back to /flights).
  // fullPage, no dependence on a single widget box (T-42 robustness lesson).
  if (shots) {
    await expect(page.getByTestId('flight-step-tow')).toBeVisible();
    await page.screenshot({
      path: `${shots.testInfo.outputDir}/alpenflight-flights-wizard-tow.png`,
      fullPage: true,
    });
  }

  const created = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      new URL(r.url()).pathname === '/api/v1/flights' &&
      r.status() >= 200 &&
      r.status() < 300,
    { timeout: 5_000 },
  );
  await page.getByTestId('flight-submit-header').click();
  await created;
  await expect(page).toHaveURL(/\/flights$/);

  // The new glider flight renders with the glider aircraft's immatriculation.
  const row = page
    .locator('[data-testid^="flights-row-"]')
    .filter({ has: page.locator(`text="${md.gliderImmat}"`) })
    .first();
  await expect(row, 'created glider flight must appear in the list').toBeVisible();
  const testId = await row.getAttribute('data-testid');
  expect(testId, 'flight row must carry a flights-row-<id> testid').toBeTruthy();
  return testId!.replace(/^flights-row-/, '');
}

/**
 * Drive the SAME /flights wizard to create a MOTOR flight ("air movement"):
 * select the seeded MOTOR aircraft in the primary-aircraft step and DON'T pick
 * an aerotow start type → the wizard infers `flightAircraftType = MOTOR` from
 * the selected motor aircraft and suppresses the tow step (a motor flight never
 * tows). NO /airmovements navigation — motor flights are unified into /flights
 * (legacy's separate /airmovements screen is NOT carried forward). Returns the
 * created flight's id + its POST-body flightAircraftType.
 */
async function createMotorFlight(
  page: Page,
  md: FlightMasterdata,
  comment: string,
  // J-2 T-43: when provided, capture the gallery's MOTOR create parity
  // screenshot — the unified /flights wizard with a motor aircraft selected and
  // the tow step suppressed (the AlpenFlight half of the legacy /airmovements ↔
  // AlpenFlight unified-motor pairing).
  shots?: { testInfo: TestInfo },
): Promise<{ id: string; flightAircraftType: string }> {
  await page.goto('/flights/new');
  await expect(page.getByTestId('flight-form')).toBeVisible();
  await expect(page.getByTestId('flight-step-launch')).toBeVisible();

  // Step 0 (Launch): start location only — leave the start type at the
  // non-aerotow default so no tow step is ever introduced.
  await selectAfOption(page, 'flight-edit-startLocation', md.locationId);

  // Step 1 (primary aircraft): pick the MOTOR aircraft. Selecting a motor
  // aircraft is what stamps the flight MOTOR (and keeps it tow-less) — no
  // separate route / variant.
  await page.getByTestId('flight-step-next').click();
  await expect(page.getByTestId('flight-step-glider')).toBeVisible();
  await selectAfOption(page, 'flight-edit-glider-aircraft', md.motorAircraftId);
  await selectAfOption(page, 'flight-edit-glider-flightType', md.gliderFlightTypeId);
  await selectAfOption(page, 'flight-edit-glider-pilot', md.pilotPersonId);
  await page.getByTestId('flight-edit-glider-startTime').locator('input').fill('11:00');
  await page.getByTestId('flight-edit-glider-ldgTime').locator('input').fill('12:00');
  await page.getByTestId('flight-edit-glider-comment').locator('input').fill(comment);

  // A motor flight never tows: the tow step must NOT render for the
  // motor-aircraft selection.
  await expect(page.getByTestId('flight-step-tow')).toHaveCount(0);

  // J-2 T-43 — the gallery's MOTOR-UNIFICATION parity shot: the unified /flights
  // wizard, populated, with a MOTOR aircraft selected and the tow step suppressed
  // (asserted absent just above). This is the AlpenFlight half of the legacy
  // /airmovements ↔ AlpenFlight motor-create pairing — it shows that legacy's
  // SEPARATE /airmovements screen is unified into the same /flights wizard.
  // Captured BEFORE submit so the populated motor form is on the PNG (after
  // submit the wizard navigates back to /flights). fullPage, T-42 robustness.
  if (shots) {
    await page.screenshot({
      path: `${shots.testInfo.outputDir}/alpenflight-motor-form.png`,
      fullPage: true,
    });
  }

  const created = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      new URL(r.url()).pathname === '/api/v1/flights' &&
      r.status() >= 200 &&
      r.status() < 300,
    { timeout: 5_000 },
  );
  await page.getByTestId('flight-submit-header').click();
  const createdResp = await created;
  await expect(page).toHaveURL(/\/flights$/);

  const body = (await createdResp.json()) as { id: string; flightAircraftType: string };
  return body;
}

/**
 * Project a `FlightDetail` (GET response) onto a minimal `FlightUpdateRequest`
 * (PUT body), with `comment` overridden. Mirrors the edit form's own update
 * mapper (`flight-form.model.ts#snapshotToUpdateRequest` → `subFormToUpdate`):
 * the PUT body is the create surface MINUS the discriminator, and excludes the
 * detail's identity/read-only fields (`id`, `flightAircraftType`, `airState`,
 * `processStateId`, `version`). Used to drive the out-of-band concurrency
 * writer with a body the backend accepts regardless of detail-shape drift —
 * spreading the whole detail 400s on strict-deserialize (J-2 T-37).
 */
function detailToUpdateRequest(d: FlightDetail, comment: string): FlightUpdateRequest {
  const req: FlightUpdateRequest = {
    aircraftId: d.aircraftId,
    crew: d.crew,
    comment,
  };
  if (d.flightDate != null) req.flightDate = d.flightDate;
  if (d.startDateTime != null) req.startDateTime = d.startDateTime;
  if (d.ldgDateTime != null) req.ldgDateTime = d.ldgDateTime;
  if (d.startLocationId != null) req.startLocationId = d.startLocationId;
  if (d.ldgLocationId != null) req.ldgLocationId = d.ldgLocationId;
  if (d.flightTypeId != null) req.flightTypeId = d.flightTypeId;
  if (d.startTypeId != null) req.startTypeId = d.startTypeId;
  if (d.towFlightId != null) req.towFlightId = d.towFlightId;
  if (d.nrOfLdgs != null) req.nrOfLdgs = d.nrOfLdgs;
  if (d.outboundRoute != null) req.outboundRoute = d.outboundRoute;
  if (d.inboundRoute != null) req.inboundRoute = d.inboundRoute;
  if (d.flightCostBalanceTypeId != null) req.flightCostBalanceTypeId = d.flightCostBalanceTypeId;
  if (d.couponNumber != null) req.couponNumber = d.couponNumber;
  if (d.engineStartOperatingCounterInSeconds != null) {
    req.engineStartOperatingCounterInSeconds = d.engineStartOperatingCounterInSeconds;
  }
  if (d.engineEndOperatingCounterInSeconds != null) {
    req.engineEndOperatingCounterInSeconds = d.engineEndOperatingCounterInSeconds;
  }
  req.isSoloFlight = d.isSoloFlight;
  req.noStartTimeInformation = d.noStartTimeInformation;
  req.noLdgTimeInformation = d.noLdgTimeInformation;
  return req;
}

// ===========================================================================
// CLEAN-SEED real chain — glider+tow+motor CRUD + tenant 404 + time-gate + 412.
// ===========================================================================
test.describe('Flight list+edit — clean-seed real chain (real-idp)', () => {
  // One realm + one backend; provision + seed once, run the asserts in order.
  // Serial keeps the sessions from racing the create→edit→delete ordering
  // (mirrors the J-1 aircraft spec).
  test.describe.configure({ mode: 'serial' });

  let fixture: TwoClubFixture;
  let baseURL: string;
  let masterdata: FlightMasterdata;
  /** The glider flight club A creates + manages; reused across CRUD + tenant cases. */
  let flightId: string;

  test.beforeAll(async ({ browser, request }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    // Spec-scoped admin usernames ('flt') so this fixture's club admins are
    // disjoint from the J-0 ('loc') / J-1 ('acft') specs when several run in one
    // `playwright test` invocation — ux_user_username_lower_alive.
    fixture = await provisionTwoClubs(browser, baseURL, 'flt');

    // The clean realm's club A has no aircraft / persons / locations /
    // flight-types — the wizard's dropdowns need real rows. Seed them through
    // the REAL create APIs (no mocking) as club A's admin.
    const ctx = await browser.newContext({ baseURL });
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      const bearer = await bearerFromFlightsList(page);
      masterdata = await seedFlightMasterdata(request, bearer);
    } finally {
      await ctx.close();
    }
  });

  test.afterAll(async () => {
    await fixture?.dispose();
  });

  test('club admin lists /flights and creates a glider flight (AEROTOW) with a linked tow', async ({
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

      // Create a glider flight via the wizard driving AEROTOW → the paired tow is
      // created + linked in the same save. Pass `shots` so the create captures the
      // gallery's glider+tow WIZARD parity PNGs (glider step + the distinctive
      // populated Tow step) on the way through — J-2 T-43.
      flightId = await createGliderFlightAerotow(page, masterdata, 'created by J-2 e2e', {
        testInfo,
      });
      expect(flightId).toMatch(/^fl-[0-9a-f-]{36}$/);

      // The glider row resolves its glider immatriculation.
      await expect(page.getByTestId(`flights-immat-${flightId}`)).toHaveText(
        masterdata.gliderImmat,
      );

      // Paired-tow assertion (S-063): the create made a DISTINCT TOW-type row and
      // the glider's towFlightId points at it. Read both off the API (the link is
      // not a list column) using the same principal's Bearer.
      const bearer = await bearerFromFlightsList(page);
      const detail = await ctx.request.get(`/api/v1/flights/${flightId}`, {
        headers: { authorization: bearer },
      });
      expect(detail.status(), 'the created glider flight is readable').toBe(200);
      const gliderBody = (await detail.json()) as { towFlightId?: string };
      expect(
        gliderBody.towFlightId,
        'the AEROTOW glider must carry a towFlightId (paired save, S-063)',
      ).toBeTruthy();
      const towDetail = await ctx.request.get(`/api/v1/flights/${gliderBody.towFlightId}`, {
        headers: { authorization: bearer },
      });
      expect(towDetail.status(), 'the linked tow flight is readable').toBe(200);
      const towBody = (await towDetail.json()) as {
        flightAircraftType: string;
        aircraftId: string;
      };
      expect(towBody.flightAircraftType, 'the linked flight is a distinct TOW row').toBe('TOW');
      expect(towBody.aircraftId, 'the tow row flies the tow aircraft').toBe(
        masterdata.towAircraftId,
      );

      // A populated list screenshot for the gallery (diagnostic; the testid
      // asserts above are the real check — CLAUDE.md §8).
      await page.goto('/flights');
      await expect(page.getByTestId('flights-table')).toBeVisible();
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-flights-list.png`,
        fullPage: true,
      });

      // J-2 T-43 — SELF-GUARD (mirrors the legacy spec's T-42 guard): the
      // gallery-declared AlpenFlight parity PNGs MUST have landed. The fanout
      // staging only DECLARES screenshots it can `find`, so a skipped-but-expected
      // capture would be silently absent from the gallery instead of red. Assert
      // the three this test owns (the populated list + the glider+tow wizard pair)
      // so a missed capture is a loud failure here, not a hidden gallery gap.
      for (const png of [
        'alpenflight-flights-list.png',
        'alpenflight-flights-wizard-glider.png',
        'alpenflight-flights-wizard-tow.png',
      ]) {
        expect(
          existsSync(`${testInfo.outputDir}/${png}`),
          `expected AlpenFlight parity screenshot ${png} in the test output dir — ` +
            "the fanout gallery's J-2 AlpenFlight half depends on it",
        ).toBeTruthy();
      }
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-2',
        caption:
          'J-2 · flight logbook · club admin logs in via real Keycloak, lists /flights, and creates ' +
          'a glider flight via the 3-step wizard with start-type AEROTOW — the paired tow is created ' +
          'and linked in the same save (glider.towFlightId → a distinct TOW row)',
        acTag: 'happy',
      });
    }
  });

  test('club admin creates a motor flight (MOTOR aircraft, no tow) that lists in /flights', async ({
    browser,
  }, testInfo) => {
    // DESIGN TRUTH (J-2 T-36): AlpenFlight unifies motor flights into the SAME
    // /flights list — there is NO separate /airmovements screen (only the legacy
    // flsweb app split them out). A motor flight is just a Flight with a motor
    // aircraft + no tow, created via the same /flights wizard. This test mirrors
    // the green glider create exactly, but selects the MOTOR aircraft.
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      // The same logbook the glider create lists into.
      await page.goto('/flights');
      await expect(page.locator('h1')).toHaveText('Flights');
      await expect(page.getByTestId('flights-table')).toBeVisible();

      // Create a motor flight via the unified wizard: select the motor aircraft,
      // no aerotow → no tow step. The wizard infers flightAircraftType = MOTOR
      // from the selected motor aircraft.
      // Pass `shots` so the create captures the gallery's MOTOR create parity PNG
      // (the unified /flights wizard, motor aircraft selected, tow suppressed) —
      // the AlpenFlight half of the legacy /airmovements ↔ unified-motor pairing
      // (J-2 T-43).
      const createdBody = await createMotorFlight(page, masterdata, 'motor by J-2 e2e', {
        testInfo,
      });

      // DECISIVE FACT (unified-design correctness): the flight created from the
      // /flights wizard with a motor aircraft selected MUST be stamped
      // flightAircraftType=MOTOR. Assert it off the POST body before the list
      // assertion so a mis-stamp fails here with the actual type in the message.
      expect(createdBody.id, 'the motor create POST returns the created flight id').toMatch(
        /^fl-[0-9a-f-]{36}$/,
      );
      expect(
        createdBody.flightAircraftType,
        'a flight created with a motor aircraft selected must be stamped MOTOR — not GLIDER',
      ).toBe('MOTOR');

      // The motor flight renders in the unified /flights list under its immat,
      // with the aircraft-type cell showing "Motor".
      const row = page
        .locator('[data-testid^="flights-row-"]')
        .filter({ has: page.locator(`text="${masterdata.motorImmat}"`) })
        .first();
      await expect(
        row,
        'the created motor flight appears in the unified /flights list',
      ).toBeVisible();
      const motorId = (await row.getAttribute('data-testid'))!.replace(/^flights-row-/, '');
      await expect(page.getByTestId(`flights-aircraft-type-${motorId}`)).toContainText('Motor');

      // J-2 T-43 — SELF-GUARD: the MOTOR create parity PNG (unified /flights
      // wizard, motor aircraft, tow suppressed) must have landed for the gallery's
      // legacy /airmovements ↔ AlpenFlight motor-create pairing.
      expect(
        existsSync(`${testInfo.outputDir}/alpenflight-motor-form.png`),
        'expected AlpenFlight parity screenshot alpenflight-motor-form.png in the test output dir — ' +
          "the fanout gallery's J-2 legacy-/airmovements ↔ AlpenFlight-motor pairing depends on it",
      ).toBeTruthy();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-2',
        caption:
          'J-2 · motor flight · a motor flight (motor aircraft, no tow) is created via the unified ' +
          '/flights wizard and renders in the SAME /flights list with aircraft type "Motor" — ' +
          "legacy's separate /airmovements screen is NOT carried forward",
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

      // Edit the glider-step comment + save.
      const updated = page.waitForResponse(
        (r) =>
          r.request().method() === 'PUT' &&
          new URL(r.url()).pathname === `/api/v1/flights/${flightId}` &&
          r.status() === 200,
        { timeout: 5_000 },
      );
      await page.getByTestId('flight-step-next').click();
      await expect(page.getByTestId('flight-step-glider')).toBeVisible();
      await page
        .getByTestId('flight-edit-glider-comment')
        .locator('input')
        .fill('edited by J-2 e2e');
      await page.getByTestId('flight-submit-header').click();
      await updated;
      await expect(page).toHaveURL(/\/flights$/);

      // Persistence round-trip — reopen the edit form, the comment persisted.
      await page.goto(`/flights/${flightId}/edit`);
      await page.getByTestId('flight-step-next').click();
      await expect(page.getByTestId('flight-edit-glider-comment').locator('input')).toHaveValue(
        'edited by J-2 e2e',
      );
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

      // Create a throwaway glider flight to delete (keeps the suite's primary
      // flightId intact for the tenant / 412 cases below).
      const victimId = await createGliderFlightAerotow(page, masterdata, 'to-delete by J-2 e2e');

      // Kebab → Delete → confirm dialog → confirm. The row leaves the list.
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
      // Club B's admin: a different tenant. Flights are NOT a cross-tenant catalog
      // (unlike aircraft, which 200s the read + 403s the write) — a single-flight
      // GET from another tenant must 404 (structural @TenantId scope, ADR 0008),
      // closing the legacy GetFlight(id) no-ClubId-filter leak (oracle #24).
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

  test('412 optimistic concurrency: a stale PUT opens the inline per-field conflict diff', async ({
    browser,
  }, testInfo) => {
    expect(flightId, 'create must have run first').toBeTruthy();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      // Net-new affordance (NOT legacy parity — legacy Flight is last-write-wins,
      // oracle #25). Open the edit form, then race a SECOND writer (a direct PUT
      // with the same principal's Bearer) so the form's in-hand version goes
      // stale. Saving issues a PUT with the old If-Match → 412 → the inline
      // per-field conflict diff opens.
      const bearer = await bearerFromFlightsList(page);

      // Read the current detail to learn its version + a value to mutate.
      const before = await ctx.request.get(`/api/v1/flights/${flightId}`, {
        headers: { authorization: bearer },
      });
      expect(before.status()).toBe(200);
      const detail = (await before.json()) as FlightDetail;

      // Open the edit form (the store now holds version N).
      await page.goto(`/flights/${flightId}/edit`);
      await expect(page.getByTestId('flight-form')).toBeVisible();

      // Out-of-band writer bumps the server version to N+1, changing the comment.
      // Build a PROPER minimal FlightUpdateRequest (NOT a spread of the whole
      // FlightDetail). The GET projection carries identity/read-only fields the
      // PUT body rejects — `id`, `flightAircraftType`, `airState`, `processStateId`,
      // `version` — none of which exist on FlightUpdateRequest (DTOs ≠ entities,
      // CLAUDE.md). Spreading them makes the backend strict-deserialize reject the
      // body with 400 "Request body could not be parsed" (J-2 T-37: T-36 changed the
      // created glider's detail shape, exposing this latent fragility). Mirror the
      // edit form's own PUT body (flight-form.model.ts#snapshotToUpdateRequest →
      // subFormToUpdate: the create surface minus the discriminator) so the
      // out-of-band write is robust regardless of detail-shape drift.
      const conflictingComment = 'changed-by-other-writer';
      const oobBody = detailToUpdateRequest(detail, conflictingComment);
      const oob = await ctx.request.put(`/api/v1/flights/${flightId}`, {
        headers: {
          authorization: bearer,
          'content-type': 'application/json',
          'If-Match': String(detail.version),
        },
        data: oobBody,
      });
      expect(oob.status(), 'the out-of-band write succeeds, bumping the server version').toBe(200);

      // Now the form's save issues a PUT with the STALE If-Match (version N) →
      // 412. Count PUTs to assert there is exactly ONE before the dialog (no
      // auto-retry — the user must resubmit).
      let putCount = 0;
      await page.route(`**/api/v1/flights/${flightId}`, async (route) => {
        if (route.request().method() === 'PUT') {
          putCount += 1;
        }
        await route.continue();
      });

      await page.getByTestId('flight-step-next').click();
      await expect(page.getByTestId('flight-step-glider')).toBeVisible();
      await page.getByTestId('flight-edit-glider-comment').locator('input').fill('my-local-edit');
      await page.getByTestId('flight-submit-header').click();

      // The inline per-field conflict diff opens (412 → keep-mine/keep-theirs).
      await expect(page.getByTestId('flight-conflict-dialog')).toBeVisible();
      // The `comment` field conflicts (mine vs theirs); both choices render.
      await expect(page.getByTestId('flight-conflict-field-comment')).toBeVisible();
      await expect(page.getByTestId('flight-conflict-keep-mine-comment')).toBeVisible();
      const keepTheirs = page.getByTestId('flight-conflict-keep-theirs-comment');
      await expect(keepTheirs).toContainText(conflictingComment);
      // The FIRST conflicting field's keep-mine control is focused on open (the
      // component focuses `#firstChoice`, the first rendered keep-mine button).
      // Assert generically on the first keep-mine control rather than on the
      // comment field specifically — the diff order is CONFLICT_FIELDS order, and
      // comment is not necessarily the first differing field.
      await expect(
        page.locator('[data-testid^="flight-conflict-keep-mine-"]').first(),
      ).toBeFocused();

      // No auto-retry: exactly one PUT fired before the dialog opened.
      expect(putCount, 'a 412 must NOT auto-retry — exactly one PUT before the dialog').toBe(1);

      // The 409 reload toast is NOT shown (a 412 is a data conflict, not a state
      // conflict — they are distinct paths, S-062h).
      await expect(page.getByTestId('flight-409-toast')).toHaveCount(0);

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-flights-conflict.png`,
        fullPage: true,
      });
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
// The migrated-flight render is the J-2 done-bar (full real chain). It ingests
// the T-08 flight parity bundle (glider+tow paired + motor + DeliveryBooked + a
// cross-tenant flight) through the REAL migration endpoint, provisions the
// migrated club admins (Keycloak), then asserts BOTH the migrated render AND the
// read-only consequence on the seeded DeliveryBooked flight.
test.describe('Flight list+edit — migrated legacy flight renders (real-idp)', () => {
  // retries: 0 unconditionally — the synth seed mints a fresh handshake/uploadId
  // per attempt, but the ingest provisions a NON-TERMINAL Deployment owned by
  // this spec's migration principal (`clubadmin2`). A Playwright retry re-runs
  // the ingest with a fresh uploadId, so the owner-active gate
  // (DeploymentProvisioningService#provision: findActiveByOwner) would 409
  // DEPLOYMENT_EXISTS on the PRIOR attempt's own Deployment — masking the real
  // first-attempt cause. retries:0 surfaces the real failure (J-1 T-17 rationale).
  test.describe.configure({ mode: 'serial', retries: 0 });

  let fixture: FlightParityFixture;
  let baseURL: string;

  test.beforeAll(async ({ browser, request }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    // Seeds through the REAL migration endpoint — ingest + Keycloak provision
    // both run live. The retry index mints a FRESH handshake/uploadId (synth) so
    // a retry after a failed ingest re-handshakes cleanly.
    fixture = await seedFlightParity(browser, request, baseURL, testInfo.retry);
  });

  test('the migrated glider flight (crew + tow link) renders in the owning club /flights list', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsMigratedAdmin(page, fixture.owner);
      await page.goto('/flights');
      await expect(page.getByTestId('flights-table')).toBeVisible();

      // The migrated glider flight renders under the migrated immatriculation
      // (HB-3000), resolved off the migrated aircraft. Identify the row by its
      // immat, then assert the tow link + crew via the API (the link is not a
      // list column).
      const row = page
        .locator('[data-testid^="flights-row-"]')
        .filter({ has: page.locator(`text="${fixture.gliderImmatriculation}"`) })
        .first();
      await expect(
        row,
        `migrated glider "${fixture.gliderImmatriculation}" must appear in the owning club's list`,
      ).toBeVisible();
      const rowId = (await row.getAttribute('data-testid'))!.replace(/^flights-row-/, '');
      expect(rowId, 'migrated flight row must carry a flights-row-<id> testid').toMatch(
        /^fl-[0-9a-f-]{36}$/,
      );

      // The migrated glider carries the tow link + crew (FlightCrewMapper).
      const bearer = await bearerFromFlightsList(page);
      const detail = await ctx.request.get(`/api/v1/flights/${rowId}`, {
        headers: { authorization: bearer },
      });
      expect(detail.status(), 'the migrated glider flight is readable in its own club').toBe(200);
      const body = (await detail.json()) as {
        flightAircraftType: string;
        towFlightId?: string;
        crew?: unknown[];
      };
      expect(body.flightAircraftType).toBe('GLIDER');
      expect(
        body.towFlightId,
        'the migrated glider carries the two-pass tow self-FK link',
      ).toBeTruthy();
      expect(
        (body.crew ?? []).length,
        'the migrated glider carries its migrated FlightCrew (pilot + co-pilot)',
      ).toBeGreaterThanOrEqual(2);

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-flights-migrated-list.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-2',
        caption:
          'J-2 · migrated flight · a real legacy Flight + FlightCrew, migrated through the live ' +
          "migration endpoint, renders in the owning club's /flights list under its immatriculation " +
          '(crew + tow link; full legacy→migrate→Keycloak→UI chain)',
        acTag: 'happy',
      });
    }
  });

  test('a migrated DeliveryBooked flight is read-only: edit + delete return 4xx (oracle #12)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsMigratedAdmin(page, fixture.owner);
      const bearer = await bearerFromFlightsList(page);

      // ----------------------------------------------------------------------
      // Time-gate (CRITICAL): the lock transition (Valid→Locked) is driven by
      // the LOCK_JOB trigger, NOT an OPERATOR HTTP endpoint (T-02 finding), so
      // this e2e does NOT try to drive a Valid→Locked lock through the UI/API —
      // the day-boundary gate is already proven at the IT layer
      // (FlightTimeGateIT). Here we assert the UI READ-ONLY CONSEQUENCE on the
      // seeded DeliveryBooked flight: per the corrected parity decision, the
      // read-only target is DeliveryBooked(60), NOT Locked (oracle #12 — a Locked
      // flight stays editable). This is the e2e half of the gate's effect.
      // ----------------------------------------------------------------------

      // Find the seeded DeliveryBooked flight (process state DELIVERY_BOOKED).
      const list = await ctx.request.get('/api/v1/flights', { headers: { authorization: bearer } });
      expect(list.status()).toBe(200);
      const items = ((await list.json()) as { items: { id: string; processState: string }[] })
        .items;
      const booked = items.find((f) => f.processState === 'DELIVERY_BOOKED');
      expect(
        booked,
        'the T-08 parity bundle seeds a DeliveryBooked(60) read-only flight',
      ).toBeTruthy();
      const bookedId = booked!.id;

      // Read it (still readable), then capture its version for the gated writes.
      const read = await ctx.request.get(`/api/v1/flights/${bookedId}`, {
        headers: { authorization: bearer },
      });
      expect(read.status(), 'a DeliveryBooked flight is still readable').toBe(200);
      const detail = (await read.json()) as Record<string, unknown> & { version: number };

      // PUT (edit) on a DeliveryBooked flight → 4xx state-gate reject.
      const put = await ctx.request.put(`/api/v1/flights/${bookedId}`, {
        headers: {
          authorization: bearer,
          'content-type': 'application/json',
          'If-Match': String(detail.version),
        },
        data: { ...detail, comment: 'attempt-edit-booked' },
      });
      expect(
        put.status(),
        'editing a DeliveryBooked flight must be rejected (4xx state gate)',
      ).toBeGreaterThanOrEqual(400);
      expect(put.status()).toBeLessThan(500);

      // DELETE on a DeliveryBooked flight → 4xx state-gate reject.
      const del = await ctx.request.delete(`/api/v1/flights/${bookedId}`, {
        headers: { authorization: bearer, 'If-Match': String(detail.version) },
      });
      expect(
        del.status(),
        'deleting a DeliveryBooked flight must be rejected (4xx state gate)',
      ).toBeGreaterThanOrEqual(400);
      expect(del.status()).toBeLessThan(500);

      // The SPA mirrors the gate: the DeliveryBooked row offers NO delete action
      // (the disabled affordance shows instead).
      await page.goto('/flights');
      await expect(page.getByTestId('flights-table')).toBeVisible();
      await page.getByTestId(`flights-kebab-${bookedId}`).click();
      await expect(page.getByTestId(`flights-delete-disabled-${bookedId}`)).toBeVisible();
      await expect(page.getByTestId(`flights-delete-${bookedId}`)).toHaveCount(0);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-2',
        caption:
          'J-2 · read-only state · a DeliveryBooked flight rejects edit + delete (4xx state gate; ' +
          'Locked stays editable per oracle #12). The Valid→Locked lock is LOCK_JOB-driven (proven ' +
          'at FlightTimeGateIT), so the e2e asserts the read-only consequence, not the transition.',
        acTag: 'key-error',
      });
    }
  });
});
