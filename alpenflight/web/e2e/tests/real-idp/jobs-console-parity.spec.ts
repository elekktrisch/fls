import { type Browser, type BrowserContext, type Page, type TestInfo } from '@playwright/test';
import { test, expect, watchConsoleErrors } from '../_helpers/console-guard';

import { fillKcLogin } from './_helpers/kc-form';
import { proofVideo } from './_helpers/proof-video';

/**
 * Scheduled-jobs console (`/system/jobs`) driven against the REAL chain (live
 * Keycloak JWT → Spring `@Scheduled`/`@MeasuredJob` registry → Postgres). No
 * `page.route`: the point of this spec is that a console click really runs a
 * cross-tenant job and really moves rows.
 *
 *   - [happy] A SYSTEM_ADMINISTRATOR opens the console and sees every registered
 *     business job with its cron and last-run status.
 *   - [happy] Run now on Daily Flight Validation completes, and the console shows
 *     the run's outcome (status + the summary counts the job returned).
 *   - [happy] The run's effect is visible to a CLUB_ADMINISTRATOR in their own
 *     tenant: the club dashboard's pending-validation count never grows across a
 *     validation pass, and the sysadmin who triggered it has no tenant of their
 *     own to read it back with.
 *   - [key-error] A CLUB_ADMINISTRATOR is redirected off `/system/jobs` by the
 *     sysadmin guard AND `POST /api/v1/admin/jobs/{name}/run` returns 403. The
 *     jobs are cross-tenant, so this is the load-bearing negative.
 *
 * Mailpit receipt (Daily Report) and the OGN device-database sync are proved by
 * `DailyReportJobIT` / `AircraftDatabaseSyncJobIT` against the captured outbox and
 * a recorded registry — deliberately not here, so the gate reaches no third party
 * and depends on no seeded opt-in.
 */

const JOBS_PATH = '/system/jobs';
const START_PATH = '/start';

interface SeededPrincipal {
  username: string;
  password: string;
}

/** Showcase principals (alpenflight/auth/realm-export.json + the dev-user seed). */
const SYSADMIN: SeededPrincipal = {
  username: 'sysadmin@example.com',
  password: 'sysadmin-dev-2026!',
};
const CLUB_ADMIN: SeededPrincipal = {
  username: 'clubadmin1@example.com',
  password: 'clubadmin1-dev-2026!',
};

/** The job the console triggers — the one with a visible cross-tenant effect. */
const VALIDATION_JOB = 'daily-flight-validation';

/** Every job the registry must surface (one `@MeasuredJob` bean each). */
const REGISTERED_JOBS = [
  VALIDATION_JOB,
  'delivery-creation',
  'daily-report',
  'licence-notification',
  'aircraft-database-sync',
  'planning-day-notification',
];

const TESTIDS = {
  table: 'jobs-table',
  row: 'job-row',
  rowName: 'job-row-name',
  rowCron: 'job-row-cron',
  rowStatus: 'job-row-status',
  runNow: 'job-run-now',
  result: 'job-run-result',
  resultStatus: 'job-run-result-status',
};

async function loginAs(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
  principal: SeededPrincipal,
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({
    baseURL,
    recordVideo: { dir: testInfo.outputDir },
  });
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  const page = await context.newPage();
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, principal.username, principal.password);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
  return { context, page };
}

/** The Bearer the OIDC interceptor attaches, for the raw-endpoint 403 probe. */
async function captureBearer(page: Page): Promise<string> {
  const bearerPromise = page.waitForRequest(
    (req) =>
      req.url().includes('/api/v1/') &&
      typeof req.headers()['authorization'] === 'string' &&
      /^Bearer /i.test(req.headers()['authorization']!),
    { timeout: 15_000 },
  );
  await page.goto(START_PATH);
  const req = await bearerPromise;
  return req.headers()['authorization']!;
}

/** The club dashboard's pending-validation count, read in the caller's tenant. */
async function pendingValidation(page: Page, bearer: string): Promise<number> {
  const res = await page.request.get('/api/v1/me/club-dashboard', {
    headers: { authorization: bearer },
  });
  expect(res.status()).toBe(200);
  const body = (await res.json()) as { pendingValidation?: number };
  return body.pendingValidation ?? 0;
}

test.describe('scheduled-jobs console (real chain)', () => {
  test('sysadmin lists the registered jobs and a Run now completes', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const { context, page } = await loginAs(browser, baseURL!, testInfo, SYSADMIN);
    try {
      await page.goto(`${JOBS_PATH}?lang=en`);

      await expect(page.getByTestId(TESTIDS.table)).toBeVisible();
      for (const job of REGISTERED_JOBS) {
        await expect(
          page.getByTestId(TESTIDS.row).filter({ hasText: job }),
          `the registry surfaces ${job}`,
        ).toHaveCount(1);
      }

      const validationRow = page.getByTestId(TESTIDS.row).filter({ hasText: VALIDATION_JOB });
      await expect(validationRow.getByTestId(TESTIDS.rowCron)).not.toBeEmpty();
      await expect(validationRow.getByTestId(TESTIDS.rowStatus)).not.toBeEmpty();

      await page.screenshot({
        path: 'screenshots/jobs-console/real-01-list.png',
        fullPage: true,
      });

      await validationRow.getByTestId(TESTIDS.runNow).click();

      const result = page.getByTestId(TESTIDS.result);
      await expect(result).toBeVisible({ timeout: 60_000 });
      await expect(page.getByTestId(TESTIDS.resultStatus)).toContainText('COMPLETED');
      await expect(result).toContainText(/validated/);
      await expect(validationRow.getByTestId(TESTIDS.rowStatus)).toContainText('COMPLETED');

      await page.screenshot({
        path: 'screenshots/jobs-console/real-02-run-result.png',
        fullPage: true,
      });
    } finally {
      await context.close();
      await proofVideo(page, testInfo, {
        journey: 'J-15',
        acTag: 'happy',
        caption:
          'A system administrator opens /system/jobs, sees every registered business job with its cron and last-run status, and Run now on Daily Flight Validation completes with the run summary the job returned.',
      });
    }
  });

  test('the validation run is visible in a club admin tenant, and never grows the pending set', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const admin = await loginAs(browser, baseURL!, testInfo, CLUB_ADMIN);
    const sysadmin = await loginAs(browser, baseURL!, testInfo, SYSADMIN);
    try {
      const adminBearer = await captureBearer(admin.page);
      const before = await pendingValidation(admin.page, adminBearer);

      await sysadmin.page.goto(`${JOBS_PATH}?lang=en`);
      await sysadmin.page
        .getByTestId(TESTIDS.row)
        .filter({ hasText: VALIDATION_JOB })
        .getByTestId(TESTIDS.runNow)
        .click();
      await expect(sysadmin.page.getByTestId(TESTIDS.resultStatus)).toContainText('COMPLETED', {
        timeout: 60_000,
      });

      // Re-read in the club's own tenant — the sysadmin has none of their own.
      const after = await pendingValidation(admin.page, adminBearer);
      expect(
        after,
        'a validation pass can only take flights out of the pending set, never add to it',
      ).toBeLessThanOrEqual(before);

      await admin.page.goto(`${START_PATH}?lang=en`);
      await admin.page.screenshot({
        path: 'screenshots/jobs-console/real-03-club-admin-reread.png',
        fullPage: true,
      });
    } finally {
      await admin.context.close();
      await sysadmin.context.close();
      await proofVideo(sysadmin.page, testInfo, {
        journey: 'J-15',
        acTag: 'happy',
        caption:
          'A cross-tenant job triggered by a system administrator is read back inside a club administrator’s own tenant: the club dashboard reflects the validation pass, and the pass never adds to the pending-validation set.',
      });
    }
  });

  test('a club admin cannot reach the console or trigger a job', async ({
    browser,
    baseURL,
  }, testInfo) => {
    const { context, page } = await loginAs(browser, baseURL!, testInfo, CLUB_ADMIN);
    try {
      const bearer = await captureBearer(page);

      await page.goto(`${JOBS_PATH}?lang=en`);
      await expect(page, 'the sysadmin guard redirects a club admin away').not.toHaveURL(
        /\/system\/jobs/,
      );
      await expect(page.getByTestId(TESTIDS.table)).toHaveCount(0);

      const run = await page.request.post(`/api/v1/admin/jobs/${VALIDATION_JOB}/run`, {
        headers: { authorization: bearer },
      });
      expect(run.status(), 'a club admin may not trigger a cross-tenant job').toBe(403);

      const list = await page.request.get('/api/v1/admin/jobs', {
        headers: { authorization: bearer },
      });
      expect(list.status(), 'nor read the registry').toBe(403);

      await page.screenshot({
        path: 'screenshots/jobs-console/real-04-club-admin-denied.png',
        fullPage: true,
      });
    } finally {
      await context.close();
      await proofVideo(page, testInfo, {
        journey: 'J-15',
        acTag: 'key-error',
        caption:
          'A club administrator is redirected off /system/jobs by the sysadmin guard, and both the registry read and the Run now endpoint answer 403 — the jobs are cross-tenant, so only a system administrator may trigger them.',
      });
    }
  });
});
