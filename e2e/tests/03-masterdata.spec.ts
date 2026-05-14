import { test, expect, gotoRoute, screenshot } from '../fixtures';
import type { Page } from '@playwright/test';

// Each entity may have a list page and (where seed data exists) a per-row form.
// `hasSeedData = false` means the list renders but is empty; we skip the form test.
type Entity = {
  name: string;
  listPath: string;
  hasSeedData: boolean;
  // Optional: a string we expect to find on the form page once a row is opened.
  // Defaults to checking that at least one populated <input> exists.
  formAssertion?: (page: Page) => Promise<void>;
};

const ENTITIES: Entity[] = [
  { name: 'aircrafts',            listPath: '/masterdata/aircrafts',             hasSeedData: true },
  { name: 'persons',              listPath: '/masterdata/persons',               hasSeedData: true },
  { name: 'users',                listPath: '/masterdata/users',                 hasSeedData: true },
  { name: 'clubs',                listPath: '/masterdata/clubs',                 hasSeedData: true },
  { name: 'locations',            listPath: '/masterdata/locations',             hasSeedData: true },
  { name: 'flighttypes',          listPath: '/masterdata/flightTypes',           hasSeedData: true },
  { name: 'memberstates',         listPath: '/masterdata/memberStates',          hasSeedData: true },
  { name: 'accountingrules',      listPath: '/masterdata/accountingRuleFilters', hasSeedData: false },
  { name: 'deliveries',           listPath: '/masterdata/deliveries',            hasSeedData: false },
  { name: 'deliverycreationtests', listPath: '/masterdata/deliveryCreationTests', hasSeedData: false },
  { name: 'personcategories',     listPath: '/masterdata/personCategories',      hasSeedData: false },
];

async function dataRowCount(page: Page): Promise<number> {
  // Every data row carries data-testid="row" (see e2e/SELECTORS.md). Header / filter / pager rows don't.
  return page.locator('tbody [data-testid="row"]').count();
}

async function openFirstRowForm(page: Page): Promise<void> {
  // Two row layouts in the wild (see e2e/SELECTORS.md):
  //   1. Row-click pattern: the <tr data-testid="row"> itself is the click target (ng-click on the <tr>).
  //   2. Pencil-link pattern: a separate <a data-testid="row-edit"> pencil icon inside the row.
  // Prefer the pencil link if it exists, otherwise fall back to clicking the row.
  const rowEdit = page.locator('tbody [data-testid="row-edit"]').first();
  const row = page.locator('tbody [data-testid="row"]').first();
  const target = (await rowEdit.count()) > 0 ? rowEdit : row;
  await target.waitFor({ state: 'visible' });
  const urlBefore = page.url();
  await target.click();
  await page.waitForFunction(prev => location.href !== prev, urlBefore);
  await page.waitForLoadState('domcontentloaded');
  await page.waitForTimeout(500);
  // Wait for any busy indicator the form fetch may have introduced.
  await page.waitForFunction(() => {
    const spinners = Array.from(document.querySelectorAll('[data-testid="busy-indicator"]')) as HTMLElement[];
    return spinners.every(el => {
      const rect = el.getBoundingClientRect();
      return rect.width === 0 && rect.height === 0;
    });
  }, undefined, { timeout: 15_000 });
  await page.waitForTimeout(300);
}

async function expectFormPopulated(page: Page): Promise<void> {
  // At least one visible text input or textarea should carry a value once the form is hydrated.
  const populated = await page
    .locator('input[type="text"], input:not([type]), textarea')
    .evaluateAll(els =>
      els.some(el => {
        const v = (el as HTMLInputElement | HTMLTextAreaElement).value;
        return typeof v === 'string' && v.trim() !== '';
      }),
    );
  expect(populated, 'expected at least one populated form field after opening the row').toBeTruthy();
}

for (const entity of ENTITIES) {
  test(`masterdata-list:${entity.name}`, async ({ loggedInPage }) => {
    await gotoRoute(loggedInPage, entity.listPath);
    if (entity.hasSeedData) {
      const count = await dataRowCount(loggedInPage);
      expect(count, `expected ${entity.name} list to have at least one row`).toBeGreaterThan(0);
    }
    await screenshot(loggedInPage, `masterdata-${entity.name}-list`);
  });

  if (entity.hasSeedData) {
    test(`masterdata-form:${entity.name}`, async ({ loggedInPage }) => {
      await gotoRoute(loggedInPage, entity.listPath);
      await openFirstRowForm(loggedInPage);
      if (entity.formAssertion) {
        await entity.formAssertion(loggedInPage);
      } else {
        await expectFormPopulated(loggedInPage);
      }
      await screenshot(loggedInPage, `masterdata-${entity.name}-form`);
    });
  }
}
