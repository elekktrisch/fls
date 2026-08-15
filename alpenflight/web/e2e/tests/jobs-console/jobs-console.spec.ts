import { type Route } from '@playwright/test';
import { expect, test } from '../_helpers/console-guard';

type JobRunStatus = 'NEVER_RUN' | 'RUNNING' | 'COMPLETED' | 'FAILED';

interface JobRun {
  status: JobRunStatus;
  startedAt?: string;
  finishedAt?: string;
  summary?: string;
}

interface JobRow {
  name: string;
  cron: string;
  lastRun: JobRun;
}

const seedJobs: JobRow[] = [
  {
    name: 'daily-flight-validation',
    cron: '0 0 2 * * *',
    lastRun: {
      status: 'COMPLETED',
      startedAt: '2026-07-22T02:00:00Z',
      finishedAt: '2026-07-22T02:00:11Z',
      summary: '3 validated, 1 locked',
    },
  },
  {
    name: 'daily-report',
    cron: '0 0 3 * * *',
    lastRun: {
      status: 'FAILED',
      startedAt: '2026-07-22T03:00:00Z',
      finishedAt: '2026-07-22T03:00:04Z',
      summary: 'SMTP timeout',
    },
  },
  {
    name: 'planning-day-notification',
    cron: '0 0 6 * * *',
    lastRun: { status: 'NEVER_RUN' },
  },
];

function setupJobsBackend(jobs: JobRow[]) {
  return async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(jobs),
    });
  };
}

function setupRunNowBackend() {
  return async (route: Route) => {
    const name = new URL(route.request().url()).pathname.split('/').at(-2) ?? '';
    const run: JobRun = {
      status: 'COMPLETED',
      startedAt: '2026-07-23T10:00:00Z',
      finishedAt: '2026-07-23T10:00:07Z',
      summary: `${name}: 2 validated, 1 locked`,
    };
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(run),
    });
  };
}

test.describe('jobs-console', () => {
  test('jobs-console: /system/jobs lists registered jobs + Run-now shows a completed result', async ({
    page,
  }) => {
    const jobs = seedJobs.map((j) => ({ ...j }));
    await page.route('**/api/v1/admin/jobs', setupJobsBackend(jobs));
    await page.route('**/api/v1/admin/jobs/*/run', setupRunNowBackend());

    await page.goto('/system/jobs');

    await expect(page.getByTestId('jobs-table')).toBeVisible();
    await expect(page.getByTestId('job-row')).toHaveCount(seedJobs.length);

    const validationRow = page
      .getByTestId('job-row')
      .filter({ hasText: 'daily-flight-validation' });
    await expect(validationRow).toBeVisible();
    await expect(validationRow.getByTestId('job-row-name')).toHaveText('daily-flight-validation');
    await expect(validationRow.getByTestId('job-row-status')).toContainText('COMPLETED');

    await page.screenshot({
      path: 'screenshots/jobs-console/01-list-populated.png',
      fullPage: true,
    });

    await validationRow.getByTestId('job-run-now').click();
    await expect(page.getByTestId('job-run-result')).toBeVisible();
    await expect(page.getByTestId('job-run-result')).toContainText('COMPLETED');

    await page.screenshot({ path: 'screenshots/jobs-console/02-run-result.png', fullPage: true });
  });
});
