import {
  type Browser,
  type BrowserContext,
  type Locator,
  type Page,
  type TestInfo,
} from '@playwright/test';
import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';

import { enterViaNav } from '../_helpers/nav';
import { fillKcLogin } from './_helpers/kc-form';
import { proofVideo } from './_helpers/proof-video';

/**
 * J-26 HARDENING — real chain (live Keycloak auth + real Spring backend + real
 * Postgres). The journey's `parity_test`. T-01 stubbed the structure / chrome
 * entry / selectors as `test.fixme`; T-27 thickens both cases to FULL REAL
 * assertions now that T-04 (membership data-loss fix) and T-05 (duplicate
 * FlightCode 409) have landed. NO `page.route`, no `@mocked:` seams — every seam
 * is the real stack (real principal → real endpoint → real constraint).
 *
 * Once this spec carries ≥1 ACTIVE (non-fixme) real-idp `test(...)`, the ci.yml
 * `proof_spec` derive (T-31) flips `is_baseline=false` → the per-push proof job
 * runs THIS spec and the deployed-journey gallery guard activates, so the
 * assertions must be real + correct (PROVEN at the gate's dispatch, where the
 * full live stack boots — not bootable on the authoring box).
 *
 * ── CASES (J-26 "Spec must assert" §real-idp/hardening-J26) ──────────────────
 *   [happy]     a REAL CLUB_ADMINISTRATOR edits a Person's membership
 *               (memberNumber + a role toggle + memberState) → Save → re-open →
 *               the values PERSISTED server-side — the data-loss fix (T-04:
 *               before the fix the form hydrated + toasted success but silently
 *               DROPPED the membership fields; `PUT /persons/{id}/clubs/current`
 *               exists, was never called).
 *   [key-error] duplicate FlightCode over the real chain: FE → real endpoint →
 *               real `ux_flight_type_club_code` partial-unique constraint /
 *               service pre-check → 409 `field=flightCode` → INLINE on the Code
 *               field (T-05; before the fix a raw 500 reproducing the legacy
 *               bug, or a mislabel onto flightTypeName).
 *
 * ── CHROME ENTRY (do-ship done-bar) ──────────────────────────────────────────
 * Both cases ENTER through the nav chrome (`af-nav-section-…` → list → form),
 * never a bare `page.goto` to the form. The real CLUB_ADMINISTRATOR principal
 * sees the tenant sections (T-28 union nav), so /persons + /flight-types are
 * both chrome-reachable (T-28 added the `/flight-types` nav entry — it was a
 * URL-only screen, the J-7 hollow-screen class).
 *
 * Mirrors the J-6b real-idp discipline (reservations-planning-hardening.spec.ts):
 * warm in-app nav only (no `clearCookies` — kills session restore
 * [[project_real_idp_goto_reboot_renew_stall]]); a fresh recorded context per
 * test → the pass-video is flushed on ctx.close() then attached via `proofVideo`
 * from the `finally`. Mutations run against the never-truncated seed-club-1, so
 * each case derives a UNIQUE value (runtime stamp) → re-run-safe.
 *
 * GALLERY: each case writes its full-page parity PNG to `testInfo.outputDir`
 * (alpenflight-hardening-membership.png / alpenflight-hardening-duplicate-code.png),
 * CAPTURED BEFORE its deep assertions so a partial red still produces the shot.
 * Declared `expected` (producedBy: proof) in expected-shots.json at T-27.
 *
 * PRINCIPAL — the J-0 Keycloak dev seed (realm-export.json + Flyway dev-user seed):
 *   clubadmin1@example.com — CLUB_ADMINISTRATOR bound to seed-club-1 (V8 t_user
 *   row; `session.isClubAdmin` → `canMutate` on both screens). A real
 *   low-privilege principal [[project_real_idp_real_roles_catches_authz_gaps]].
 */

const CLUB_ADMIN1 = {
  username: 'clubadmin1@example.com',
  password: 'clubadmin1-dev-2026!',
};

/** The two member states the V36 dev seed gives seed-club-1 (Persons masterdata). */
const MEMBER_STATE_A = 'Aktivmitglied';
const MEMBER_STATE_B = 'Passivmitglied';

/** A new recorded context (one isolated session per test → its own pass-video). */
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
  // Persons / flight-types now nest under the Masterdata nav group (J-8 T-22a);
  // enterViaNav opens that dropdown first for nested paths.
  await enterViaNav(page, sectionPath);
}

/** The real-idp project's baseURL (resolved per test — no shared hook state). */
function projectBaseUrl(testInfo: TestInfo): string {
  return testInfo.project.use.baseURL ?? 'http://localhost:4201';
}

/**
 * The inline error region under one form field — the `<af-field-errors>` alert
 * scoped to the `<af-form-field>` wrapping the target control (the same pattern
 * the mock sibling `forms/validation-hardening.spec.ts` asserts on).
 */
function fieldErrors(page: Page, controlLocator: Locator): Locator {
  return page.locator('af-form-field', { has: controlLocator }).getByRole('alert');
}

test.describe('J-26 hardening (real-idp heavy chain)', () => {
  test.describe.configure({ mode: 'serial' });

  test('[happy] a real principal edits a Person membership (memberNumber + role toggle + memberState) → Save → re-open → persisted server-side', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, projectBaseUrl(testInfo), testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page);

      // Chrome entry: Persons section → first seeded row → edit form. The list is
      // an af-data-table whose rows are `person-row-<id>` routerLinks (NOT a bare
      // <table>); the V36 dev seed gives seed-club-1 ≥3 person-clubs, so a row is
      // always present.
      await enterSection(page, '/persons');
      await expect(page).toHaveURL(/\/persons(\?|$)/);
      await expect(page.getByTestId('persons-table')).toBeVisible();
      const firstRow = page.locator('[data-testid^="person-row-"]').first();
      await expect(firstRow, 'the V36 seed renders ≥1 person row').toBeVisible();
      await firstRow.click();
      await expect(page).toHaveURL(/\/persons\/[^/]+\/edit$/);
      await expect(page.getByTestId('person-form')).toBeVisible();

      // Read the CURRENT membership state — the test flips it, so it derives the
      // expected post-Save values from what hydrated (re-run-safe: a prior run
      // left whatever it last wrote, never a fixed seed value).
      const memberNumberInput = page.getByTestId('member-number-input').locator('input');
      await expect(memberNumberInput).toBeVisible();
      const motorRole = page.getByTestId('role-motor-pilot');
      const motorWasChecked = await motorRole.isChecked();
      const stateSelect = page.getByTestId('member-state-select');
      const currentStateText = ((await stateSelect.textContent()) ?? '').trim();
      // Pick the OTHER seeded member state so Save genuinely changes it.
      const targetState = currentStateText.includes(MEMBER_STATE_B)
        ? MEMBER_STATE_A
        : MEMBER_STATE_B;

      // Edit the three membership fields the T-04 fix wires through
      // `PUT /persons/{id}/clubs/current` (memberNumber + a role flag +
      // memberState) — the fields the form hydrated but the update silently
      // DROPPED before the fix (≤20 chars: the form's maxLength).
      const memberNumber = `M-${Date.now() % 100_000_000}`;
      await memberNumberInput.fill(memberNumber);
      await motorRole.setChecked(!motorWasChecked);
      await stateSelect.locator('nz-select').click();
      await page.locator('nz-option-item').filter({ hasText: targetState }).click();
      await expect(stateSelect).toContainText(targetState);

      // Save over the REAL backend (`PUT /persons/{id}` + the T-04-wired
      // `PUT /persons/{id}/clubs/current`). Watch the membership PUT succeed over
      // the wire — the half that did not exist before the fix.
      const membershipPut = page.waitForResponse(
        (r) =>
          r.request().method() === 'PUT' &&
          /\/api\/v1\/persons\/[^/]+\/clubs\/current$/.test(new URL(r.url()).pathname) &&
          r.ok(),
        { timeout: 15_000 },
      );
      await page.getByTestId('person-save-button').click();
      await membershipPut;
      await expect(page).toHaveURL(/\/persons(\?|$)/);

      // RE-OPEN the same row — the round-trip READ is the proof the values
      // persisted server-side (a fresh GET hydrates the form from the real
      // PersonClub; before T-04 the form re-hydrated the OLD values).
      await page.getByTestId('persons-table').waitFor({ state: 'visible' });
      await firstRow.click();
      await expect(page).toHaveURL(/\/persons\/[^/]+\/edit$/);
      await expect(page.getByTestId('person-form')).toBeVisible();

      // CAPTURE-BEFORE-DEEP-ASSERT: the re-opened form showing the persisted
      // membership (the AF side of the membership gallery pair).
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-hardening-membership.png`,
        fullPage: true,
      });

      await expect(
        page.getByTestId('member-number-input').locator('input'),
        'the edited memberNumber came back from the real backend',
      ).toHaveValue(memberNumber);
      await expect(
        page.getByTestId('role-motor-pilot'),
        'the toggled role flag persisted server-side',
      ).toBeChecked({ checked: !motorWasChecked });
      await expect(
        page.getByTestId('member-state-select'),
        'the changed member state persisted server-side',
      ).toContainText(targetState);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-26',
        caption:
          'J-26 · persons · a real CLUB_ADMINISTRATOR edits memberNumber + a pilot-role toggle + ' +
          'the member state, saves, re-opens — every value comes back from the real backend ' +
          '(the membership data-loss fix T-04: the form used to toast success while silently ' +
          'dropping them; PUT /persons/{id}/clubs/current now fires)',
        acTag: 'happy',
      });
    }
  });

  test('[key-error] duplicate FlightCode over the real chain → inline 409 on the code field (not a raw 500, not mislabeled on the name)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, projectBaseUrl(testInfo), testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page);

      // Chrome entry — the `/flight-types` nav section (T-28 added it; URL-only
      // screen before that, the J-7 hollow-screen class).
      await enterSection(page, '/flight-types');
      await expect(page).toHaveURL(/\/flight-types(\?|$)/);
      await expect(page.getByTestId('flight-types-table')).toBeVisible();

      // A unique code so the FIRST create always succeeds against the never-
      // truncated seed (the partial-unique `ux_flight_type_club_code` is per
      // (club, code)). `dupCode` is reused for the second create to trip it.
      const stamp = Date.now() % 100_000;
      const dupCode = `D${stamp}`;

      // FIRST create — a fresh flight type owning `dupCode`. POST → 201 → the
      // `flightType.created` bus event navigates back to the list.
      await page.getByTestId('flight-types-new-button').click();
      await expect(page.getByTestId('flight-types-edit-form')).toBeVisible();
      await page.locator('#FlightTypeName').fill(`J26 Original ${stamp}`);
      await page.locator('#FlightCode').fill(dupCode);
      const firstCreate = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new URL(r.url()).pathname === '/api/v1/flight-types' &&
          r.status() === 201,
        { timeout: 15_000 },
      );
      await page.getByTestId('flight-types-save-button').click();
      await firstCreate;
      await expect(page).toHaveURL(/\/flight-types(\?|$)/);

      // SECOND create — a DIFFERENT name but the SAME code. The real chain (the
      // service `findActiveByCode` pre-check + the `ux_flight_type_club_code` DIVE
      // race net) rejects it with a 409 carrying problem-detail `field=flightCode`.
      await expect(page.getByTestId('flight-types-table')).toBeVisible();
      await page.getByTestId('flight-types-new-button').click();
      await expect(page.getByTestId('flight-types-edit-form')).toBeVisible();
      await page.locator('#FlightTypeName').fill(`J26 Duplikat ${stamp}`);
      await page.locator('#FlightCode').fill(dupCode);

      // WIRE PROOF: the REAL endpoint returns 409 (not a raw 500 reproducing the
      // legacy bug). Capturing the status at response time proves FE → real
      // endpoint → real constraint, end-to-end.
      const dupResponse = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/v1/flight-types',
        { timeout: 15_000 },
      );
      await page.getByTestId('flight-types-save-button').click();
      const resp = await dupResponse;
      expect(
        resp.status(),
        `the duplicate FlightCode must 409 over the real chain (not a 500) — got ${resp.status()}`,
      ).toBe(409);

      // The 409 routes INLINE onto the Code field (the store routes by the
      // problem-detail `field`), NOT onto flightTypeName — the name vs code
      // mislabel the legacy bug had.
      const codeError = fieldErrors(page, page.locator('#FlightCode'));
      await expect(
        codeError,
        'the duplicate-code 409 surfaces inline on the FlightCode field',
      ).toBeVisible();

      // CAPTURE-BEFORE-DEEP-ASSERT: the form with the inline 409 on the code field
      // (the AF side of the duplicate-code gallery pair).
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-hardening-duplicate-code.png`,
        fullPage: true,
      });

      await expect(
        fieldErrors(page, page.locator('#FlightTypeName')),
        'the 409 is NOT mislabeled onto the flightTypeName field',
      ).toHaveCount(0);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-26',
        caption:
          'J-26 · flight-types · creating a second flight type with a duplicate FlightCode over the ' +
          'real chain returns a 409 (real ux_flight_type_club_code constraint + service pre-check) ' +
          'that surfaces INLINE on the Code field — previously a raw 500 reproducing the legacy bug, ' +
          'or a mislabel onto the name field',
        acTag: 'key-error',
      });
    }
  });
});
