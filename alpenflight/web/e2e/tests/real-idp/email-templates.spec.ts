import { type Browser, type BrowserContext, type TestInfo } from '@playwright/test';
import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';

import { enterViaNav } from '../_helpers/nav';
import {
  provisionTwoClubs,
  loginAsClubAdmin,
  type TwoClubFixture,
} from './_helpers/two-club-fixture';
import { proofVideo } from './_helpers/proof-video';

/**
 * EmailTemplate club-customization real chain (live Keycloak auth + real Spring
 * backend + real Postgres). The journey's `parity_test` — greenfield UI, so the
 * proof is the real-idp CRUD + override-at-send video, NOT a legacy pairing.
 *
 * A CLUB_ADMINISTRATOR opens `/email-templates`, sees the system/file defaults
 * UNIONed with the caller's own-club override rows, edits one template (which
 * clones it to a per-club override that renders at send time without a
 * redeploy), and resets it (deleting the override so the file default renders
 * again). Write is `CLUB_ADMINISTRATOR`, own club only; a non-admin gets 403.
 *
 * The screen lands in T-07 and the assertions thicken in T-14; this stub commits
 * the SCREEN SHAPE — the selectors + a thin happy-path flow entered through the
 * nav chrome, not a URL-only goto — so the proof window stands from the journey's
 * first task and accumulates captures as the later specs go green.
 */

/** Masterdata path testid for the club-admin email-templates entry. */
const NAV_PATH = '/email-templates';

async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  const context = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  return context;
}

test.describe('Email templates — club customization real chain (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let baseURL: string;
  let twoClubs: TwoClubFixture;

  test.beforeAll(async ({ browser }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    twoClubs = await provisionTwoClubs(browser, baseURL, 'tpl');
  });

  test.afterAll(async () => {
    await twoClubs?.dispose();
  });

  test('[happy] a club admin enters via the nav, sees the union list, customizes a template, and resets it', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, twoClubs.clubA);

      await page.goto('/start?lang=en');
      await enterViaNav(page, NAV_PATH);
      await expect(page).toHaveURL(NAV_PATH);
      await expect(page.getByTestId('email-templates-table')).toBeVisible();

      // Union read — system/file defaults ∪ the caller's own-club overrides. The
      // list carries at least the seeded transactional-email keys.
      const rows = page.locator('[data-testid^="email-template-row-"]');
      await expect(rows.first()).toBeVisible();

      // Open one template to edit and save a customization → a per-club override
      // row (IsSystemTemplate=false, club_id=caller) that renders at send time.
      await rows.first().click();
      await expect(page).toHaveURL(/\/email-templates\/.+/);
      await page.getByTestId('email-template-body').fill('Customized by club A');
      await page.getByTestId('email-template-save-button').click();
      await expect(page).toHaveURL(NAV_PATH);

      // Reset to default — removing the override row restores the system/file
      // default.
      await rows.first().click();
      await page.getByTestId('email-template-reset-button').click();
      await expect(page.getByTestId('email-templates-table')).toBeVisible();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-11',
        caption:
          'J-11 · email templates · a club admin customizes a transactional template and resets it to the system default',
        acTag: 'happy',
      });
    }
  });
});
