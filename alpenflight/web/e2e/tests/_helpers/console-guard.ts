import { test as base, expect, type Page, type TestInfo } from '@playwright/test';

/**
 * Suite-wide "no uncaught browser errors" guard.
 *
 * Every test that imports this `test` (instead of the raw `@playwright/test`
 * one) fails if the browser emitted a `console.error` or a `pageerror` that no
 * allowlist entry and no per-test opt-out covers. The guard is the standing
 * proof that a screen mounts, renders, and interacts WITHOUT throwing — a
 * regression the green-on-the-happy-path assertions silently miss (a component
 * can satisfy its visible assertions while logging a binding/zoneless error).
 *
 * It runs as an `auto` fixture: the assertion fires in teardown for every test,
 * with no per-spec `afterEach`. Specs that drive their OWN page (a
 * `browser.newContext().newPage()` rather than the injected `page`) opt that
 * page in with `watchConsoleErrors(page)` — the same per-test collector backs
 * both, so the single teardown assertion covers every watched page.
 */

/**
 * Curated benign patterns — framework / third-party noise the app does NOT own
 * and cannot fix. Seed CONSERVATIVELY: an app-caused error belongs in a bug
 * fix, never here. Each entry needs a one-line why. A `string` matches as a
 * substring; a `RegExp` is tested against the full message.
 *
 * NOTE: the `[af-icon]` unregistered-icon `console.error` and `[signup]`
 * authorize-failure log are APP errors the guard exists to catch — they are
 * deliberately absent.
 */
const BENIGN_PATTERNS: readonly (string | RegExp)[] = [
  // The dev server serves no favicon; the browser logs a resource-load error
  // for it on every cold load. Not app behaviour, not fixable here.
  /Failed to load resource.*favicon\.ico/i,
];

function isBenign(message: string): boolean {
  return BENIGN_PATTERNS.some((p) =>
    typeof p === 'string' ? message.includes(p) : p.test(message),
  );
}

/** Per-test bag of collected browser errors + the test's declared opt-out patterns. */
interface ConsoleGuard {
  readonly errors: string[];
  /** Caller-declared patterns for errors a test DELIBERATELY triggers. */
  readonly allowed: (string | RegExp)[];
}

/**
 * The `WeakMap<Page, …>` keys a collector to each watched page so a spec's own
 * `browser.newContext()` page funnels into the SAME per-test bag the auto
 * fixture asserts on. Keyed by `TestInfo` (one bag per running test); cleaned
 * up implicitly when the test object is collected.
 */
const guards = new WeakMap<TestInfo, ConsoleGuard>();

function guardFor(testInfo: TestInfo): ConsoleGuard {
  let g = guards.get(testInfo);
  if (!g) {
    g = { errors: [], allowed: [] };
    guards.set(testInfo, g);
  }
  return g;
}

/**
 * Subscribe `page` to the running test's error collector. The injected `page`
 * is wired automatically by the fixture; call this ONLY for a page from a
 * spec-owned context (`browser.newContext().newPage()`), which the fixture
 * cannot see. Idempotent enough for normal use — call once per page.
 */
export function watchConsoleErrors(page: Page, testInfo: TestInfo): void {
  const g = guardFor(testInfo);
  page.on('console', (msg) => {
    if (msg.type() !== 'error') return;
    const text = `console.error: ${msg.text()}`;
    if (!isBenign(text)) g.errors.push(text);
  });
  page.on('pageerror', (err) => {
    const text = `pageerror: ${err.message}`;
    if (!isBenign(text)) g.errors.push(text);
  });
}

/**
 * Declare error patterns the current test DELIBERATELY triggers (a 403/404/412/
 * 409 / optimistic-concurrency path the app legitimately logs). Matched errors
 * are excluded from the teardown assertion; everything else still fails the
 * test. Call inside the test body, before driving the error path. Prefer a
 * SPECIFIC pattern over a broad one so a real adjacent error still trips.
 */
export function allowConsoleErrors(testInfo: TestInfo, ...patterns: (string | RegExp)[]): void {
  guardFor(testInfo).allowed.push(...patterns);
}

function isAllowed(message: string, allowed: readonly (string | RegExp)[]): boolean {
  return allowed.some((p) => (typeof p === 'string' ? message.includes(p) : p.test(message)));
}

/**
 * The reference catalogs the authenticated session-bootstrap forkJoin
 * (`ReferenceDataStore.loadAll`, fanned out from `SessionStore.bootstrapPrefetch`)
 * pulls in parallel on every cold load. The mock-auth project runs with NO
 * backend, so each unstubbed GET falls through Vite's proxy to ECONNREFUSED →
 * 500 and the no-console-errors guard trips. Empty arrays satisfy the
 * `catchError`-per-stream contract — the store simply renders no rows.
 */
const BOOTSTRAP_REFERENCE_PATHS: readonly string[] = [
  '**/api/v1/countries**',
  '**/api/v1/club-states**',
  '**/api/v1/location-types**',
  '**/api/v1/aircraft-types**',
  '**/api/v1/aircraft-states**',
  '**/api/v1/counter-unit-types**',
];

/**
 * Register empty-array fulfillments for the bootstrap catalogs as the BASE
 * layer, before the test body runs its own `page.route` calls. Playwright tries
 * the most-recently-registered matching handler first, so a spec that needs
 * POPULATED reference data layers its own route on top and wins; a spec that
 * only needs the session to boot without 500s relies on these. Scoped to the
 * known catalog paths — NOT a blanket 200 — so a genuinely-broken request still
 * surfaces.
 */
async function installBootstrapReferenceStubs(page: Page): Promise<void> {
  for (const path of BOOTSTRAP_REFERENCE_PATHS) {
    await page.route(path, (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
    );
  }
}

/**
 * One default fulfillment for a per-screen resource the mock-auth session pulls
 * lazily after bootstrap (a list/picker/dashboard a screen loads on nav, or an
 * inline `validate` / `page` POST a form fires). With no backend each unstubbed
 * call falls through Vite's proxy to ECONNREFUSED → 500 and the guard trips, so
 * the same base-layer pattern as the bootstrap catalogs applies here: a minimal
 * SHAPE-CORRECT empty/valid body satisfies the generated client's type so the
 * stub never introduces a NEW console error, and a spec that needs real data
 * layers its own route on top and wins.
 *
 * The body shape mirrors the orval-generated response type the screen consumes:
 * list endpoints → `[]`; envelope endpoints (`FlightListResponse`,
 * `AircraftReservationPage`) → an empty-items object; object endpoints
 * (`/me/*`) → `{}` (all fields optional); `validate` POSTs → `{ valid: true }`
 * (`PlanningDayValidationResult` / `ReservationValidationResult`). The
 * no-row resources (`flights/last-context` + the kebab detail prefetch, from
 * which the flight store silently falls back, and `migrations/handshake/
 * current`) return 200 with a store-tolerated body rather than 404 — a stubbed
 * 404 still makes the browser emit a `Failed to load resource …404`
 * `console.error` that trips the guard.
 */
interface ResourceStub {
  readonly url: string | RegExp;
  readonly status: number;
  readonly body: string;
}

const PER_SCREEN_RESOURCE_STUBS: readonly ResourceStub[] = [
  { url: '**/api/v1/me/system-dashboard', status: 200, body: '{}' },
  { url: '**/api/v1/me/person/licences', status: 200, body: '{}' },
  { url: '**/api/v1/me/person', status: 200, body: '{}' },
  { url: '**/api/v1/me/club-membership/notification-prefs', status: 200, body: '{}' },
  { url: '**/api/v1/persons', status: 200, body: '[]' },
  { url: '**/api/v1/club/member-states', status: 200, body: '[]' },
  { url: '**/api/v1/locations', status: 200, body: '[]' },
  { url: '**/api/v1/flight-types', status: 200, body: '[]' },
  { url: '**/api/v1/flights/new-template**', status: 200, body: newTemplateBody() },
  { url: /\/api\/v1\/flights(\?|$)/, status: 200, body: '{"items":[]}' },
  // `last-context` + the kebab detail prefetch return 200/`null` (not 404) for
  // the no-row case: a 404 makes the browser emit `console.error: Failed to load
  // resource …404` which trips the guard, while the flight store's
  // null-tolerant fallback (`applyLastContextThenPrefs`) resolves 200/`null` to
  // the same no-row path with no console error.
  { url: /\/api\/v1\/flights\/last-context(\?|$)/, status: 200, body: 'null' },
  {
    url: /\/api\/v1\/flights\/(?!new-template|last-context)[^/?]+(\?|$)/,
    status: 200,
    body: 'null',
  },
  { url: '**/api/v1/planning-days/overview/future', status: 200, body: '[]' },
  { url: '**/api/v1/planning-days/validate', status: 200, body: '{"valid":true}' },
  { url: '**/api/v1/aircraft-reservations/validate', status: 200, body: '{"valid":true}' },
  {
    url: /\/api\/v1\/aircraft-reservations\/page\/\d+\/\d+/,
    status: 200,
    body: '{"items":[],"pageStart":0,"pageSize":50,"totalRows":0}',
  },
  { url: '**/api/v1/aircraft/picker', status: 200, body: '[]' },
  { url: '**/api/v1/aircraft-reservation-types', status: 200, body: '[]' },
  { url: '**/api/v1/migrations/handshake/current', status: 200, body: '{}' },
];

/**
 * `FlightTemplateResponse` carries required fields the flight-edit form binds
 * to, so an empty `{}` would surface an `undefined.flightAircraftType` read.
 * The minimal valid template seeds those required keys with empty/false
 * defaults — an unpopulated but well-typed new-flight surface.
 */
function newTemplateBody(): string {
  return JSON.stringify({
    flightAircraftType: 'GLIDER',
    isSoloFlight: false,
    noStartTimeInformation: false,
    noLdgTimeInformation: false,
    crew: [],
  });
}

async function installPerScreenResourceStubs(page: Page): Promise<void> {
  for (const stub of PER_SCREEN_RESOURCE_STUBS) {
    await page.route(stub.url, (route) =>
      route.fulfill({ status: stub.status, contentType: 'application/json', body: stub.body }),
    );
  }
}

/**
 * A `/api/v1/**` floor so NO mock-auth `/api/v1/*` request can reach Vite's
 * proxy → ECONNREFUSED → the `console.error` that trips the guard. The leak is a
 * timing race: a spec stubs the endpoints it asserts on, but a sibling/late XHR
 * (a picker resolving a link, a debounced refresh, a teardown-window request)
 * for an endpoint no specific stub covers fires before its route exists or after
 * the page settles, falls through, and 500s. Registered FIRST in the chromium
 * stub path so it is the LOWEST-priority handler — Playwright tries the
 * most-recently-registered match first, so every bootstrap / per-screen / spec
 * stub layered on top wins; the floor only answers the genuinely-unstubbed call.
 *
 * This is PREVENTION, not masking: it closes the proxy fall-through at the
 * source, so a real backend 500 (which the guard must still catch) is impossible
 * to confuse with a missing-stub artifact — there is no backend here, every
 * `/api/v1/*` is mocked, and the floor returns a benign success, never a 500.
 * A GET gets a shape-permissive empty body (`{}` parses as an empty object and
 * coerces to an empty list under the generated client's optional-field types);
 * a write gets `204` no-content. Real-idp specs hit the live backend untouched —
 * this is gated to the mock-auth project exactly like the other stubs.
 */
async function installApiFloor(page: Page): Promise<void> {
  await page.route('**/api/v1/**', (route) => {
    const method = route.request().method();
    if (method === 'GET') {
      route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
      return;
    }
    route.fulfill({ status: 204, body: '' });
  });
}

/**
 * The reference-data stubs answer for the absent backend, so they belong ONLY
 * to the no-backend mock-auth project (`chromium`). A real-idp spec drives the
 * live `/api/v1/*` backend; installing the stubs there would intercept its real
 * calls and let a leg pass on a fulfilled stub instead of the real response. The
 * console-error WATCH stays universal — it is the guard every project runs.
 */
const MOCK_AUTH_PROJECT = 'chromium';

export const test = base.extend<{ consoleGuard: void }>({
  consoleGuard: [
    async ({ page }, use, testInfo) => {
      watchConsoleErrors(page, testInfo);
      if (testInfo.project.name === MOCK_AUTH_PROJECT) {
        // FIRST so it is the lowest-priority handler — see installApiFloor.
        await installApiFloor(page);
        await installBootstrapReferenceStubs(page);
        await installPerScreenResourceStubs(page);
      }
      await use();

      const g = guardFor(testInfo);
      const unexpected = g.errors.filter((e) => !isAllowed(e, g.allowed));
      expect(
        unexpected,
        `uncaught browser errors (console.error / pageerror) during the test:\n${unexpected.join(
          '\n',
        )}\n\nIf a test DELIBERATELY triggers one, declare it via allowConsoleErrors(testInfo, /pattern/).`,
      ).toEqual([]);
    },
    { auto: true },
  ],
});

export { expect };
