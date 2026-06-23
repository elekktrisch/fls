import { type Browser, type BrowserContext, type Page, type TestInfo } from '@playwright/test';
import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';

import { enterViaNav } from '../_helpers/nav';
import {
  provisionTwoClubs,
  loginAsClubAdmin,
  type ClubAdmin,
  type TwoClubFixture,
} from './_helpers/two-club-fixture';
import { proofVideo } from './_helpers/proof-video';

/**
 * Club-customizable masterdata against the REAL chain (live Keycloak auth +
 * real Spring backend + real Postgres) — the J-11 `parity_test`. Both screens
 * are greenfield, so the proof is the real-idp CRUD round-trip video, NOT a
 * legacy pairing.
 *
 * Email templates: a CLUB_ADMINISTRATOR opens `/email-templates`, sees the
 * S-082 file defaults UNIONed with the caller's own-club overrides, customizes
 * one default (cloning it to a per-club override that renders at send time
 * without a redeploy), and resets it (deleting the override so the file default
 * renders again). Customizing in club A must NOT mutate the file default a
 * second club sees.
 *
 * Articles: the shipped S-054 `/articles` screen round-trips tenant-scoped
 * create / edit / soft-delete, and `includeInactive` surfaces an inactive row —
 * verified against the deployed UI, not rebuilt. Override-at-send is proven by
 * the backend IT; the UI-observable proof is the override CRUD round-trip.
 */

const EMAIL_TEMPLATES_PATH = '/email-templates';

/** The S-082 file defaults the union read seeds from (all keyed `de`). */
const DEFAULT_KEY = 'planningday-ok';
const DEFAULT_LOCALE = 'de';
const DEFAULT_ROW = `${DEFAULT_KEY}-${DEFAULT_LOCALE}`;

async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  const context = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  return context;
}

/** Open the email-templates list through the nav chrome and wait for the table. */
async function openEmailTemplates(page: Page, admin: ClubAdmin): Promise<void> {
  await loginAsClubAdmin(page, admin);
  await page.goto('/start?lang=en');
  await enterViaNav(page, EMAIL_TEMPLATES_PATH);
  await expect(page).toHaveURL(EMAIL_TEMPLATES_PATH);
  await expect(page.getByTestId('email-templates-table')).toBeVisible();
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

  test('[happy] union read shows the file defaults as defaults; another club never appears', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await openEmailTemplates(page, twoClubs.clubA);

      // The S-082 set ships as file defaults — each renders a row tagged
      // "Default" (no override indicator) until a club customizes it.
      for (const key of [
        'planningday-ok',
        'planningday-cancel',
        'planningday-assignment-notification',
      ]) {
        const rowId = `${key}-${DEFAULT_LOCALE}`;
        await expect(page.getByTestId(`email-template-row-${rowId}`)).toBeVisible();
        await expect(page.getByTestId(`email-template-override-${rowId}`)).toHaveCount(0);
      }
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-11',
        caption:
          'J-11 · email templates · a club admin sees the file-default templates, each marked as a default',
        acTag: 'happy',
      });
    }
  });

  test('[happy] customizing a default clones a per-club override and persists; the file default is untouched for another club', async ({
    browser,
  }, testInfo) => {
    const customBody = 'Customized by club A — planning confirmed.';
    const customSubject = 'Club A · Flugtag bestätigt';

    const ctxA = await newRecordedContext(browser, baseURL, testInfo);
    const pageA = await ctxA.newPage();
    try {
      await openEmailTemplates(pageA, twoClubs.clubA);

      // Edit the file default → upsert a per-club override (club_id = caller).
      await pageA.getByTestId(`email-template-row-${DEFAULT_ROW}`).click();
      await expect(pageA).toHaveURL(`${EMAIL_TEMPLATES_PATH}/${DEFAULT_KEY}/${DEFAULT_LOCALE}`);
      // File defaults carry no subject, so Save is gated until one is supplied.
      await pageA.locator('#EmailSubject').fill(customSubject);
      await pageA.getByTestId('email-template-body').fill(customBody);
      await pageA.getByTestId('email-template-save-button').locator('button').click();
      await expect(pageA).toHaveURL(EMAIL_TEMPLATES_PATH);

      // The row now reads as an override (the clone-on-customize indicator).
      await expect(pageA.getByTestId(`email-template-override-${DEFAULT_ROW}`)).toBeVisible();

      // Re-open the row — the customized body persisted in the DB override.
      await pageA.getByTestId(`email-template-row-${DEFAULT_ROW}`).click();
      await expect(pageA).toHaveURL(`${EMAIL_TEMPLATES_PATH}/${DEFAULT_KEY}/${DEFAULT_LOCALE}`);
      await expect(pageA.getByTestId('email-template-body')).toHaveValue(customBody);
      await expect(pageA.locator('#EmailSubject')).toHaveValue(customSubject);
    } finally {
      await ctxA.close();
    }

    // A second club still sees the system/file default — club A's override is
    // tenant-scoped and never mutated the shipped template.
    const ctxB = await newRecordedContext(browser, baseURL, testInfo);
    const pageB = await ctxB.newPage();
    try {
      await openEmailTemplates(pageB, twoClubs.clubB);
      await expect(pageB.getByTestId(`email-template-row-${DEFAULT_ROW}`)).toBeVisible();
      await expect(pageB.getByTestId(`email-template-override-${DEFAULT_ROW}`)).toHaveCount(0);
    } finally {
      await ctxB.close();
      await proofVideo(pageB, testInfo, {
        journey: 'J-11',
        caption:
          'J-11 · email templates · customizing in club A clones a per-club override; another club still sees the file default',
        acTag: 'happy',
      });
    }
  });

  test('[happy] reset removes the override and the file default re-surfaces', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await openEmailTemplates(page, twoClubs.clubA);

      // The prior test left club A holding an override for this row.
      await expect(page.getByTestId(`email-template-override-${DEFAULT_ROW}`)).toBeVisible();

      await page.getByTestId(`email-template-row-${DEFAULT_ROW}`).click();
      await expect(page).toHaveURL(`${EMAIL_TEMPLATES_PATH}/${DEFAULT_KEY}/${DEFAULT_LOCALE}`);
      await page.getByTestId('email-template-reset-button').locator('button').click();
      await expect(page).toHaveURL(EMAIL_TEMPLATES_PATH);

      // The row returns to default state — the override indicator is gone.
      await expect(page.getByTestId(`email-template-override-${DEFAULT_ROW}`)).toHaveCount(0);

      // Re-open — the file default body is back (the reset button no longer
      // renders, since the row is a file default again).
      await page.getByTestId(`email-template-row-${DEFAULT_ROW}`).click();
      await expect(page).toHaveURL(`${EMAIL_TEMPLATES_PATH}/${DEFAULT_KEY}/${DEFAULT_LOCALE}`);
      await expect(page.getByTestId('email-template-reset-button')).toHaveCount(0);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-11',
        caption:
          'J-11 · email templates · reset-to-default removes the override and the file default re-surfaces',
        acTag: 'happy',
      });
    }
  });
});

const ARTICLES_PATH = '/articles';

/** A per-run-unique article number so reruns/retries never 409 on the index. */
function uniqueArticleNumber(prefix: string): string {
  const tail = Math.random().toString(36).slice(2, 8).toUpperCase();
  return `${prefix}-${tail}`;
}

/** Read the rendered list row's id (`articles-row-art-<uuid>`) for a named article. */
async function articleRowId(page: Page, articleName: string): Promise<string> {
  const row = page.locator('[data-testid^="articles-row-"]').filter({ hasText: articleName });
  await expect(row).toBeVisible();
  const testId = await row.getAttribute('data-testid');
  expect(testId, 'the created article row must carry an articles-row-<id> testid').toBeTruthy();
  return testId!.replace(/^articles-row-/, '');
}

test.describe('Articles — tenant-scoped CRUD real chain (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let baseURL: string;
  let twoClubs: TwoClubFixture;

  test.beforeAll(async ({ browser }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    twoClubs = await provisionTwoClubs(browser, baseURL, 'art');
  });

  test.afterAll(async () => {
    await twoClubs?.dispose();
  });

  test('[happy] a club admin creates, edits, deactivates an article; includeInactive surfaces the inactive row', async ({
    browser,
  }, testInfo) => {
    const articleNumber = uniqueArticleNumber('E2E');
    const articleName = `Tow service ${articleNumber}`;
    const renamed = `${articleName} (renamed)`;

    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, twoClubs.clubA);

      // Create — the new row lands in the caller's tenant list.
      await page.goto(ARTICLES_PATH);
      await expect(page.getByTestId('articles-table')).toBeVisible();
      await page.getByTestId('articles-new-button').locator('button').click();
      await expect(page).toHaveURL(`${ARTICLES_PATH}/new`);
      await page.locator('#ArticleNumber').fill(articleNumber);
      await page.locator('#ArticleName').fill(articleName);
      const created = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new URL(r.url()).pathname === '/api/v1/articles' &&
          r.status() === 201,
      );
      await page.getByTestId('articles-save-button').locator('button').click();
      await created;
      await expect(page).toHaveURL(ARTICLES_PATH);

      const articleId = await articleRowId(page, articleName);

      // Edit — the rename round-trips and persists across a reload.
      await page.getByTestId(`articles-row-${articleId}`).click();
      await expect(page).toHaveURL(`${ARTICLES_PATH}/${articleId}/edit`);
      await page.locator('#ArticleName').fill(renamed);
      await page.getByTestId('articles-save-button').locator('button').click();
      await expect(page).toHaveURL(ARTICLES_PATH);
      await expect(page.getByTestId(`articles-row-${articleId}`)).toContainText(renamed);

      // Deactivate — isActive=false drops the row out of the default
      // (active-only) list. Soft-delete is a separate, terminal operation:
      // a deleted row never resurfaces, so includeInactive (which surfaces
      // isActive=false rows) is driven by deactivate, not delete.
      await page.getByTestId(`articles-row-${articleId}`).click();
      await expect(page).toHaveURL(`${ARTICLES_PATH}/${articleId}/edit`);
      await page.getByTestId('articles-flag-active').uncheck();
      await page.getByTestId('articles-save-button').locator('button').click();
      await expect(page).toHaveURL(ARTICLES_PATH);
      await expect(page.getByTestId(`articles-row-${articleId}`)).toHaveCount(0);

      // includeInactive — the deactivated row re-surfaces, tagged inactive.
      await page.getByTestId('articles-include-inactive').check();
      await expect(page.getByTestId(`articles-row-${articleId}`)).toBeVisible();
      await expect(page.getByTestId(`articles-badge-inactive-${articleId}`)).toBeVisible();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-11',
        caption:
          'J-11 · articles · a club admin creates, edits, deactivates an article and surfaces it via includeInactive',
        acTag: 'happy',
      });
    }
  });
});
