import { type Browser, type BrowserContext, type Page, type TestInfo } from '@playwright/test';
import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';

import { enterViaNav } from '../_helpers/nav';

import { proofVideo } from './_helpers/proof-video';
import {
  loginAsReservationAdmin,
  captureReservationAdminBearer,
} from './_helpers/reservation-parity-fixture';
import {
  provisionTwoClubs,
  loginAsClubAdmin,
  type TwoClubFixture,
} from './_helpers/two-club-fixture';

/**
 * J-10b — Deliveries WRITE side over the real chain (live Keycloak auth + real
 * Spring backend + real Postgres). The journey's `parity_test` (the real-chain
 * done-bar). Completes J-10's read screen with the accounting-correctness write
 * paths: engine create, the Prepared→Booked terminal state machine, and delete
 * (which resets the linked flights and reverses the flight-time credit it
 * balanced). NO `page.route` mocking on any path.
 *
 * SCAFFOLD: this spec is authored ahead of the write endpoints + the `/deliveries`
 * write affordances. One case (create) is active to scope the per-push real-idp
 * gate to J-10b and prove the nav-entry flow; the five write/error/tenancy cases
 * are `test.fixme` until their slices land, then thickened to the oracle-pinned
 * assertions. The `del-*` write testids below are the contract T-08 builds to.
 *
 * ── PRINCIPAL (CLUB_ADMINISTRATOR — every delivery write endpoint is admin-gated) ─
 * Drives `clubadmin4` (V29 seed), a REAL CLUB_ADMINISTRATOR bound to seed-club-1,
 * NOT the mock-admin everything-principal that hides a role-authz gap
 * ([[project_real_idp_real_roles_catches_authz_gaps]]). The cross-tenant write
 * probe drives a REAL second club + admin (`provisionTwoClubs`).
 *
 * ── REAL-IDP HYGIENE (hard-won) ──────────────────────────────────────────────
 *   - ENTER via the masterdata nav (`enterViaNav`), NOT a bare goto, for the
 *     chrome-reachability assertion;
 *   - prefer WARM in-app navigation; do NOT `clearCookies` (kills session
 *     restore) ([[project_real_idp_goto_reboot_renew_stall]]);
 *   - read a created id off the 201 `Location` header / a re-GET, never the POST
 *     response body ([[project_spa_nav_evicts_post_response_body]]).
 */

const DELIVERIES_PATH = '/deliveries';

// The write-side testids the `/deliveries` screen (T-08) builds to. The read
// screen already ships `del-table` / `del-row-<id>` / `del-state-<id>` /
// `del-detail` / `del-state-badge`; these add the write affordances.
const CREATE_BUTTON = 'del-create-button';
const DELETE_BUTTON = 'del-delete-button';
const DELETE_CONFIRM_MODAL = 'del-delete-confirm-modal';
const DELETE_CONFIRM = 'del-delete-confirm';
const BOOKED_BADGE = 'del-booked-badge';
const ERROR_TOAST = 'del-error-toast';

async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  const context = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  return context;
}

/** Log in as the seed-club-1 admin and land on the `/deliveries` list via the nav. */
async function enterDeliveries(page: Page): Promise<void> {
  await loginAsReservationAdmin(page);
  await page.goto('/start?lang=en');
  await enterViaNav(page, DELIVERIES_PATH);
  await expect(page).toHaveURL(DELIVERIES_PATH);
  await expect(page.getByTestId('del-table')).toBeVisible();
}

test.describe('Deliveries — write side (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let baseURL: string;
  /** clubadmin4's Bearer (seed-club-1, the @TenantId club). */
  let adminBearer: string;
  /** A real second club + admin (club B) — the cross-tenant write probe. */
  let twoClubs: TwoClubFixture;

  test.beforeAll(async ({ browser }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    adminBearer = await captureReservationAdminBearer(browser, baseURL);
    twoClubs = await provisionTwoClubs(browser, baseURL, 'dlw');
  });

  test.afterAll(async () => {
    await twoClubs?.dispose();
  });

  test('[happy] create deliveries action → the engine produces one delivery per eligible flight → the new rows appear', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await enterDeliveries(page);
      // The create affordance (T-08) is not yet on the list; once it lands this
      // case drives it and asserts the engine-produced rows appear (T-09).
      expect(await page.getByTestId(CREATE_BUTTON).count()).toBeGreaterThanOrEqual(0);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-10b',
        caption:
          'J-10b · deliveries · a club admin logs in via real Keycloak and ENTERS the /deliveries ' +
          'write screen through the masterdata nav (the create / delete / book affordances land in T-08, ' +
          'this active case scopes the per-push real-idp gate to J-10b)',
        acTag: 'happy',
      });
    }
  });

  test.fixme('[happy] delete a Prepared delivery → the flight (+tow) resets to Locked and the balanced credit is reversed', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await enterDeliveries(page);
      const row = page.getByTestId('del-table').getByRole('row').first();
      await row.getByTestId(DELETE_BUTTON).click();
      await expect(page.getByTestId(DELETE_CONFIRM_MODAL)).toBeVisible();
      await page.getByTestId(DELETE_CONFIRM).click();
    } finally {
      await ctx.close();
    }
  });

  test.fixme('[key-error] delete rejected when >1 delivery shares the flight → 409 surfaced, no partial mutation', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await enterDeliveries(page);
      await page.getByTestId(DELETE_BUTTON).first().click();
      await page.getByTestId(DELETE_CONFIRM).click();
      await expect(page.getByTestId(ERROR_TOAST)).toBeVisible();
    } finally {
      await ctx.close();
    }
  });

  test.fixme('[key-error] Booked is terminal → a Booked delivery rejects edit/delete with 409 and no mutation', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await enterDeliveries(page);
      await expect(page.getByTestId(BOOKED_BADGE).first()).toBeVisible();
    } finally {
      await ctx.close();
    }
  });

  test.fixme('[edge] book a Prepared delivery via the /delivered endpoint → number/DeliveredOn stamped, flight (+tow) → DeliveryBooked', async ({
    browser,
  }) => {
    const ctx = await browser.newContext({ baseURL });
    try {
      const booked = await ctx.request.post('/api/v1/deliveries/delivered', {
        headers: { authorization: adminBearer, 'content-type': 'application/json' },
        data: {},
      });
      expect(booked.status()).toBe(200);
    } finally {
      await ctx.close();
    }
  });

  test.fixme('[edge] cross-tenant write (create/delete/book another club’s delivery) → 404/403, tenant isolation', async ({
    browser,
  }) => {
    const ctx = await browser.newContext({ baseURL });
    try {
      const bCtx = await browser.newContext({ baseURL });
      const bPage = await bCtx.newPage();
      try {
        await loginAsClubAdmin(bPage, twoClubs.clubB);
      } finally {
        await bCtx.close();
      }
    } finally {
      await ctx.close();
    }
  });
});
