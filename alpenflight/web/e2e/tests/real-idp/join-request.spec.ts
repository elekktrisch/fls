import { type Browser, type BrowserContext, type Page, type TestInfo } from '@playwright/test';
import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';

import { fillKcRegistration } from './_helpers/kc-form';
import { freshTestUser, type TestUser } from './_helpers/test-user';
import { findUserByEmail, deleteUser } from './_helpers/keycloak-admin';
import { proofVideo } from './_helpers/proof-video';

/**
 * J-12a pilot self-serve club join against the REAL chain (live Keycloak auth +
 * real Spring backend + real Postgres) — the `parity_test`. Greenfield: legacy
 * has no join-by-code, so the proof is the real-idp join lifecycle, not a
 * legacy pairing.
 *
 * STUB: structure + the stable `data-testid` selectors the `/join` +
 * `/join/pending` SPA screens build to, plus a thin happy-path flow (signup →
 * `/join` → enter a join code → submit → `/join/pending`). The cases are
 * `test.fixme` until the screens + backend land — the spec collects and
 * type-checks (the screen SHAPE is committed here) but is a gate no-op, so the
 * journey-under-work proof job stays on the baseline until the assertions are
 * thickened. The full real assertions (approve / deny / withdraw / 429 / 404)
 * land then.
 */

const JOIN_PATH = '/join';
const JOIN_PENDING_PATH = '/join/pending';

/** A placeholder 8-char join code in the story alphabet (auto-uppercase). */
const SAMPLE_JOIN_CODE = 'ABCD2345';

async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  const context = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  return context;
}

/**
 * Signup → KC registration → the SPA lands a t_user-less authenticated user on
 * `/join` (the post-signup default, S-134 flip). The contract the `/join` screen
 * builds to.
 */
async function signupAndLandOnJoin(page: Page, user: TestUser): Promise<void> {
  await page.goto('/signup');
  await expect(page.getByTestId('signup-page')).toBeVisible();
  await page.getByTestId('signup-local').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcRegistration(page, user);
  await expect(page).toHaveURL(new RegExp(`${JOIN_PATH}$`));
  await expect(page.getByTestId('join-page')).toBeVisible();
}

/** Enter a join code (+ optional note) and submit on the `/join` screen. */
async function submitJoinCode(page: Page, code: string, note?: string): Promise<void> {
  await page.getByTestId('join-code-input').fill(code);
  if (note !== undefined) {
    await page.getByTestId('join-note-input').fill(note);
  }
  await page.getByTestId('join-submit').click();
}

test.describe('Pilot self-serve club join — real chain (real-idp)', () => {
  const cleanupEmails: string[] = [];

  test.afterEach(async () => {
    const targets = cleanupEmails.splice(0);
    for (const email of targets) {
      const kcUser = await findUserByEmail(email);
      if (!kcUser) continue;
      await deleteUser(kcUser.id, kcUser.email);
    }
  });

  test.fixme('[happy] signup → /join → submit a valid code → /join/pending shows the public club projection + Withdraw', async ({
    browser,
  }, testInfo) => {
    const baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    const user = freshTestUser();
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await signupAndLandOnJoin(page, user);
      cleanupEmails.push(user.email);

      await submitJoinCode(page, SAMPLE_JOIN_CODE, 'Looking forward to flying with you.');

      await expect(page).toHaveURL(new RegExp(`${JOIN_PENDING_PATH}$`));
      await expect(page.getByTestId('join-pending-page')).toBeVisible();
      await expect(page.getByTestId('join-pending-club-name')).toBeVisible();
      await expect(page.getByTestId('join-pending-withdraw')).toBeVisible();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-12a',
        caption:
          'J-12a · pilot join · a new signup enters a valid club join code and files a pending join request',
        acTag: 'happy',
      });
    }
  });
});
