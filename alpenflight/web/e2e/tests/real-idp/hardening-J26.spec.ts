import {
  test,
  expect,
  type Browser,
  type BrowserContext,
  type Page,
  type TestInfo,
} from '@playwright/test';

import { fillKcLogin } from './_helpers/kc-form';
import { proofVideo } from './_helpers/proof-video';

/**
 * J-26 HARDENING — real chain (live Keycloak auth + real Spring backend + real
 * Postgres). The journey's `parity_test` (T-01 STUB — every case `test.fixme`;
 * the structure, chrome-entry flow and selectors are pinned here, T-27 thickens
 * to full real assertions once T-04/T-05 land the fixes). NO `page.route`, no
 * `@mocked:` seams — every seam is the real stack.
 *
 * ── PLANNED CASES (J-26 "Spec must assert" §real-idp/hardening-J26) ──────────
 *   [happy]     a REAL principal edits a Person's membership (memberNumber +
 *               role toggle) → Save → re-open → values persisted SERVER-side —
 *               the data-loss fix (T-04: today the form hydrates + toasts
 *               success but silently DROPS the membership fields;
 *               `PUT /persons/{id}/clubs/current` exists, is never called).
 *   [key-error] duplicate FlightCode over the real chain: FE → real endpoint →
 *               real `ux_flight_type_club_code` constraint → 409 → INLINE on
 *               the code field (T-05; today a raw 500, reproducing the legacy
 *               bug).
 *
 * ── CHROME ENTRY (do-ship done-bar) ───────────────────────────────────────────
 * Both cases ENTER through the nav chrome (`af-nav-section-…` → list → form),
 * never a bare `page.goto` to the form. The real CLUB_ADMINISTRATOR principal
 * sees the tenant sections (unlike the dual-role mock — see the mock sibling's
 * header), so `/persons` is enterable today. PREREQUISITE the stub surfaces:
 * `/flight-types` has NO nav section (URL-only screen — the J-7 /flightreports
 * hollow-screen miss); `af-nav-section-/flight-types` must land before the
 * duplicate-code case un-fixmes.
 *
 * Mirrors the J-6b real-idp discipline (reservations-planning-hardening.spec.ts):
 * warm in-app nav only (no `clearCookies` — kills session restore
 * [[project_real_idp_goto_reboot_renew_stall]]); a fresh recorded context per
 * test → the pass-video is flushed on ctx.close() then attached via `proofVideo`
 * from the `finally`. Created-ids are read from the Location header / a re-GET,
 * never `response.json()` ([[project_spa_nav_evicts_post_response_body]]).
 *
 * GALLERY: each case writes its full-page parity PNG to `testInfo.outputDir`
 * (alpenflight-hardening-membership.png / alpenflight-hardening-duplicate-code.png)
 * — declared `pending` in e2e/proof-gallery/expected-shots.json until T-27 flips
 * them to `expected` (producedBy: proof) when the staging wires up.
 *
 * PRINCIPAL — the J-0 Keycloak dev seed (realm-export.json + Flyway dev-user seed):
 *   clubadmin1@example.com — CLUB_ADMINISTRATOR bound to seed-club-1 (V8 t_user
 *   row). Low-privilege real principal
 *   [[project_real_idp_real_roles_catches_authz_gaps]].
 */

const CLUB_ADMIN1 = {
  username: 'clubadmin1@example.com',
  password: 'clubadmin1-dev-2026!',
};

/** A new recorded context (one isolated session per test → its own pass-video). */
async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  return browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
}

/**
 * Log the seeded principal in through the real Keycloak redirect flow, landing
 * back on the SPA. Warm in-app nav from here (no cold `page.goto` mid-session).
 */
async function loginAsClubAdmin(page: Page): Promise<void> {
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, CLUB_ADMIN1.username, CLUB_ADMIN1.password);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
  await expect(page.getByTestId('landing-topbar-sign-in')).toHaveCount(0);
}

/** Chrome entry: app shell → nav section click (never a bare goto to the form). */
async function enterSection(page: Page, sectionPath: string): Promise<void> {
  await page.goto('/start?lang=de');
  const section = page.getByTestId(`af-nav-section-${sectionPath}`);
  await expect(section, `the ${sectionPath} nav section is chrome-reachable`).toBeVisible();
  await section.click();
}

/** The real-idp project's baseURL (resolved per test — no shared hook state). */
function projectBaseUrl(testInfo: TestInfo): string {
  return testInfo.project.use.baseURL ?? 'http://localhost:4201';
}

test.describe('J-26 hardening (real-idp heavy chain)', () => {
  test.describe.configure({ mode: 'serial' });

  test.fixme('[happy] a real principal edits a Person membership (memberNumber + role toggle) → Save → re-open → persisted server-side', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, projectBaseUrl(testInfo), testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page);

      // Chrome entry: Persons section → first row → edit form.
      await enterSection(page, '/persons');
      await expect(page).toHaveURL(/\/persons(\?|$)/);
      await page.getByTestId('persons-table').getByRole('row').nth(1).click();
      await expect(page.getByTestId('person-form')).toBeVisible();

      // Edit the membership seam the T-04 fix wires: a unique memberNumber +
      // a role toggle (the fields the form hydrates but the update silently
      // DROPS today — the data-loss bug).
      const memberNumber = `M-${Date.now() % 1_000_000}`;
      await page.getByTestId('member-number-input').locator('input').fill(memberNumber);
      const gliderRole = page.getByTestId('role-glider-pilot');
      const gliderWasChecked = await gliderRole.isChecked();
      await gliderRole.setChecked(!gliderWasChecked);

      // Save over the REAL backend (`PUT /persons/{id}` + the T-04-wired
      // `PUT /persons/{id}/clubs/current`), then RE-OPEN from the list — the
      // round-trip read is the proof the values persisted server-side.
      await page.getByTestId('person-save-button').click();
      await expect(page).toHaveURL(/\/persons(\?|$)/);
      await page.getByTestId('persons-table').getByRole('row').nth(1).click();
      await expect(page.getByTestId('person-form')).toBeVisible();

      await expect(page.getByTestId('member-number-input').locator('input')).toHaveValue(
        memberNumber,
      );
      await expect(gliderRole).toBeChecked({ checked: !gliderWasChecked });

      // Gallery shot (pending → expected at T-27, producedBy: proof).
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-hardening-membership.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-26',
        caption:
          'J-26 · persons · a real CLUB_ADMINISTRATOR edits memberNumber + a pilot-role toggle, ' +
          'saves, re-opens — the values come back from the real backend (the membership ' +
          'data-loss fix: the form used to toast success while silently dropping them)',
        acTag: 'happy',
      });
    }
  });

  test.fixme('[key-error] duplicate FlightCode over the real chain → inline 409 on the code field (not a raw 500)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, projectBaseUrl(testInfo), testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page);

      // Chrome entry — PREREQUISITE: the `/flight-types` nav section (URL-only
      // screen today; see header). T-05/T-27 land the entry before un-fixme.
      await enterSection(page, '/flight-types');
      await expect(page.getByTestId('flight-types-table')).toBeVisible();

      // Read an EXISTING row's code, then create a new flight type reusing it —
      // the real `ux_flight_type_club_code` partial-unique constraint rejects it.
      const existingCode = (
        await page.getByTestId('flight-types-table').getByRole('row').nth(1).innerText()
      ).trim();
      await page.getByTestId('flight-types-new-button').click();
      await expect(page.getByTestId('flight-types-edit-form')).toBeVisible();

      await page.locator('#FlightTypeName').fill(`Duplikat ${Date.now() % 1_000_000}`);
      await page.locator('#FlightCode').fill(existingCode);
      await page.getByTestId('flight-types-save-button').click();

      // The REAL 409 (FE → real endpoint → real constraint → problem-detail
      // field=flightCode) surfaces INLINE on the Code field — no raw 500, no
      // mislabeled flightTypeName error.
      await expect(
        page.locator('af-form-field', { has: page.locator('#FlightCode') }).getByRole('alert'),
      ).toBeVisible();

      // Gallery shot (pending → expected at T-27, producedBy: proof).
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-hardening-duplicate-code.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-26',
        caption:
          'J-26 · flight-types · saving a duplicate FlightCode over the real chain returns an ' +
          'inline 409 on the code field (real ux_flight_type_club_code constraint) — previously ' +
          'a raw 500 reproducing the legacy bug',
        acTag: 'key-error',
      });
    }
  });
});
