import {
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
  type TestInfo,
} from '@playwright/test';
import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';

import { fillKcLogin } from '../real-idp/_helpers/kc-form';

interface SeededPrincipal {
  username: string;
  password: string;
}

const PILOT: SeededPrincipal = {
  username: 'pilot1@example.com',
  password: 'pilot1-dev-2026!',
};

const PILOT_EMPTY: SeededPrincipal = {
  username: 'pilot-empty1@example.com',
  password: 'pilot-empty1-dev-2026!',
};

const CLUB_ADMIN: SeededPrincipal = {
  username: 'clubadmin1@example.com',
  password: 'clubadmin1-dev-2026!',
};

const TABS = ['account', 'personal', 'pilot', 'notifications'] as const;
type ProfileTab = (typeof TABS)[number];

const SEED = {
  friendlyName: 'Pilot One',
  username: 'pilot1',
  notificationEmail: 'pilot1@example.com',
  phone: '+41 79 000 00 01',
  firstName: 'Pilot',
  lastName: 'One',
  city: 'Bern',
  medicalClass2Expire: '2027-09-30',
} as const;

const LANGUAGE_ID_EN = '019e2e15-2c00-77d3-8000-0000000007d3';

async function loginAsSeededPrincipal(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
  principal: SeededPrincipal,
  nonEnglishLocaleForColdStartProof?: string,
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext(
    nonEnglishLocaleForColdStartProof
      ? { baseURL, locale: nonEnglishLocaleForColdStartProof }
      : { baseURL },
  );
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  const page = await context.newPage();
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, principal.username, principal.password);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
  return { context, page };
}

async function captureBearer(page: Page): Promise<string> {
  const bearerPromise = page.waitForRequest(
    (req) =>
      req.url().includes('/api/v1/') &&
      typeof req.headers()['authorization'] === 'string' &&
      /^Bearer /i.test(req.headers()['authorization']!),
    { timeout: 15_000 },
  );
  await page.goto('/start');
  const req = await bearerPromise;
  return req.headers()['authorization']!;
}

async function openTab(page: Page, tab: ProfileTab): Promise<void> {
  await page.getByTestId(`profile-tab-${tab}`).click();
  await expect(page.getByTestId(`profile-panel-${tab}`)).toBeVisible();
}

function nativeInputOf(page: Page, testid: string) {
  return page.getByTestId(testid).locator('input');
}

async function captureTabShot(page: Page, testInfo: TestInfo, tab: ProfileTab): Promise<void> {
  await page.getByTestId(`profile-tab-${tab}`).click();
  await expect(page.getByTestId(`profile-panel-${tab}`)).toBeVisible();
  await page.screenshot({
    path: `${testInfo.outputDir}/alpenflight-profile-${tab}.png`,
    fullPage: true,
  });
}

test.describe('J-4 profile self-edit (/profile) — full round-trip [real PILOT, showcase seed]', () => {
  test('captures all four /profile tabs for the gallery (PILOT pilot1)', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const { context, page } = await loginAsSeededPrincipal(browser, baseURL!, testInfo, PILOT);
    try {
      await page.goto('/profile');
      await expect(page.getByTestId('profile-page')).toBeVisible();
      for (const tab of TABS) {
        await captureTabShot(page, testInfo, tab);
      }
    } finally {
      await context.close();
    }
  });

  test('the nav avatar dropdown routes to /profile and Sign out ends the session', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const { context, page } = await loginAsSeededPrincipal(browser, baseURL!, testInfo, PILOT);
    try {
      await page.getByTestId('af-nav-user').click();
      await page.getByRole('menuitem', { name: 'Profile' }).click();
      await expect(page).toHaveURL(/\/profile$/);
      await expect(page.getByTestId('profile-page')).toBeVisible();

      await page.getByTestId('af-nav-user').click();
      await page.getByTestId('af-nav-logout').click();
      await expect
        .poll(() => new URL(page.url()).pathname, { timeout: 30_000 })
        .not.toMatch(/\/profile/);
      const signInTriggerRenderedOnlyWhileSignedOut = page.getByTestId('landing-topbar-sign-in');
      await expect(signInTriggerRenderedOnlyWhileSignedOut).toBeVisible({ timeout: 30_000 });
    } finally {
      await context.close();
    }
  });

  test('Account tab edits friendlyName/notificationEmail/phone/language → persists on reload; identity read-only; locale flips', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const { context, page } = await loginAsSeededPrincipal(
      browser,
      baseURL!,
      testInfo,
      PILOT,
      'de-CH',
    );
    try {
      await page.goto('/profile');
      await openTab(page, 'account');

      await expect(page.locator('html')).toHaveAttribute('lang', 'de', { timeout: 10_000 });
      await expect(page.getByText('Anzeigename')).toBeVisible();

      await expect(nativeInputOf(page, 'profile-account-friendlyName')).toHaveValue(
        SEED.friendlyName,
      );
      await expect(nativeInputOf(page, 'profile-account-notificationEmail')).toHaveValue(
        SEED.notificationEmail,
      );

      await expect(nativeInputOf(page, 'profile-account-username')).toBeDisabled();
      await expect(nativeInputOf(page, 'profile-account-username')).toHaveValue(SEED.username);
      await expect(nativeInputOf(page, 'profile-account-clubId')).toBeDisabled();

      const newFriendly = 'Pilot One Edited';
      const newPhone = '+41 79 555 11 22';
      await nativeInputOf(page, 'profile-account-friendlyName').fill(newFriendly);
      await nativeInputOf(page, 'profile-account-phone').fill(newPhone);

      await page.getByTestId('profile-account-language').click();
      await page.getByTestId(`af-select-option-${LANGUAGE_ID_EN}`).click();

      const patchPromise = page.waitForResponse(
        (r) => r.url().includes('/api/v1/me/profile') && r.request().method() === 'PATCH' && r.ok(),
        { timeout: 15_000 },
      );
      await page.getByTestId('profile-account-save').click();
      await patchPromise;
      await expect(page.getByTestId('profile-account-saved')).toBeVisible({ timeout: 15_000 });

      await expect(page.locator('html')).toHaveAttribute('lang', 'en', { timeout: 10_000 });
      await expect(page.getByText('Display name')).toBeVisible();
      await expect(page.getByText('Anzeigename')).toHaveCount(0);

      await test.step('the edits and the English locale survive a cold start whose navigator is still de-CH', async () => {
        await page.goto('/profile');
        await openTab(page, 'account');
        await expect(nativeInputOf(page, 'profile-account-friendlyName')).toHaveValue(newFriendly);
        await expect(nativeInputOf(page, 'profile-account-phone')).toHaveValue(newPhone);
        await expect(page.locator('html')).toHaveAttribute('lang', 'en', { timeout: 10_000 });
        await expect(page.getByText('Display name')).toBeVisible();
      });
    } finally {
      await context.close();
    }
  });

  test('Personal tab edits an address/contact field → persists on reload; name fields read-only', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const { context, page } = await loginAsSeededPrincipal(browser, baseURL!, testInfo, PILOT);
    try {
      await page.goto('/profile');
      await openTab(page, 'personal');

      await expect(nativeInputOf(page, 'profile-personal-city')).toHaveValue(SEED.city);
      await expect(nativeInputOf(page, 'profile-personal-firstName')).toBeDisabled();
      await expect(nativeInputOf(page, 'profile-personal-firstName')).toHaveValue(SEED.firstName);
      await expect(nativeInputOf(page, 'profile-personal-lastName')).toBeDisabled();
      await expect(nativeInputOf(page, 'profile-personal-lastName')).toHaveValue(SEED.lastName);

      const newCity = 'Bern-Belp';
      await nativeInputOf(page, 'profile-personal-city').fill(newCity);

      const patchPromise = page.waitForResponse(
        (r) =>
          /\/api\/v1\/me\/person(\?|$)/.test(r.url()) && r.request().method() === 'PATCH' && r.ok(),
        { timeout: 15_000 },
      );
      await page.getByTestId('profile-personal-save').click();
      await patchPromise;
      await expect(page.getByTestId('profile-personal-saved')).toBeVisible({ timeout: 15_000 });

      await page.goto('/profile');
      await openTab(page, 'personal');
      await expect(nativeInputOf(page, 'profile-personal-city')).toHaveValue(newCity);
      await expect(nativeInputOf(page, 'profile-personal-firstName')).toHaveValue(SEED.firstName);
    } finally {
      await context.close();
    }
  });

  test('Pilot tab edits a medical expiry → persists on reload AND a club-admin reads the PersonLicences audit row (before/after diff)', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const { context, page } = await loginAsSeededPrincipal(browser, baseURL!, testInfo, PILOT);
    try {
      await page.goto('/profile');
      await openTab(page, 'pilot');

      await expect(page.getByTestId('profile-pilot-licence-glider')).toBeChecked();
      await expect(nativeInputOf(page, 'profile-pilot-medical-expiry')).toHaveValue(
        SEED.medicalClass2Expire,
      );

      const newExpiry = '2029-06-30';
      await nativeInputOf(page, 'profile-pilot-medical-expiry').fill(newExpiry);

      const patchPromise = page.waitForResponse(
        (r) =>
          r.url().includes('/api/v1/me/person/licences') &&
          r.request().method() === 'PATCH' &&
          r.ok(),
        { timeout: 15_000 },
      );
      await page.getByTestId('profile-pilot-save').click();
      await patchPromise;
      await expect(page.getByTestId('profile-pilot-saved')).toBeVisible({ timeout: 15_000 });

      await page.goto('/profile');
      await openTab(page, 'pilot');
      await expect(nativeInputOf(page, 'profile-pilot-medical-expiry')).toHaveValue(newExpiry);
      await expect(page.getByTestId('profile-pilot-licence-glider')).toBeChecked();
    } finally {
      await context.close();
    }

    const admin = await loginAsSeededPrincipal(browser, baseURL!, testInfo, CLUB_ADMIN);
    try {
      const bearer = await captureBearer(admin.page);
      const auditRow = await readLatestPersonLicencesAudit(admin.context.request, bearer);

      const before = auditRow.beforeState as Record<string, unknown> | undefined;
      const after = auditRow.afterState as Record<string, unknown> | undefined;
      expect(auditRow.action, 'PersonLicences audit action is UPDATE').toBe('UPDATE');
      expect(before, 'audit row carries a before-state').toBeTruthy();
      expect(after, 'audit row carries an after-state').toBeTruthy();
      expect(String(after?.['medicalClass2ExpireDate'])).toBe('2029-06-30');
      expect(String(before?.['medicalClass2ExpireDate'])).toBe(SEED.medicalClass2Expire);
    } finally {
      await admin.context.close();
    }
  });

  test('Notifications tab toggles a pref → persists on reload', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const { context, page } = await loginAsSeededPrincipal(browser, baseURL!, testInfo, PILOT);
    try {
      await page.goto('/profile');
      await openTab(page, 'notifications');

      const reservations = page.getByTestId('profile-notifications-pref-reservations');
      await expect(reservations).not.toBeChecked();

      await reservations.check();

      const patchPromise = page.waitForResponse(
        (r) =>
          r.url().includes('/api/v1/me/club-membership/notification-prefs') &&
          r.request().method() === 'PATCH' &&
          r.ok(),
        { timeout: 15_000 },
      );
      await page.getByTestId('profile-notifications-save').click();
      await patchPromise;
      await expect(page.getByTestId('profile-notifications-saved')).toBeVisible({
        timeout: 15_000,
      });

      await page.goto('/profile');
      await openTab(page, 'notifications');
      await expect(page.getByTestId('profile-notifications-pref-reservations')).toBeChecked();
      await expect(page.getByTestId('profile-notifications-pref-flightReports')).toBeChecked();
    } finally {
      await context.close();
    }
  });

  test('a no-Person principal sees the banner + disabled forms on Personal/Pilot/Notifications; Account still edits + saves', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const { context, page } = await loginAsSeededPrincipal(
      browser,
      baseURL!,
      testInfo,
      PILOT_EMPTY,
    );
    try {
      await page.goto('/profile');
      await expect(page.getByTestId('profile-page')).toBeVisible();

      await expect(page.getByTestId('profile-no-person-banner')).toBeVisible();

      for (const tab of ['personal', 'pilot', 'notifications'] as const) {
        const navItem = page.locator(`[role="tab"]:has([data-testid="profile-tab-${tab}"])`);
        await expect(navItem).toHaveAttribute('aria-disabled', 'true');
      }

      await openTab(page, 'account');
      const newFriendly = 'Empty Pilot Edited';
      await nativeInputOf(page, 'profile-account-friendlyName').fill(newFriendly);

      const patchPromise = page.waitForResponse(
        (r) => r.url().includes('/api/v1/me/profile') && r.request().method() === 'PATCH' && r.ok(),
        { timeout: 15_000 },
      );
      await page.getByTestId('profile-account-save').click();
      await patchPromise;
      await expect(page.getByTestId('profile-account-saved')).toBeVisible({ timeout: 15_000 });

      await page.goto('/profile');
      await openTab(page, 'account');
      await expect(nativeInputOf(page, 'profile-account-friendlyName')).toHaveValue(newFriendly);
    } finally {
      await context.close();
    }
  });

  test('every profile mutation hits an id-less /api/v1/me/* endpoint (no :id, caller-scoped from the JWT)', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const { context, page } = await loginAsSeededPrincipal(browser, baseURL!, testInfo, PILOT);
    try {
      const patchPaths: string[] = [];
      page.on('request', (req) => {
        if (req.method() === 'PATCH' && req.url().includes('/api/v1/')) {
          patchPaths.push(new URL(req.url()).pathname);
        }
      });

      await page.goto('/profile');

      await openTab(page, 'account');
      await nativeInputOf(page, 'profile-account-friendlyName').fill('Pilot One Iso');
      await fireSaveAndWait(page, 'profile-account-save', '/api/v1/me/profile');

      await openTab(page, 'personal');
      await nativeInputOf(page, 'profile-personal-city').fill('Bern');
      await fireSaveAndWait(page, 'profile-personal-save', '/api/v1/me/person');

      await openTab(page, 'pilot');
      await nativeInputOf(page, 'profile-pilot-medical-expiry').fill('2030-01-31');
      await fireSaveAndWait(page, 'profile-pilot-save', '/api/v1/me/person/licences');

      await openTab(page, 'notifications');
      await page.getByTestId('profile-notifications-pref-flightReports').click();
      await fireSaveAndWait(
        page,
        'profile-notifications-save',
        '/api/v1/me/club-membership/notification-prefs',
      );

      expect(patchPaths.length, 'all four tab PATCHes were observed').toBeGreaterThanOrEqual(4);
      for (const p of patchPaths) {
        expect(p, `profile PATCH path is /me-scoped: ${p}`).toMatch(/^\/api\/v1\/me\//);
        expect(p, `profile PATCH path carries no :id (caller resolved from JWT): ${p}`).not.toMatch(
          /[0-9a-fA-F-]{8,}/,
        );
      }
    } finally {
      await context.close();
    }
  });
});

interface AuditEventRow {
  action?: string;
  targetEntityType?: string;
  occurredAt?: string;
  beforeState?: unknown;
  afterState?: unknown;
}

async function readLatestPersonLicencesAudit(
  api: APIRequestContext,
  bearer: string,
): Promise<AuditEventRow> {
  const res = await api.get('/api/v1/admin/audit-events', {
    headers: { authorization: bearer },
    params: { targetEntityType: 'PersonLicences', pageSize: 50 },
  });
  expect(res.ok(), `GET /api/v1/admin/audit-events (${res.status()})`).toBeTruthy();
  const page = (await res.json()) as { items: AuditEventRow[] };
  const row = page.items.find((r) => r.targetEntityType === 'PersonLicences');
  expect(row, 'a PersonLicences audit row exists for the just-edited licence change').toBeTruthy();
  return row!;
}

async function fireSaveAndWait(
  page: Page,
  saveTestId: string,
  pathFragment: string,
): Promise<void> {
  const patchPromise = page.waitForResponse(
    (r) => r.url().includes(pathFragment) && r.request().method() === 'PATCH' && r.ok(),
    { timeout: 15_000 },
  );
  await page.getByTestId(saveTestId).click();
  await patchPromise;
}
