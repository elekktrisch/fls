import {
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
  type TestInfo,
} from '@playwright/test';
import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';

import { fillKcLogin } from '../real-idp/_helpers/kc-form';

/**
 * J-4 — Profile self-edit (`/profile`) — FULL REAL ASSERTIONS (T-13).
 *
 * T-01 committed the screen shape (the `data-testid` anchors); T-15 added the
 * resilient per-tab gallery capture. This file THICKENS every case to the
 * journey's `## Spec must assert` list against the SHOWCASE-SEEDED server
 * (`@Profile("showcase")` → `ShowcaseSeeder`), run by the
 * `alpenflight-dashboard-proof` CI job (real chain: bootJar + Keycloak +
 * Postgres + showcase seed). The clean-seed `alpenflight-proof` job does NOT run
 * this spec (showcase data would pollute J-0/J-1/J-2 exact-count assertions).
 *
 * ── Driven as the real PILOT showcase principal `pilot1` ─────────────────────
 * `/profile` is a self-edit surface whose audience is a pilot, so the spec drives
 * the real low-privilege PILOT (J-3 lesson, project_real_idp_real_roles_catches_
 * authz_gaps) — that is what proves the `isAuthenticated()` + caller-resolved-
 * from-JWT gating (no `:id` path param). The only admin principal is `clubadmin1`
 * (CLUB_ADMINISTRATOR of club-1), used ONLY to read the Pilot-tab audit row
 * (AC4) through the tenant-scoped admin audit endpoint.
 *
 * ── pilot1's seeded values (ShowcaseSeeder, J-4 T-14 / T-02 contract) ─────────
 *   User:   friendlyName "Pilot One", username "pilot1", clubId club-1,
 *           notificationEmail "pilot1@example.com", phone "+41 79 000 00 01",
 *           language German (V8 seed: language_id = de).
 *   Person: firstName "Pilot", lastName "One", address "Flugplatzstrasse 1",
 *           city "Bern", region "BE", zip "3000", birthday 1985-06-15,
 *           has_glider_pilot_licence=true, licence_number "CH-GLD-0001",
 *           medical_class2_expire_date 2027-09-30.
 *   PersonClub prefs: receive_flight_reports=true,
 *           receive_aircraft_reservation_notifications=false,
 *           receive_planning_day_role_reminder=true.
 * No-Person principal: realm user `pilot-empty1` (t_user with person_id NULL).
 *
 * ── Resilient gallery capture (J-2 T-42 → do-ship principle) ─────────────────
 * The first test still drives all four tabs and writes one STABLE-NAMED fullPage
 * PNG per tab (`alpenflight-profile-<tab>.png`) the MOMENT the panel is visible —
 * BEFORE any deep round-trip assertion. The `alpenflight-dashboard-proof` job
 * stages those into the J-4 gallery, so the operator gets a live 4-tab gallery
 * even while a deep assertion below is red. The thick round-trips live in
 * SEPARATE tests so a red assertion never costs the gallery shots.
 */

interface SeededPrincipal {
  username: string;
  password: string;
}

// Showcase PILOT principal — the self-edit audience; full Person + prefs (T-14).
const PILOT: SeededPrincipal = {
  username: 'pilot1@example.com',
  password: 'pilot1-dev-2026!',
};

// No-Person principal — `t_user.person_id` is NULL (V30). The Personal/Pilot/
// Notifications tabs show the banner + disabled forms; Account still edits.
const PILOT_EMPTY: SeededPrincipal = {
  username: 'pilot-empty1@example.com',
  password: 'pilot-empty1-dev-2026!',
};

// CLUB_ADMINISTRATOR of club-1 — same tenant as pilot1, so it can read club-1's
// mutation-audit trail. Used ONLY for the AC4 Pilot-tab audit-row read.
const CLUB_ADMIN: SeededPrincipal = {
  username: 'clubadmin1@example.com',
  password: 'clubadmin1-dev-2026!',
};

/** The four tab segments, in render order — drives the per-tab gallery loop. */
const TABS = ['account', 'personal', 'pilot', 'notifications'] as const;
type ProfileTab = (typeof TABS)[number];

// pilot1's seeded values (ShowcaseSeeder). The round-trips edit OFF these.
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

// `language.id` (V2 seed UUID) for English — the locale we flip TO from the
// seeded German, mirrored from `shared/ui/locale/language-options.ts`.
const LANGUAGE_ID_EN = '019e2e15-2c00-77d3-8000-0000000007d3';

/**
 * Log the seeded principal in through the real Keycloak redirect flow and land on
 * the authed SPA. Mirrors `start-dashboard.spec.ts#loginAsRole`: click the
 * landing sign-in, fill the KC form, wait for the redirect back out of
 * `/realms/`. The caller then navigates to `/profile` (warm in-app nav preferred
 * over a cold goto mid-session — project_real_idp_goto_reboot_renew_stall).
 */
async function loginAsPilot(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
  principal: SeededPrincipal,
  contextLocale?: string,
): Promise<{ context: BrowserContext; page: Page }> {
  // `contextLocale` boots the browser context at a NON-`en` navigator locale so
  // the SPA's cold-start resolver genuinely starts non-English (AC2 locale
  // proof). Chromium defaults to `navigator.language=en-US`→`en`, which would
  // make a `<html lang>='en'` assertion trivially green regardless of DB state.
  const context = await browser.newContext(
    contextLocale ? { baseURL, locale: contextLocale } : { baseURL },
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

/**
 * Capture the Bearer the OIDC interceptor attaches to a `/api/v1/*` call for the
 * authenticated principal. Navigating to `/start` guarantees an authed read fires
 * (the dashboard store calls `/api/v1/me`), so the Bearer is observable without
 * touching the spec's flow. Used by the AC4 admin audit-row read.
 */
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

/** Open a tab by clicking its control and assert the matching panel shows. */
async function openTab(page: Page, tab: ProfileTab): Promise<void> {
  await page.getByTestId(`profile-tab-${tab}`).click();
  await expect(page.getByTestId(`profile-panel-${tab}`)).toBeVisible();
}

/**
 * The editable field testids wrap the `<af-input>` HOST; the real `<input>` is a
 * child. Resolve the inner native control for fill/read. (Pilot checkboxes carry
 * the testid directly on `<input type="checkbox">` — those use the testid as-is.)
 */
function field(page: Page, testid: string) {
  return page.getByTestId(testid).locator('input');
}

/**
 * RESILIENT per-tab capture (J-2 T-42 lesson, modelled on
 * `start-dashboard.spec.ts#captureVariantShot`): open a tab and screenshot its
 * panel the MOMENT it is visible — BEFORE any deep assertion. Stable name
 * `alpenflight-profile-<tab>.png` → the `alpenflight-dashboard-proof` job stages
 * it into the J-4 gallery. fullPage per CLAUDE.md §8 + greenfield (AlpenFlight-
 * only) gallery policy.
 */
async function captureTabShot(page: Page, testInfo: TestInfo, tab: ProfileTab): Promise<void> {
  await page.getByTestId(`profile-tab-${tab}`).click();
  await expect(page.getByTestId(`profile-panel-${tab}`)).toBeVisible();
  await page.screenshot({
    path: `${testInfo.outputDir}/alpenflight-profile-${tab}.png`,
    fullPage: true,
  });
}

test.describe('J-4 profile self-edit (/profile) — full round-trip [real PILOT, showcase seed]', () => {
  // ── Gallery capture: 4 stable-named per-tab screenshots (T-15) ───────────────
  // Drives all four tabs as PILOT pilot1 and writes one STABLE-NAMED fullPage PNG
  // per tab BEFORE any deep assertion, so all four always reach the gallery even
  // while a round-trip below is red. Keep this test FIRST + assertion-light.
  test('captures all four /profile tabs for the gallery (PILOT pilot1)', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const { context, page } = await loginAsPilot(browser, baseURL!, testInfo, PILOT);
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

  // ── AC1 — Entry: avatar dropdown → Profile → /profile; Sign out ends session ─
  test('the nav avatar dropdown routes to /profile and Sign out ends the session', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const { context, page } = await loginAsPilot(browser, baseURL!, testInfo, PILOT);
    try {
      // Avatar/initials trigger → dropdown → "Profile" routerLink.
      await page.getByTestId('af-nav-user').click();
      await page.getByRole('menuitem', { name: 'Profile' }).click();
      await expect(page).toHaveURL(/\/profile$/);
      await expect(page.getByTestId('profile-page')).toBeVisible();

      // "Sign out" (af-nav-logout → /auth/logout → RP-initiated KC logout) ends
      // the session. The OIDC end_session round-trip leaves the SPA on the public
      // landing route, NOT an authed page — assert we are off /profile and on the
      // landing sign-in surface (the session is gone).
      await page.getByTestId('af-nav-user').click();
      await page.getByTestId('af-nav-logout').click();
      await expect
        .poll(() => new URL(page.url()).pathname, { timeout: 30_000 })
        .not.toMatch(/\/profile/);
      // The landing sign-in trigger is only rendered for an UNauthenticated
      // session — its presence proves the session ended.
      await expect(page.getByTestId('landing-topbar-sign-in')).toBeVisible({ timeout: 30_000 });
    } finally {
      await context.close();
    }
  });

  // ── AC2 — Account round-trip + read-only identity + locale flip ──────────────
  test('Account tab edits friendlyName/notificationEmail/phone/language → persists on reload; identity read-only; locale flips', async ({
    browser,
    baseURL,
  }, testInfo) => {
    // Boot the context at a NON-`en` navigator locale (`de-CH` → resolves to
    // `de`) so the SPA genuinely cold-starts in German — the de→en flip is then
    // actually observable, not trivially-green off Chromium's `en-US` default.
    const { context, page } = await loginAsPilot(browser, baseURL!, testInfo, PILOT, 'de-CH');
    try {
      await page.goto('/profile');
      await openTab(page, 'account');

      // INITIAL locale is German. `<html lang>='de'` is the document-lang oracle;
      // the German label "Anzeigename" (friendlyName) is a known-string oracle
      // for the actual rendered translations. With navigator=de-CH the persisted
      // German `languageCode` and the navigator both point at `de` here, so the
      // start is unambiguously German (the reload assertion below is what proves
      // the persisted-languageId cold-start path on its own).
      await expect(page.locator('html')).toHaveAttribute('lang', 'de', { timeout: 10_000 });
      // Substring (NOT exact): af-form-field renders the label as
      // `<label>{{label}}<span>*</span></label>` for required fields, so the
      // label's normalized text is "Anzeigename *" — an exact match would miss it.
      await expect(page.getByText('Anzeigename')).toBeVisible();

      // Populated render: the seeded values hydrate the form.
      await expect(field(page, 'profile-account-friendlyName')).toHaveValue(SEED.friendlyName);
      await expect(field(page, 'profile-account-notificationEmail')).toHaveValue(
        SEED.notificationEmail,
      );

      // username + clubId render read-only (disabled, non-editable).
      await expect(field(page, 'profile-account-username')).toBeDisabled();
      await expect(field(page, 'profile-account-username')).toHaveValue(SEED.username);
      await expect(field(page, 'profile-account-clubId')).toBeDisabled();

      // Edit the editable self-fields with values distinct from the seed.
      const newFriendly = 'Pilot One Edited';
      const newPhone = '+41 79 555 11 22';
      await field(page, 'profile-account-friendlyName').fill(newFriendly);
      await field(page, 'profile-account-phone').fill(newPhone);

      // Language flip: switch the seeded German → English via the af-select
      // (ng-zorro overlay) and assert the SPA locale FLIPS off German.
      // LocaleService.set mirrors the change onto <html lang> + the rendered
      // translations (the single switch the bootstrap uses).
      await page.getByTestId('profile-account-language').click();
      await page.getByTestId(`af-select-option-${LANGUAGE_ID_EN}`).click();

      // Save → PATCH /api/v1/me/profile.
      const patchPromise = page.waitForResponse(
        (r) => r.url().includes('/api/v1/me/profile') && r.request().method() === 'PATCH' && r.ok(),
        { timeout: 15_000 },
      );
      await page.getByTestId('profile-account-save').click();
      await patchPromise;
      await expect(page.getByTestId('profile-account-saved')).toBeVisible({ timeout: 15_000 });

      // Locale flipped de→en — assert BOTH oracles: <html lang> AND the English
      // label "Display name" (German "Anzeigename" must be gone).
      await expect(page.locator('html')).toHaveAttribute('lang', 'en', { timeout: 10_000 });
      await expect(page.getByText('Display name')).toBeVisible();
      await expect(page.getByText('Anzeigename')).toHaveCount(0);

      // SPA-nav-evicts-POST-body lesson: prove persistence via a RELOAD + re-GET,
      // never the PATCH response body. Reload /profile and re-read the fields.
      await page.goto('/profile');
      await openTab(page, 'account');
      await expect(field(page, 'profile-account-friendlyName')).toHaveValue(newFriendly);
      await expect(field(page, 'profile-account-phone')).toHaveValue(newPhone);
      // LOCALE-SURVIVAL — this is the real proof of the persisted-languageId
      // cold-start fix (T-20a). The context's navigator is STILL `de-CH`, so
      // without that fix the reload would revert to German. It STAYS English
      // ONLY because cold-start now reads the saved `/me` `languageCode='en'`.
      await expect(page.locator('html')).toHaveAttribute('lang', 'en', { timeout: 10_000 });
      await expect(page.getByText('Display name')).toBeVisible();
    } finally {
      await context.close();
    }
  });

  // ── AC3 — Personal round-trip; name fields read-only ─────────────────────────
  test('Personal tab edits an address/contact field → persists on reload; name fields read-only', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const { context, page } = await loginAsPilot(browser, baseURL!, testInfo, PILOT);
    try {
      await page.goto('/profile');
      await openTab(page, 'personal');

      // Populated render + name fields read-only (rename stays admin-only).
      await expect(field(page, 'profile-personal-city')).toHaveValue(SEED.city);
      await expect(field(page, 'profile-personal-firstName')).toBeDisabled();
      await expect(field(page, 'profile-personal-firstName')).toHaveValue(SEED.firstName);
      await expect(field(page, 'profile-personal-lastName')).toBeDisabled();
      await expect(field(page, 'profile-personal-lastName')).toHaveValue(SEED.lastName);

      const newCity = 'Bern-Belp';
      await field(page, 'profile-personal-city').fill(newCity);

      const patchPromise = page.waitForResponse(
        (r) =>
          /\/api\/v1\/me\/person(\?|$)/.test(r.url()) && r.request().method() === 'PATCH' && r.ok(),
        { timeout: 15_000 },
      );
      await page.getByTestId('profile-personal-save').click();
      await patchPromise;
      await expect(page.getByTestId('profile-personal-saved')).toBeVisible({ timeout: 15_000 });

      // Persistence via reload + re-GET (GET /me/person hydrates the tab).
      await page.goto('/profile');
      await openTab(page, 'personal');
      await expect(field(page, 'profile-personal-city')).toHaveValue(newCity);
      // Name fields untouched (still read-only with the seeded values).
      await expect(field(page, 'profile-personal-firstName')).toHaveValue(SEED.firstName);
    } finally {
      await context.close();
    }
  });

  // ── AC4 — Pilot round-trip + sysadmin-fixture audit-row read ─────────────────
  test('Pilot tab edits a medical expiry → persists on reload AND a club-admin reads the PersonLicences audit row (before/after diff)', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const { context, page } = await loginAsPilot(browser, baseURL!, testInfo, PILOT);
    try {
      await page.goto('/profile');
      await openTab(page, 'pilot');

      // Populated render: seeded glider flag + class-2 medical expiry.
      await expect(page.getByTestId('profile-pilot-licence-glider')).toBeChecked();
      await expect(field(page, 'profile-pilot-medical-expiry')).toHaveValue(
        SEED.medicalClass2Expire,
      );

      // Edit the class-2 medical expiry to a NEW value (the audit diff oracle).
      const newExpiry = '2029-06-30';
      await field(page, 'profile-pilot-medical-expiry').fill(newExpiry);

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

      // Persistence via reload + re-GET (GET /me/person/licences hydrates the tab).
      await page.goto('/profile');
      await openTab(page, 'pilot');
      await expect(field(page, 'profile-pilot-medical-expiry')).toHaveValue(newExpiry);
      await expect(page.getByTestId('profile-pilot-licence-glider')).toBeChecked();
    } finally {
      await context.close();
    }

    // ── AC4 audit-row read — the SYSADMIN FIXTURE (clubadmin1) ──────────────────
    // The PATCH emits a `person.licences_updated`-equivalent audit row under the
    // DISTINCT entity type `PersonLicences` (T-08), allow-listed so the
    // before/after diff is readable (un-redacted). The real read surface is the
    // tenant-scoped admin endpoint GET /api/v1/admin/audit-events
    // (@PreAuthorize CLUB_ADMINISTRATOR/SYSTEM_ADMINISTRATOR — AuditAdminController).
    // pilot1's audit row lives under tenant club-1, so club-1's CLUB_ADMINISTRATOR
    // (clubadmin1) reads it — the genuine HTTP admin path, no DB peek, no seam.
    const admin = await loginAsPilot(browser, baseURL!, testInfo, CLUB_ADMIN);
    try {
      const bearer = await captureBearer(admin.page);
      const auditRow = await readLatestPersonLicencesAudit(admin.context.request, bearer);

      // before/after diff reflects the medical-expiry change (un-redacted).
      const before = auditRow.beforeState as Record<string, unknown> | undefined;
      const after = auditRow.afterState as Record<string, unknown> | undefined;
      expect(auditRow.action, 'PersonLicences audit action is UPDATE').toBe('UPDATE');
      expect(before, 'audit row carries a before-state').toBeTruthy();
      expect(after, 'audit row carries an after-state').toBeTruthy();
      // The after-state shows the new class-2 expiry verbatim (allow-listed, not
      // [redacted]); the before-state shows the seeded value.
      expect(String(after?.['medicalClass2ExpireDate'])).toBe('2029-06-30');
      expect(String(before?.['medicalClass2ExpireDate'])).toBe(SEED.medicalClass2Expire);
    } finally {
      await admin.context.close();
    }
  });

  // ── AC5 — Notifications round-trip ───────────────────────────────────────────
  test('Notifications tab toggles a pref → persists on reload', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const { context, page } = await loginAsPilot(browser, baseURL!, testInfo, PILOT);
    try {
      await page.goto('/profile');
      await openTab(page, 'notifications');

      // Seeded prefs: flightReports=true, reservations=false, clubNews(planning)=true.
      const reservations = page.getByTestId('profile-notifications-pref-reservations');
      await expect(reservations).not.toBeChecked();

      // Toggle reservations false → true.
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

      // Persistence via reload + re-GET (GET notification-prefs hydrates the tab).
      await page.goto('/profile');
      await openTab(page, 'notifications');
      await expect(page.getByTestId('profile-notifications-pref-reservations')).toBeChecked();
      // The untouched prefs keep their seeded values.
      await expect(page.getByTestId('profile-notifications-pref-flightReports')).toBeChecked();
    } finally {
      await context.close();
    }
  });

  // ── AC6 — No-Person banner: Personal/Pilot/Notifications gated; Account works ─
  test('a no-Person principal sees the banner + disabled forms on Personal/Pilot/Notifications; Account still edits + saves', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const { context, page } = await loginAsPilot(browser, baseURL!, testInfo, PILOT_EMPTY);
    try {
      await page.goto('/profile');
      await expect(page.getByTestId('profile-page')).toBeVisible();

      // No-Person → the "ask your club admin" banner renders at the top of the
      // shell (it does not require activating a tab).
      await expect(page.getByTestId('profile-no-person-banner')).toBeVisible();

      // The three person-backed tabs are DISABLED (forms not editable). A disabled
      // ng-zorro nz-tab cannot be activated, so the proof is the disabled state on
      // each tab's nav item (`aria-disabled` on the role="tab" ancestor), not a
      // click-through. (The banner above is the operator-facing "ask your admin"
      // message the AC names.)
      for (const tab of ['personal', 'pilot', 'notifications'] as const) {
        const navItem = page.locator(`[role="tab"]:has([data-testid="profile-tab-${tab}"])`);
        await expect(navItem).toHaveAttribute('aria-disabled', 'true');
      }

      // The Account tab still works for a person-less principal — edit + save.
      await openTab(page, 'account');
      const newFriendly = 'Empty Pilot Edited';
      await field(page, 'profile-account-friendlyName').fill(newFriendly);

      const patchPromise = page.waitForResponse(
        (r) => r.url().includes('/api/v1/me/profile') && r.request().method() === 'PATCH' && r.ok(),
        { timeout: 15_000 },
      );
      await page.getByTestId('profile-account-save').click();
      await patchPromise;
      await expect(page.getByTestId('profile-account-saved')).toBeVisible({ timeout: 15_000 });

      await page.goto('/profile');
      await openTab(page, 'account');
      await expect(field(page, 'profile-account-friendlyName')).toHaveValue(newFriendly);
    } finally {
      await context.close();
    }
  });

  // ── AC7 — Isolation: every /me/* call is id-less + caller-scoped ─────────────
  test('every profile mutation hits an id-less /api/v1/me/* endpoint (no :id, caller-scoped from the JWT)', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const { context, page } = await loginAsPilot(browser, baseURL!, testInfo, PILOT);
    try {
      // Record every PATCH the profile screen fires across all four tabs; assert
      // each targets `/api/v1/me/...` with NO id segment — the endpoint resolves
      // the caller's own User/Person/PersonClub from the JWT, so a cross-principal
      // edit is structurally impossible (there is no :id to point elsewhere).
      const patchPaths: string[] = [];
      page.on('request', (req) => {
        if (req.method() === 'PATCH' && req.url().includes('/api/v1/')) {
          patchPaths.push(new URL(req.url()).pathname);
        }
      });

      await page.goto('/profile');

      // Account PATCH.
      await openTab(page, 'account');
      await field(page, 'profile-account-friendlyName').fill('Pilot One Iso');
      await fireSaveAndWait(page, 'profile-account-save', '/api/v1/me/profile');

      // Personal PATCH.
      await openTab(page, 'personal');
      await field(page, 'profile-personal-city').fill('Bern');
      await fireSaveAndWait(page, 'profile-personal-save', '/api/v1/me/person');

      // Pilot PATCH.
      await openTab(page, 'pilot');
      await field(page, 'profile-pilot-medical-expiry').fill('2030-01-31');
      await fireSaveAndWait(page, 'profile-pilot-save', '/api/v1/me/person/licences');

      // Notifications PATCH.
      await openTab(page, 'notifications');
      await page.getByTestId('profile-notifications-pref-flightReports').click();
      await fireSaveAndWait(
        page,
        'profile-notifications-save',
        '/api/v1/me/club-membership/notification-prefs',
      );

      // Every recorded PATCH is a `/me/*` path with NO numeric/uuid id segment.
      expect(patchPaths.length, 'all four tab PATCHes were observed').toBeGreaterThanOrEqual(4);
      for (const p of patchPaths) {
        expect(p, `profile PATCH path is /me-scoped: ${p}`).toMatch(/^\/api\/v1\/me\//);
        // No UUID / numeric id anywhere in the path — purely the me-scoped shape.
        expect(p, `profile PATCH path carries no :id (caller resolved from JWT): ${p}`).not.toMatch(
          /[0-9a-fA-F-]{8,}/,
        );
      }
    } finally {
      await context.close();
    }
  });
});

/** `AuditEventRow`-shaped subset the AC4 read asserts on (generated server DTO). */
interface AuditEventRow {
  action?: string;
  targetEntityType?: string;
  occurredAt?: string;
  beforeState?: unknown;
  afterState?: unknown;
}

/**
 * Read the most-recent `PersonLicences` audit row through the tenant-scoped admin
 * endpoint (`AuditAdminController`). The caller's Bearer must be a
 * CLUB_ADMINISTRATOR / SYSTEM_ADMINISTRATOR of the same tenant. Rows come back
 * newest-first; the spec edits exactly one licence row, so item[0] is the edit.
 */
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

/** Click a tab's save button and wait for its PATCH to land 2xx. */
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
