import {
  type Browser,
  type BrowserContext,
  type Locator,
  type Page,
  type TestInfo,
} from '@playwright/test';
import { test, expect, watchConsoleErrors, allowConsoleErrors } from '../_helpers/console-guard';

import { enterViaNav } from '../_helpers/nav';
import { fillKcLogin } from './_helpers/kc-form';
import { proofVideo } from './_helpers/proof-video';


const CLUB_ADMIN1 = {
  username: 'clubadmin1@example.com',
  password: 'clubadmin1-dev-2026!',
};

const MEMBER_STATE_A = 'Aktivmitglied';
const MEMBER_STATE_B = 'Passivmitglied';

async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  const context = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  return context;
}

async function loginAsClubAdmin(page: Page): Promise<void> {
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, CLUB_ADMIN1.username, CLUB_ADMIN1.password);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
  await expect(page.getByTestId('landing-topbar-sign-in')).toHaveCount(0);
}

async function enterSection(page: Page, sectionPath: string): Promise<void> {
  await page.goto('/start?lang=de');
  await enterViaNav(page, sectionPath);
}

function projectBaseUrl(testInfo: TestInfo): string {
  return testInfo.project.use.baseURL ?? 'http://localhost:4201';
}

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

      await enterSection(page, '/persons');
      await expect(page).toHaveURL(/\/persons(\?|$)/);
      await expect(page.getByTestId('persons-table')).toBeVisible();
      const firstRow = page.locator('[data-testid^="person-row-"]').first();
      await expect(firstRow, 'the V36 seed renders ≥1 person row').toBeVisible();
      await firstRow.click();
      await expect(page).toHaveURL(/\/persons\/[^/]+\/edit$/);
      await expect(page.getByTestId('person-form')).toBeVisible();

      const memberNumberInput = page.getByTestId('member-number-input').locator('input');
      await expect(memberNumberInput).toBeVisible();
      const motorRole = page.getByTestId('role-motor-pilot');
      const motorWasChecked = await motorRole.isChecked();
      const stateSelect = page.getByTestId('member-state-select');
      const currentStateText = ((await stateSelect.textContent()) ?? '').trim();
      const targetState = currentStateText.includes(MEMBER_STATE_B)
        ? MEMBER_STATE_A
        : MEMBER_STATE_B;

      const memberNumber = `M-${Date.now() % 100_000_000}`;
      await memberNumberInput.fill(memberNumber);
      await motorRole.setChecked(!motorWasChecked);
      await stateSelect.locator('nz-select').click();
      await page.locator('nz-option-item').filter({ hasText: targetState }).click();
      await expect(stateSelect).toContainText(targetState);

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

      await page.getByTestId('persons-table').waitFor({ state: 'visible' });
      await firstRow.click();
      await expect(page).toHaveURL(/\/persons\/[^/]+\/edit$/);
      await expect(page.getByTestId('person-form')).toBeVisible();

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
    allowConsoleErrors(testInfo, /\b409\b/);
    const ctx = await newRecordedContext(browser, projectBaseUrl(testInfo), testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page);

      await enterSection(page, '/flight-types');
      await expect(page).toHaveURL(/\/flight-types(\?|$)/);
      await expect(page.getByTestId('flight-types-table')).toBeVisible();

      const stamp = Date.now() % 100_000;
      const dupCode = `D${stamp}`;

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

      await expect(page.getByTestId('flight-types-table')).toBeVisible();
      await page.getByTestId('flight-types-new-button').click();
      await expect(page.getByTestId('flight-types-edit-form')).toBeVisible();
      await page.locator('#FlightTypeName').fill(`J26 Duplikat ${stamp}`);
      await page.locator('#FlightCode').fill(dupCode);

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

      const codeError = fieldErrors(page, page.locator('#FlightCode'));
      await expect(
        codeError,
        'the duplicate-code 409 surfaces inline on the FlightCode field',
      ).toBeVisible();

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
