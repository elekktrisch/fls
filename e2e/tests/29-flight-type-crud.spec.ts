// Spec #29: FlightType CRUD. Pencil-link table — row itself not clickable,
// edit happens via `[data-testid="row-edit"]` link.
//
// TODO testid: `.fls-new-button button`, row trash anchor, form Save/Cancel.

import { expect, gotoRoute, screenshot, test } from '../fixtures';
import { testId } from '../test-id';
import { API_BASE, getBearerToken } from '../test-data';
import type { Page } from '@playwright/test';

const LIST_PATH = '/masterdata/flightTypes';

function rowByText(page: Page, text: string) {
  return page.locator('tbody [data-testid="row"]', { hasText: text });
}

async function openListAndWait(page: Page) {
  await gotoRoute(page, LIST_PATH);
  await page.locator('tbody [data-testid="row"]').first().waitFor({ state: 'visible' });
}

async function submitForm(page: Page) {
  await page.locator('form[name="flightTypeForm"] button[type="submit"]').click();
  await page.waitForURL('**/#/masterdata/flightTypes', { timeout: 10_000 });
  await page.waitForLoadState('domcontentloaded');
  await page.locator('tbody [data-testid="row"]').first().waitFor({ state: 'visible' });
}

async function filterTo(page: Page, needle: string) {
  // <fls-simple-search-bar> filters by FlightCode OR FlightTypeName.
  const search = page.locator('.search-bar input').first();
  await search.fill(needle);
}

test('masterdata-crud:flightTypes create-edit-delete', async ({ loggedInPage }, testInfo) => {
  const page = loggedInPage;
  const id = testId(testInfo);
  const CODE = id.short.slice(0, 5).toUpperCase();
  // Disjoint substrings — see TEST_WRITING.md §2.
  const NAME_INITIAL = `E2E FT ${id.short} initial`;
  const NAME_EDITED  = `E2E FT ${id.short} updated`;

  // Pre-clean.
  const token = await getBearerToken(loggedInPage);
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  const listRes = await page.request.get(`${API_BASE}/api/v1/flighttypes`, { headers });
  if (listRes.ok()) {
    const types = await listRes.json() as Array<{ FlightTypeId: string; FlightCode: string }>;
    for (const t of types) {
      if (t.FlightCode !== CODE) continue;
      await page.request.post(`${API_BASE}/api/v1/flighttypes/${t.FlightTypeId}`, {
        headers: { ...headers, 'X-HTTP-Method-Override': 'DELETE' },
      });
    }
  }

  // CREATE
  await openListAndWait(page);
  await page.locator('.fls-new-button button').click();
  await page.waitForURL('**/#/masterdata/flightTypes/new', { timeout: 10_000 });
  await page.locator('#FlightCode').waitFor({ state: 'visible' });

  await page.locator('#FlightCode').fill(CODE);
  await page.locator('#FlightTypeName').fill(NAME_INITIAL);
  await page.locator('#IsForGliderFlights').check();
  await submitForm(page);

  await filterTo(page, CODE);
  const createdRow = rowByText(page, NAME_INITIAL);
  await expect(createdRow).toHaveCount(1, { timeout: 10_000 });

  // EDIT — pencil link, not the row.
  await createdRow.locator('[data-testid="row-edit"]').click();
  await page.waitForURL(/\/masterdata\/flightTypes\/[0-9a-fA-F-]{36}$/, { timeout: 10_000 });
  await page.locator('#FlightTypeName').waitFor({ state: 'visible' });
  await expect(page.locator('#FlightCode')).toHaveValue(CODE);
  await expect(page.locator('#FlightTypeName')).toHaveValue(NAME_INITIAL);
  await page.locator('#FlightTypeName').fill(NAME_EDITED);
  await submitForm(page);

  await filterTo(page, CODE);
  const editedRow = rowByText(page, NAME_EDITED);
  await expect(editedRow).toHaveCount(1, { timeout: 10_000 });
  await expect(rowByText(page, NAME_INITIAL)).toHaveCount(0);

  // DELETE — accept native confirm, wait for the POST+DELETE roundtrip.
  page.once('dialog', dialog => dialog.accept());
  const deletePromise = page.waitForResponse(r =>
    /\/api\/v1\/flighttypes\/[a-f0-9-]+$/i.test(r.url()) && r.request().method() === 'POST',
    { timeout: 10_000 });
  await editedRow.locator('a:has(.fa-trash-o)').click();
  await deletePromise;

  // Re-navigate so ng-table re-fetches.
  await gotoRoute(page, '/masterdata/flightTypes');
  await filterTo(page, CODE);
  await expect(rowByText(page, NAME_EDITED)).toHaveCount(0, { timeout: 10_000 });
  await screenshot(loggedInPage, '29-flight-type-crud-01');
});
