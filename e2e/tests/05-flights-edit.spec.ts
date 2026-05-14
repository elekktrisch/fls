// e2e/tests/05-flights-edit.spec.ts
//
// Plan row #05: Edit a seeded glider flight's glider-specific fields and
// assert the mutation persisted.
//
// Approach: drive the AngularJS flight-edit form at /flights/:id, change the
// FlightComment field (the safest single-field mutation on this form — see
// TESTING.md "T3 round-trip" which uses exactly this field via curl), submit
// the form, then assert persistence two ways:
//   1. API readback via /api/v1/flights/<id> using the bearer token stashed
//      in sessionStorage by the loggedInPage fixture.
//   2. Re-loading the route in the browser and reading the input value.
//
// Contract gaps noted (no shared infra modified):
//   - The save button has no data-testid — uses semantic getByRole + name
//     filter. TODO testid: add data-testid="form-save" / "form-cancel" /
//     "form-delete" on flight-edit-form.html buttons.
//   - The FlightComment field has no data-testid — uses its stable id
//     `#Comment`. TODO testid: a `data-testid="flight-comment-input"` on
//     flight-edit-glider-form.html (id="Comment", line 446) would harden
//     this selector against id renames.

import { expect, gotoRoute, screenshot, test } from '../fixtures';
import type { Page } from '@playwright/test';

const FLIGHT_ID = '728a5199-3e1e-43a6-970a-c3cd741884ff'; // seeded "PAX flight"
const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';

async function getBearerToken(page: Page): Promise<string> {
  const token = await page.evaluate(() => {
    const raw = sessionStorage.getItem('ngStorage-loginResult');
    if (!raw) return null;
    try {
      return JSON.parse(raw).access_token as string;
    } catch {
      return null;
    }
  });
  expect(token, 'expected access_token in sessionStorage from loggedInPage').toBeTruthy();
  return token!;
}

async function readFlightCommentViaApi(page: Page, token: string): Promise<string> {
  const res = await page.request.get(`${API_BASE}/api/v1/flights/${FLIGHT_ID}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(res.ok(), `GET /api/v1/flights/${FLIGHT_ID} -> ${res.status()}`).toBeTruthy();
  const body = await res.json();
  return body?.GliderFlightDetailsData?.FlightComment ?? '';
}

test('flights-edit: round-trip FlightComment via form submit', async ({ loggedInPage, freshDb }) => {
  // 1. Load the edit form for the seeded PAX flight.
  await gotoRoute(loggedInPage, `/flights/${FLIGHT_ID}`);

  // The glider form's comment field is a plain <input type="text" id="Comment">
  // bound to flightDetails.GliderFlightDetailsData.FlightComment.
  const commentInput = loggedInPage.locator('input#Comment');
  await expect(commentInput).toBeVisible({ timeout: 10_000 });

  // Sanity: the seeded value should be populated. The test fixture stamps
  // this flight with "PAX flight" but we don't hard-code that — just ensure
  // the input is hydrated (form is loaded, not blank-on-error).
  const originalValue = await commentInput.inputValue();
  expect(originalValue.length, 'expected seeded FlightComment to be non-empty').toBeGreaterThan(0);

  // Cross-check the API readback matches what the form rendered.
  const token = await getBearerToken(loggedInPage);
  const apiBefore = await readFlightCommentViaApi(loggedInPage, token);
  expect(apiBefore).toBe(originalValue);

  // 2. Edit the comment to a unique value.
  const newComment = `e2e-edit ${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  await commentInput.fill(newComment);
  await expect(commentInput).toHaveValue(newComment);

  // 3. Submit. The form's <button type="submit" translate="SAVE">Save</button>
  // (flight-edit-form.html:42) has no testid — match by role + accessible name.
  // TODO testid: data-testid="form-save" on flight-edit-form.html.
  const saveButton = loggedInPage
    .locator('button[type="submit"]')
    .filter({ hasText: /^\s*(Save|Speichern)\s*$/i })
    .first();
  await expect(saveButton).toBeEnabled();
  await saveButton.click();

  // FlightsController.save() -> doSave() -> $saveFlight (POST + X-HTTP-Method-Override: PUT)
  // -> on success calls $scope.cancel(), which navigates to '/flights'.
  await loggedInPage.waitForURL(/#\/flights$/, { timeout: 15_000 });
  await loggedInPage.waitForLoadState('domcontentloaded');

  // 4. Assert persistence via API.
  const apiAfter = await readFlightCommentViaApi(loggedInPage, token);
  expect(apiAfter, 'API readback should reflect the edited FlightComment').toBe(newComment);

  // 5. Assert persistence via UI: re-load the edit route and read the input.
  await gotoRoute(loggedInPage, `/flights/${FLIGHT_ID}`);
  const reloadedInput = loggedInPage.locator('input#Comment');
  await expect(reloadedInput).toBeVisible({ timeout: 10_000 });
  await expect(reloadedInput).toHaveValue(newComment);
  await screenshot(loggedInPage, '05-flights-edit-01');
});
