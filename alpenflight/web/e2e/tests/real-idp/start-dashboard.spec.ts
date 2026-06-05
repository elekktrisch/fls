import {
  test,
  expect,
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
  type TestInfo,
} from '@playwright/test';

import { fillKcLogin } from './_helpers/kc-form';

/**
 * J-3 — Dashboard / home (`/start`) role variants + live updates, REAL-IDP family.
 *
 * FULL REAL ASSERTIONS (T-16) against the SHOWCASE-SEEDED server. T-01 committed
 * the screen shape (the `data-testid` anchors below); this file thickens every
 * case to real assertions against the deterministic showcase seed
 * (`alpenflight/server/src/main/resources/showcase/README.md`):
 *   - PILOT variant → greeting + a FILLED last-flight card (pilot1 is PIC on 8
 *     club-1 flights, so the card resolves a real seeded flight, not empty state).
 *   - CLUB-ADMIN variant (club-1) → today-flights = 3, pending-validation = 4
 *     (the exact showcase club-1 numbers — see the README counts table).
 *   - SYSADMIN variant → total-clubs ≥ 2, total-flights ≥ 14, total-users ≥ 6,
 *     tenant-enter present (FLOOR assertions: the sysadmin endpoint counts the
 *     whole DB, so the showcase only guarantees the ≥2-club/≥14-flight floor).
 *   - SSE live update → clubadmin1 opens the dashboard (today=3), POSTs a club-1
 *     flight as clubadmin1 (the `flight.created` AFTER_COMMIT listener fires to
 *     the creating principal's sub → the open dashboard re-fetches), tile → 4
 *     WITHOUT a reload (DOM poll, not a network assertion).
 *   - SSE Bearer auth → `GET /api/v1/me/events` with no token → 401.
 *   - Role/tenant gating → the pilot sees NO admin/sysadmin containers; the
 *     clubadmin counts are club-1-scoped (3, not 3+club-2's 1).
 *
 * Why a NEW file rather than extending `tests/start/start.spec.ts`: that spec
 * lives under `tests/start/` which the Playwright config routes to the `chromium`
 * (mock-auth) project — `tests/!(real-idp)/**`. Its S-165 pilot assertions are
 * mock-stubbed (`page.route`) and must stay there, green and untouched. The J-3
 * variants are a REAL-IDP family (role-switched login against live Keycloak), so
 * they belong under `tests/real-idp/**` (the `real-idp` project) alongside the
 * other real-chain proof specs and their login harness.
 *
 * RUN CONTEXT (the showcase display run, NOT the clean-seed `alpenflight-proof`
 * job): this spec is run by the `alpenflight-dashboard-proof` CI job, which boots
 * the same real stack (bootJar + Keycloak + Postgres) and then loads the
 * `@Profile("showcase")` seed (`./gradlew seedShowcase`) before running it. The
 * clean-seed `alpenflight-proof` job deliberately does NOT run this spec and does
 * NOT load the showcase seed — adding showcase data there would pollute
 * J-0/J-1/J-2's exact-count assertions.
 *
 * Login: drives the Flyway+realm-seeded SHOWCASE principals through the SPA's
 * real Keycloak redirect-login (NOT a freshly-registered `e2e-…` user) so each
 * role variant is reachable deterministically (see the README principal matrix):
 *   - PILOT                → `pilot1@example.com`    (PIC on 8 club-1 flights)
 *   - CLUB_ADMINISTRATOR   → `clubadmin1@example.com` (club-1: 3 today / 4 pending)
 *   - SYSTEM_ADMINISTRATOR → `sysadmin@example.com`  (cross-tenant aggregate)
 */

interface SeededPrincipal {
  username: string;
  password: string;
}

// Showcase principals (alpenflight/auth/realm-export.json + the Flyway dev-user
// seed). Passwords are the canned dev passwords carried in the realm export.
const PILOT: SeededPrincipal = {
  username: 'pilot1@example.com',
  password: 'pilot1-dev-2026!',
};
const CLUB_ADMIN: SeededPrincipal = {
  username: 'clubadmin1@example.com',
  password: 'clubadmin1-dev-2026!',
};
const SYSADMIN: SeededPrincipal = {
  username: 'sysadmin@example.com',
  password: 'sysadmin-dev-2026!',
};

// ── Showcase club-1 expected dashboard numbers (README counts table) ──────────
// These are EXACT (deterministic seed, club-1 scoped). The sysadmin aggregate is
// a FLOOR (whole-DB count): the showcase contributes 2 clubs / 14 flights / 6
// principals, but dev seeds / migrated data may add more.
const CLUB1_TODAY_FLIGHTS = 3;
const CLUB1_PENDING_VALIDATION = 4;
const CLUB1_TODAY_AFTER_CREATE = CLUB1_TODAY_FLIGHTS + 1; // 4 — after the SSE-drive create
const SYSADMIN_MIN_CLUBS = 2;
const SYSADMIN_MIN_FLIGHTS = 14;
const SYSADMIN_MIN_USERS = 6;

// The showcase club-1 motor aircraft (README T-03a: AIRCRAFT_C1_MOTOR, HB-MOT1)
// — resolved by immatriculation off the tenant-scoped /api/v1/aircraft list so
// the spec never hardcodes the seed UUID (robust against an id-band re-pick).
const CLUB1_MOTOR_IMMAT = 'HB-MOT1';

/**
 * Log a seeded principal in through the real Keycloak redirect flow and land on
 * `/start`. Returns the authenticated context + page. Mirrors the redirect-login
 * dance in the J-2 flight-parity fixture: click the landing sign-in, fill the KC
 * form, wait for the post-login redirect back to the SPA, then navigate to
 * `/start`.
 */
async function loginAsRole(
  browser: Browser,
  baseURL: string,
  principal: SeededPrincipal,
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({ baseURL });
  const page = await context.newPage();
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, principal.username, principal.password);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
  await page.goto('/start?lang=en');
  return { context, page };
}

/**
 * Capture the Bearer the OIDC interceptor attaches to a `/api/v1/*` call for the
 * authenticated principal. The flights list is the principal's authed read
 * surface, so navigating to it guarantees a `/api/v1/*` request fires.
 */
async function captureBearer(page: Page): Promise<string> {
  const bearerPromise = page.waitForRequest(
    (req) =>
      req.url().includes('/api/v1/') &&
      typeof req.headers()['authorization'] === 'string' &&
      /^Bearer /i.test(req.headers()['authorization']!),
    { timeout: 10_000 },
  );
  await page.goto('/flights');
  const req = await bearerPromise;
  return req.headers()['authorization']!;
}

/** Today as a local `YYYY-MM-DD` — what the club-dashboard "today" count keys on. */
function todayLocalDate(): string {
  const now = new Date();
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/** `GET /api/v1/aircraft` list-item projection (the SPA's generated `AircraftListItem`). */
interface AircraftListItem {
  id: string;
  immatriculation: string;
}

/**
 * Resolve the club-1 motor aircraft id (HB-MOT1) off the caller's tenant-scoped
 * aircraft list — used to POST a real club-1 motor flight for the SSE drive.
 */
async function resolveClub1MotorAircraftId(
  api: APIRequestContext,
  bearer: string,
): Promise<string> {
  const res = await api.get('/api/v1/aircraft', { headers: { authorization: bearer } });
  expect(res.ok(), `GET /api/v1/aircraft (${res.status()})`).toBeTruthy();
  const list = (await res.json()) as AircraftListItem[];
  const motor = list.find((a) => a.immatriculation === CLUB1_MOTOR_IMMAT);
  expect(
    motor,
    `the showcase club-1 motor aircraft ${CLUB1_MOTOR_IMMAT} must be on the tenant aircraft list`,
  ).toBeTruthy();
  return motor!.id;
}

/**
 * POST a minimal MOTOR flight on `flightDate = today` as the given principal
 * (Bearer). The create fires `FlightCreatedEvent` → the `me` module's
 * AFTER_COMMIT listener publishes `flight.created` to the CREATING principal's
 * sub — so when the creator is the same clubadmin whose dashboard is open, that
 * dashboard's open SSE stream receives the nudge and re-fetches its counts. The
 * `flightDate = today` is what bumps the today-flights tile.
 */
async function createClub1TodayMotorFlight(
  api: APIRequestContext,
  bearer: string,
  aircraftId: string,
): Promise<void> {
  const res = await api.post('/api/v1/flights', {
    headers: { authorization: bearer, 'content-type': 'application/json' },
    data: {
      flightAircraftType: 'MOTOR',
      aircraftId,
      flightDate: todayLocalDate(),
      noStartTimeInformation: true,
      noLdgTimeInformation: true,
      isSoloFlight: false,
      comment: 'J-3 SSE live-update drive',
    },
  });
  expect(
    res.status(),
    `POST /api/v1/flights (motor, today) must 201 — body: ${await res.text().catch(() => '?')}`,
  ).toBe(201);
}

/**
 * Capture the 3 variant screenshots into the per-spec output dir under the stable
 * names the J-3 gallery sidecar declares (alpenflight-start-<variant>.png). The
 * `alpenflight-dashboard-proof` CI job stages these into the `--screenshots` dir
 * + writes `screenshots.json` so the gallery renders them under the J-3 section.
 * fullPage per CLAUDE.md §8 + the gallery's AlpenFlight-only (greenfield) policy.
 */
async function captureVariantShot(page: Page, testInfo: TestInfo, variant: string): Promise<void> {
  await page.screenshot({
    path: `${testInfo.outputDir}/alpenflight-start-${variant}.png`,
    fullPage: true,
  });
}

test.describe('J-3 dashboard (/start) — role variants [showcase seed, real chain]', () => {
  // ── 1. Pilot variant — greeting + FILLED last-flight card ───────────────────
  test('pilot principal renders the pilot variant with a populated last-flight card', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const { context, page } = await loginAsRole(browser, baseURL!, PILOT);
    try {
      await expect(page.getByTestId('start-variant-pilot')).toBeVisible();
      await expect(page.getByTestId('start-greeting')).toBeVisible();

      // pilot1 is PIC on 8 seeded club-1 flights (README T-03b), so the last-flight
      // card resolves a REAL flight — the FILLED card, not the empty state.
      await expect(page.getByTestId('start-last-flight-card')).toBeVisible();
      await expect(page.getByTestId('start-last-flight-empty')).toHaveCount(0);
      await expect(page.getByTestId('start-last-flight-error')).toHaveCount(0);
      // The card resolves a crew role for the viewing pilot (PIC on a real flight).
      await expect(page.getByTestId('start-last-flight-role')).not.toHaveText('—');

      // Role gating (AC6): a pilot sees NO admin/sysadmin variant containers.
      await expect(page.getByTestId('start-variant-clubadmin')).toHaveCount(0);
      await expect(page.getByTestId('start-variant-sysadmin')).toHaveCount(0);
      await expect(page.getByTestId('start-pilot-view-toggle')).toHaveCount(0);

      await captureVariantShot(page, testInfo, 'pilot');
    } finally {
      await context.close();
    }
  });

  // ── 2. Club-admin variant — exact club-1 showcase counts ────────────────────
  test('club-admin (club-1) renders today-flights=3 + pending-validation=4 (showcase club-1)', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const { context, page } = await loginAsRole(browser, baseURL!, CLUB_ADMIN);
    try {
      await expect(page.getByTestId('start-variant-clubadmin')).toBeVisible();

      // EXACT club-1 showcase numbers (README counts table). These are the club-1
      // scoped counts — NOT club-1 + club-2 (the tenant filter must scope to
      // club-1's 3 today, not 3 + club-2's 1 = 4) — which the SSE case re-proves.
      await expect(page.getByTestId('start-tile-today-flights-value')).toHaveText(
        String(CLUB1_TODAY_FLIGHTS),
      );
      await expect(page.getByTestId('start-tile-pending-validation-value')).toHaveText(
        String(CLUB1_PENDING_VALIDATION),
      );

      await captureVariantShot(page, testInfo, 'clubadmin');
    } finally {
      await context.close();
    }
  });

  // ── 3. Sysadmin variant — cross-tenant FLOOR aggregates + tenant-enter ───────
  test('sysadmin renders cross-tenant tiles (≥2 clubs / ≥14 flights / ≥6 users) + tenant-enter', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const { context, page } = await loginAsRole(browser, baseURL!, SYSADMIN);
    try {
      await expect(page.getByTestId('start-variant-sysadmin')).toBeVisible();

      // FLOOR assertions: the sysadmin endpoint counts the WHOLE DB, so the
      // showcase only guarantees the ≥2-club / ≥14-flight / ≥6-principal floor.
      const clubs = Number(
        (await page.getByTestId('start-tile-total-clubs-value').textContent())?.trim(),
      );
      const flights = Number(
        (await page.getByTestId('start-tile-total-flights-value').textContent())?.trim(),
      );
      const users = Number(
        (await page.getByTestId('start-tile-total-users-value').textContent())?.trim(),
      );
      expect(clubs, 'sysadmin total-clubs ≥ showcase 2-club floor').toBeGreaterThanOrEqual(
        SYSADMIN_MIN_CLUBS,
      );
      expect(flights, 'sysadmin total-flights ≥ showcase 14-flight floor').toBeGreaterThanOrEqual(
        SYSADMIN_MIN_FLIGHTS,
      );
      expect(users, 'sysadmin total-users ≥ showcase 6-principal floor').toBeGreaterThanOrEqual(
        SYSADMIN_MIN_USERS,
      );

      await expect(page.getByTestId('start-tenant-enter')).toBeVisible();

      await captureVariantShot(page, testInfo, 'sysadmin');
    } finally {
      await context.close();
    }
  });

  // ── 4. SSE live update — today-flights tile moves 3 → 4 without a reload ─────
  test('today-flights tile updates live (3 → 4) on flight.created without a reload', async ({
    browser,
    baseURL,
  }) => {
    const { context, page } = await loginAsRole(browser, baseURL!, CLUB_ADMIN);
    try {
      // Open the dashboard — the club-dashboard store opens the SSE stream and
      // the tile shows the showcase club-1 count (3).
      await expect(page.getByTestId('start-variant-clubadmin')).toBeVisible();
      const tile = page.getByTestId('start-tile-today-flights-value');
      await expect(tile).toHaveText(String(CLUB1_TODAY_FLIGHTS));

      // Capture the SAME principal's Bearer (a `/api/v1/*` request from a separate
      // nav) so the create runs as clubadmin1 — the AFTER_COMMIT listener fires
      // `flight.created` to the CREATING principal's sub, which is the same sub
      // whose dashboard SSE stream is open above. Use a second page in the same
      // context so navigating away to capture the Bearer does NOT close the
      // dashboard page holding the live SSE stream.
      const actorPage = await context.newPage();
      try {
        const bearer = await captureBearer(actorPage);
        const motorAircraftId = await resolveClub1MotorAircraftId(context.request, bearer);
        await createClub1TodayMotorFlight(context.request, bearer, motorAircraftId);
      } finally {
        await actorPage.close();
      }

      // Assert the tile re-fetched to 4 WITHOUT reloading the dashboard page (DOM
      // poll, not a network assertion). The AFTER_COMMIT listener + SSE delivery
      // + the store's re-fetch GET take a moment; a generous-but-bounded wait
      // (15s) covers the chain without `waitForTimeout`.
      await expect(tile).toHaveText(String(CLUB1_TODAY_AFTER_CREATE), { timeout: 15_000 });
    } finally {
      await context.close();
    }
  });

  // ── 5. SSE Bearer auth — anonymous stream is rejected (401) ──────────────────
  test('GET /api/v1/me/events with no token is rejected 401 (no anonymous stream)', async ({
    request,
  }) => {
    const res = await request.get('/api/v1/me/events', {
      headers: { accept: 'text/event-stream' },
    });
    expect(res.ok()).toBeFalsy();
    // The stream sits under `/api/v1/**` `.authenticated()` — the resource server
    // rejects a no-token request with 401 before the controller runs (T-04 has
    // landed, so this is the exact code, not the 404/403 the T-01 stub tolerated).
    expect(res.status()).toBe(401);
  });
});
