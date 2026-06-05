import {
  test,
  expect,
  type Browser,
  type BrowserContext,
  type Page,
  type TestInfo,
} from '@playwright/test';

import { fillKcLogin } from '../real-idp/_helpers/kc-form';

/**
 * J-4 — Profile self-edit (`/profile`) — SPEC STUB (T-01).
 *
 * This file COMMITS THE SCREEN SHAPE and nothing more. It is the work-list for
 * J-4's frontend tasks: it asserts the entry flow (nav avatar dropdown →
 * `/profile`) and that each of the four tabs (Account / Personal / Pilot /
 * Notifications) renders, with THIN assertions only (`toBeVisible` on the tab
 * controls, panels, and each tab's key fields). The full real round-trip
 * assertions — edit → `PATCH /api/v1/me/*` → persist + reflect on reload, the
 * sysadmin-fixture audit-row read, the no-Person banner, and cross-principal
 * isolation — land in T-13 (`## Spec must assert` in
 * `docs/modernization/stories/J-4-profile-self-edit.md`).
 *
 * IT IS EXPECTED TO FAIL until the screen exists (T-03 ships the shell + tab
 * routing; T-05/T-07/T-09/T-11 ship the four tabs). Red is the point — the
 * `data-testid`s below are the contract those tasks implement.
 *
 * ── data-testid convention (downstream tabs MUST match these) ────────────────
 * Shell / routing (T-03):
 *   profile-page                         the /profile shell root
 *   profile-tab-account                  tab CONTROL (clickable)  — Account
 *   profile-tab-personal                 tab CONTROL              — Personal
 *   profile-tab-pilot                    tab CONTROL              — Pilot
 *   profile-tab-notifications            tab CONTROL              — Notifications
 *   profile-panel-account                tab PANEL (body shown when active)
 *   profile-panel-personal               tab PANEL
 *   profile-panel-pilot                  tab PANEL
 *   profile-panel-notifications          tab PANEL
 *   profile-no-person-banner             the "ask your club admin" banner (edge; asserted in T-13)
 * Account tab fields (T-05):
 *   profile-account-friendlyName
 *   profile-account-notificationEmail
 *   profile-account-phone
 *   profile-account-language
 * Personal tab fields (T-07):
 *   profile-personal-address
 *   profile-personal-city
 *   profile-personal-phonePrivate
 *   profile-personal-phoneBusiness
 *   profile-personal-birthday
 * Pilot tab fields (T-09):
 *   profile-pilot-licence-glider           a licence flag (checkbox/toggle)
 *   profile-pilot-medical-expiry           a medical expiry date field
 * Notifications tab toggles (T-11) — the 3 prefs:
 *   profile-notifications-pref-flightReports
 *   profile-notifications-pref-reservations
 *   profile-notifications-pref-clubNews
 *
 * Field testids wrap the form CONTROL (input / select / checkbox). The later tab
 * components own whether the inner element is an `<input>` (`.locator('input')`)
 * or an ng-zorro/`af-*` control; this stub only asserts the wrapper is visible,
 * so it stays agnostic to that choice until T-13 thickens to value assertions.
 *
 * ── Real PILOT principal (J-3 lesson, project_real_idp_real_roles_catches_authz_gaps) ──
 * Driven as `pilot1@example.com` — a real, low-privilege showcase principal,
 * NOT an admin. `/profile` is a self-edit surface whose actual audience is a
 * pilot; driving the real PILOT is what proves the `isAuthenticated()` +
 * caller-resolved-from-JWT gating (no `:id` path param) and catches authz gaps
 * an admin-everything mock principal would hide. The sysadmin fixture is only
 * for reading the Pilot-tab audit row (AC4) — added in T-13, not here.
 *
 * Login mirrors the J-3 `start-dashboard.spec.ts#loginAsRole` redirect dance
 * (landing sign-in → real Keycloak form → redirect back → in-app nav). This spec
 * therefore needs the real-idp stack (live Keycloak + backend); ship/T-13 wires
 * its project routing — the file path is fixed by the journey's `parity_test`.
 */

interface SeededPrincipal {
  username: string;
  password: string;
}

// Showcase PILOT principal (alpenflight realm export + Flyway dev-user seed).
// T-02 extends the showcase seed so this pilot has full contact + licence/medical
// + PersonClub notification prefs, so the tabs render POPULATED (asserted in T-13).
const PILOT: SeededPrincipal = {
  username: 'pilot1@example.com',
  password: 'pilot1-dev-2026!',
};

/** The four tab segments, in render order — drives the per-tab visibility loop. */
const TABS = ['account', 'personal', 'pilot', 'notifications'] as const;
type ProfileTab = (typeof TABS)[number];

/**
 * Log the seeded PILOT in through the real Keycloak redirect flow and land on the
 * authed SPA. Mirrors `start-dashboard.spec.ts#loginAsRole`: click the landing
 * sign-in, fill the KC form, wait for the redirect back out of `/realms/`.
 * Returns the authenticated context + page; the caller navigates to `/profile`
 * (warm in-app nav is preferred over a cold goto mid-session —
 * project_real_idp_goto_reboot_renew_stall).
 */
async function loginAsPilot(
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
  return { context, page };
}

/**
 * Open a tab by clicking its control and assert the matching panel shows. Thin:
 * panel + control visibility only — the per-field assertions are the caller's.
 */
async function openTab(page: Page, tab: ProfileTab): Promise<void> {
  await page.getByTestId(`profile-tab-${tab}`).click();
  await expect(page.getByTestId(`profile-panel-${tab}`)).toBeVisible();
}

/**
 * RESILIENT per-tab capture (J-2 T-42 lesson → a do-ship principle, modelled on
 * `start-dashboard.spec.ts#captureVariantShot`): open a tab and screenshot its
 * panel the MOMENT it is visible — BEFORE any deep round-trip assertion that
 * could abort the test. The showcase-seed `alpenflight-dashboard-proof` CI job
 * stages these into the gallery's `--screenshots` dir under stable names
 * (`alpenflight-profile-<tab>.png`) + writes the J-4 `screenshots.json` sidecar,
 * so the operator gets a live 4-tab `/profile` gallery even while the deep
 * round-trip assertions (T-13) and the stub tab bodies (T-06..T-11) are still
 * red-by-design. fullPage per CLAUDE.md §8 + the gallery's AlpenFlight-only
 * (greenfield) policy.
 */
async function captureTabShot(page: Page, testInfo: TestInfo, tab: ProfileTab): Promise<void> {
  await page.getByTestId(`profile-tab-${tab}`).click();
  await expect(page.getByTestId(`profile-panel-${tab}`)).toBeVisible();
  await page.screenshot({
    path: `${testInfo.outputDir}/alpenflight-profile-${tab}.png`,
    fullPage: true,
  });
}

test.describe('J-4 profile self-edit (/profile) — screen shape [real PILOT, stub]', () => {
  // ── Gallery capture: 4 stable-named per-tab screenshots (T-15) ───────────────
  // Drives all four tabs as PILOT pilot1 and writes one STABLE-NAMED fullPage PNG
  // per tab — `alpenflight-profile-{account,personal,pilot,notifications}.png` —
  // into `testInfo.outputDir`, which the `alpenflight-dashboard-proof` CI job
  // stages into the J-4 gallery. Each shot is taken the MOMENT its panel is
  // visible (inside `captureTabShot`), BEFORE any further assertion, so all four
  // always land even while the deep round-trip assertions (T-13) stay red. pilot1
  // now has a linked Person (T-14), so Personal/Pilot/Notifications are enabled
  // (stub bodies for now — the honest current state; captured anyway).
  test('captures all four /profile tabs for the gallery (PILOT pilot1)', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const { context, page } = await loginAsPilot(browser, baseURL!, PILOT);
    try {
      await page.goto('/profile');
      await expect(page.getByTestId('profile-page')).toBeVisible();

      // Capture each tab's panel before any later assertion can abort the run, so
      // every one of the four stable-named shots reaches the gallery regardless.
      for (const tab of TABS) {
        await captureTabShot(page, testInfo, tab);
      }
    } finally {
      await context.close();
    }
  });

  // ── Entry: nav avatar dropdown → Profile → /profile ─────────────────────────
  test('the nav avatar dropdown routes a PILOT to /profile', async ({ browser, baseURL }) => {
    const { context, page } = await loginAsPilot(browser, baseURL!, PILOT);
    try {
      // The avatar/initials trigger already exists (af-nav-bar.component.ts:
      // data-testid="af-nav-user"). Click it → the dropdown opens → "Profile".
      await page.getByTestId('af-nav-user').click();
      // The "Profile" item is a routerLink="/profile" menuitem (no testid of its
      // own yet); target it by role + accessible name within the open menu.
      await page.getByRole('menuitem', { name: 'Profile' }).click();

      await expect(page).toHaveURL(/\/profile$/);
      await expect(page.getByTestId('profile-page')).toBeVisible();
    } finally {
      await context.close();
    }
  });

  // ── Tabs present: all four controls + each panel reachable ──────────────────
  test('the four profile tabs are present and each panel is reachable', async ({
    browser,
    baseURL,
  }) => {
    const { context, page } = await loginAsPilot(browser, baseURL!, PILOT);
    try {
      await page.goto('/profile');
      await expect(page.getByTestId('profile-page')).toBeVisible();

      // All four tab controls render.
      for (const tab of TABS) {
        await expect(page.getByTestId(`profile-tab-${tab}`)).toBeVisible();
      }

      // Account is the default-active tab — its panel shows without a click.
      await expect(page.getByTestId('profile-panel-account')).toBeVisible();

      // Clicking each tab shows its panel.
      for (const tab of TABS) {
        await openTab(page, tab);
      }
    } finally {
      await context.close();
    }
  });

  // ── Account tab key fields ──────────────────────────────────────────────────
  test('the Account tab shows its key fields', async ({ browser, baseURL }) => {
    const { context, page } = await loginAsPilot(browser, baseURL!, PILOT);
    try {
      await page.goto('/profile');
      await openTab(page, 'account');

      await expect(page.getByTestId('profile-account-friendlyName')).toBeVisible();
      await expect(page.getByTestId('profile-account-notificationEmail')).toBeVisible();
      await expect(page.getByTestId('profile-account-phone')).toBeVisible();
      await expect(page.getByTestId('profile-account-language')).toBeVisible();
    } finally {
      await context.close();
    }
  });

  // ── Personal tab key fields ─────────────────────────────────────────────────
  test('the Personal tab shows its key fields', async ({ browser, baseURL }) => {
    const { context, page } = await loginAsPilot(browser, baseURL!, PILOT);
    try {
      await page.goto('/profile');
      await openTab(page, 'personal');

      await expect(page.getByTestId('profile-personal-address')).toBeVisible();
      await expect(page.getByTestId('profile-personal-city')).toBeVisible();
      await expect(page.getByTestId('profile-personal-phonePrivate')).toBeVisible();
      await expect(page.getByTestId('profile-personal-phoneBusiness')).toBeVisible();
      await expect(page.getByTestId('profile-personal-birthday')).toBeVisible();
    } finally {
      await context.close();
    }
  });

  // ── Pilot tab key fields (a licence flag + a medical expiry date) ────────────
  test('the Pilot tab shows a licence flag and a medical expiry field', async ({
    browser,
    baseURL,
  }) => {
    const { context, page } = await loginAsPilot(browser, baseURL!, PILOT);
    try {
      await page.goto('/profile');
      await openTab(page, 'pilot');

      await expect(page.getByTestId('profile-pilot-licence-glider')).toBeVisible();
      await expect(page.getByTestId('profile-pilot-medical-expiry')).toBeVisible();
    } finally {
      await context.close();
    }
  });

  // ── Notifications tab — the 3 pref toggles ──────────────────────────────────
  test('the Notifications tab shows its three preference toggles', async ({ browser, baseURL }) => {
    const { context, page } = await loginAsPilot(browser, baseURL!, PILOT);
    try {
      await page.goto('/profile');
      await openTab(page, 'notifications');

      await expect(page.getByTestId('profile-notifications-pref-flightReports')).toBeVisible();
      await expect(page.getByTestId('profile-notifications-pref-reservations')).toBeVisible();
      await expect(page.getByTestId('profile-notifications-pref-clubNews')).toBeVisible();
    } finally {
      await context.close();
    }
  });
});
