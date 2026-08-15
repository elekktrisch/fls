import { type Route } from '@playwright/test';
import { expect, test } from '../_helpers/console-guard';

// @mocked: http — mock-auth screen e2e

type Source = 'FILE_DEFAULT' | 'CLUB_OVERRIDE';

interface MockTemplate {
  templateKey: string;
  languageLocale: string;
  source: Source;
  subject?: string;
  body: string;
}

const lostPasswordDefault: MockTemplate = {
  templateKey: 'lostpassword',
  languageLocale: 'de',
  source: 'FILE_DEFAULT',
  subject: 'Passwort zurücksetzen',
  body: 'Hallo [[${name}]], hier ist dein Link.',
};

const planningOverride: MockTemplate = {
  templateKey: 'planningday-ok',
  languageLocale: 'de',
  source: 'CLUB_OVERRIDE',
  subject: 'Flugtag bestätigt',
  body: 'Dein Flugtag ist bestätigt.',
};

function rowId(t: Pick<MockTemplate, 'templateKey' | 'languageLocale'>): string {
  return `${t.templateKey}-${t.languageLocale}`;
}

function setupEmailTemplatesBackend(items: MockTemplate[]) {
  return async (route: Route) => {
    const req = route.request();
    const url = new URL(req.url());
    const method = req.method();
    const path = url.pathname;
    const editMatch = path.match(/^\/api\/v1\/email-templates\/([^/]+)\/([^/]+)$/);

    if (method === 'GET' && path === '/api/v1/email-templates') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(items),
      });
      return;
    }
    if (method === 'PUT' && editMatch) {
      const [, key, locale] = editMatch;
      const body = req.postDataJSON() as { subject: string; body: string };
      const idx = items.findIndex((t) => t.templateKey === key && t.languageLocale === locale);
      const next: MockTemplate = {
        templateKey: key!,
        languageLocale: locale!,
        source: 'CLUB_OVERRIDE',
        subject: body.subject,
        body: body.body,
      };
      if (idx === -1) items.push(next);
      else items[idx] = next;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(next),
      });
      return;
    }
    if (method === 'DELETE' && editMatch) {
      const [, key, locale] = editMatch;
      const idx = items.findIndex((t) => t.templateKey === key && t.languageLocale === locale);
      if (idx !== -1) {
        items[idx] = { ...items[idx]!, source: 'FILE_DEFAULT' };
      }
      await route.fulfill({ status: 204, body: '' });
      return;
    }
    await route.fallback();
  };
}

test('email templates: lists the union of file defaults and club overrides', async ({ page }) => {
  const items: MockTemplate[] = [{ ...lostPasswordDefault }, { ...planningOverride }];
  await page.route('**/api/v1/email-templates**', setupEmailTemplatesBackend(items));

  await page.goto('/email-templates');

  await expect(page.locator('h1')).toHaveText('Email templates');
  await expect(page.getByTestId('email-templates-table')).toBeVisible();
  await expect(page.getByTestId(`email-template-row-${rowId(lostPasswordDefault)}`)).toBeVisible();
  await expect(
    page.getByTestId(`email-template-override-${rowId(planningOverride)}`),
  ).toBeVisible();
  await expect(
    page.getByTestId(`email-template-override-${rowId(lostPasswordDefault)}`),
  ).toHaveCount(0);
});

test('email templates: saving an edit clones the default into a club override', async ({
  page,
}) => {
  const items: MockTemplate[] = [{ ...lostPasswordDefault }];
  await page.route('**/api/v1/email-templates**', setupEmailTemplatesBackend(items));

  await page.goto('/email-templates');
  await page.getByTestId(`email-template-row-${rowId(lostPasswordDefault)}`).click();

  await expect(page).toHaveURL(/\/email-templates\/.+/);
  await page.getByTestId('email-template-body').fill('Customized by club A');
  await page.getByTestId('email-template-save-button').locator('button').click();

  await expect(page).toHaveURL('/email-templates');
  await expect(
    page.getByTestId(`email-template-override-${rowId(lostPasswordDefault)}`),
  ).toBeVisible();
});

test('email templates: reset-to-default removes the club override', async ({ page }) => {
  const items: MockTemplate[] = [{ ...planningOverride }];
  await page.route('**/api/v1/email-templates**', setupEmailTemplatesBackend(items));

  await page.goto('/email-templates');
  await page.getByTestId(`email-template-row-${rowId(planningOverride)}`).click();

  await expect(page).toHaveURL(/\/email-templates\/.+/);
  await page.getByTestId('email-template-reset-button').locator('button').click();

  await expect(page).toHaveURL('/email-templates');
  await expect(page.getByTestId(`email-template-override-${rowId(planningOverride)}`)).toHaveCount(
    0,
  );
});

test('email templates: blank subject keeps Save disabled', async ({ page }) => {
  const items: MockTemplate[] = [{ ...lostPasswordDefault }];
  await page.route('**/api/v1/email-templates**', setupEmailTemplatesBackend(items));

  await page.goto('/email-templates');
  await page.getByTestId(`email-template-row-${rowId(lostPasswordDefault)}`).click();

  await page.locator('#EmailSubject').fill('');
  await page.locator('#EmailSubject').blur();

  await expect(page.getByTestId('email-template-save-button').locator('button')).toBeDisabled();
});
