import { test as base, expect, type Page, type TestInfo } from '@playwright/test';


const BENIGN_PATTERNS: readonly (string | RegExp)[] = [
  /Failed to load resource.*favicon\.ico/i,
];

function isBenign(message: string): boolean {
  return BENIGN_PATTERNS.some((p) =>
    typeof p === 'string' ? message.includes(p) : p.test(message),
  );
}

interface ConsoleGuard {
  readonly errors: string[];
  readonly allowed: (string | RegExp)[];
}

const guards = new WeakMap<TestInfo, ConsoleGuard>();

function guardFor(testInfo: TestInfo): ConsoleGuard {
  let g = guards.get(testInfo);
  if (!g) {
    g = { errors: [], allowed: [] };
    guards.set(testInfo, g);
  }
  return g;
}

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

export function allowConsoleErrors(testInfo: TestInfo, ...patterns: (string | RegExp)[]): void {
  guardFor(testInfo).allowed.push(...patterns);
}

function isAllowed(message: string, allowed: readonly (string | RegExp)[]): boolean {
  return allowed.some((p) => (typeof p === 'string' ? message.includes(p) : p.test(message)));
}

const BOOTSTRAP_REFERENCE_PATHS: readonly string[] = [
  '**/api/v1/countries**',
  '**/api/v1/club-states**',
  '**/api/v1/location-types**',
  '**/api/v1/aircraft-types**',
  '**/api/v1/aircraft-states**',
  '**/api/v1/counter-unit-types**',
];

async function installBootstrapReferenceStubs(page: Page): Promise<void> {
  for (const path of BOOTSTRAP_REFERENCE_PATHS) {
    await page.route(path, (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
    );
  }
}

interface ResourceStub {
  readonly url: string | RegExp;
  readonly status: number;
  readonly body: string;
}

const NO_ROW_BODY_200_NOT_404 = 'null';

const PER_SCREEN_RESOURCE_STUBS: readonly ResourceStub[] = [
  { url: '**/api/v1/me/system-dashboard', status: 200, body: '{}' },
  { url: '**/api/v1/me/person/licences', status: 200, body: '{}' },
  { url: '**/api/v1/me/person', status: 200, body: '{}' },
  { url: '**/api/v1/me/club-membership/notification-prefs', status: 200, body: '{}' },
  { url: '**/api/v1/persons', status: 200, body: '[]' },
  { url: '**/api/v1/club/member-states', status: 200, body: '[]' },
  { url: '**/api/v1/locations', status: 200, body: '[]' },
  { url: '**/api/v1/flight-types', status: 200, body: '[]' },
  { url: '**/api/v1/flights/new-template**', status: 200, body: minimalValidFlightTemplateBody() },
  { url: /\/api\/v1\/flights(\?|$)/, status: 200, body: '{"items":[]}' },
  { url: /\/api\/v1\/flights\/last-context(\?|$)/, status: 200, body: NO_ROW_BODY_200_NOT_404 },
  {
    url: /\/api\/v1\/flights\/(?!new-template|last-context)[^/?]+(\?|$)/,
    status: 200,
    body: NO_ROW_BODY_200_NOT_404,
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

function minimalValidFlightTemplateBody(): string {
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

async function installLowestPriorityApiFloor(page: Page): Promise<void> {
  await page.route('**/api/v1/**', (route) => {
    const method = route.request().method();
    if (method === 'GET') {
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
      return;
    }
    route.fulfill({ status: 204, body: '' });
  });
}

const NO_BACKEND_MOCK_AUTH_PROJECT = 'chromium';

export async function installMockApiStubs(page: Page): Promise<void> {
  await installLowestPriorityApiFloor(page);
  await installBootstrapReferenceStubs(page);
  await installPerScreenResourceStubs(page);
}

export const test = base.extend<{ consoleGuard: void }>({
  consoleGuard: [
    async ({ page }, use, testInfo) => {
      watchConsoleErrors(page, testInfo);
      if (testInfo.project.name === NO_BACKEND_MOCK_AUTH_PROJECT) {
        await installMockApiStubs(page);
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
