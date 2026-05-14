// e2e/tests/05-flights-edit.spec.ts
//
// Plan row #05: Edit a glider flight's FlightComment via the UI form and
// assert the mutation persisted.
//
// Self-contained: each run creates (or re-uses) a flight whose Comment is
// derived from the test title (stable per-test id, see e2e/test-id.ts).
// No reliance on shared fixture rows, so this spec is safe under parallel
// workers and doesn't trample test 06/19/20/22/23 which used to fight
// over F1500005.
//
// Contract gaps:
//   - The save button has no data-testid — uses role + accessible name.
//   - The FlightComment field has no data-testid — uses its stable id
//     `#Comment` from flight-edit-glider-form.html.

import { expect, gotoRoute, screenshot, test } from '../fixtures';
import { testId } from '../test-id';
import { API_BASE, authHeaders, ensureGliderFlight, getBearerToken } from '../test-data';

test('flights-edit: round-trip FlightComment via form submit', async ({ loggedInPage }, testInfo) => {
  const id = testId(testInfo);
  // Initial flight Comment — what the test starts from. Stable.
  const initialComment = `${id.name} initial`;
  // What the test edits it to. Also stable, so re-runs are idempotent.
  const editedComment = `${id.name} edited`;

  const token = await getBearerToken(loggedInPage);

  // Set up a flight owned by THIS test. ensureGliderFlight upserts; if a
  // previous run left it at `editedComment`, normalise back to `initialComment`
  // via SQL so the assertion below holds.
  const { flightId } = await ensureGliderFlight(loggedInPage.request, token, {
    comment: initialComment,
  });
  // Snap the Comment back to the initial value if a previous run edited it
  // (we can't change PK via API; the row is the same).
  await loggedInPage.request.put(`${API_BASE}/api/v1/flights/${flightId}`, {
    headers: authHeaders(token),
    data: await loadFlightForUpdate(loggedInPage, token, flightId, initialComment),
  }).then(r => r.ok()
    ? null
    : Promise.reject(new Error(`PUT /flights/${flightId} init -> ${r.status()}: ${r.text()}`)));

  // 1. Load the edit form.
  await gotoRoute(loggedInPage, `/flights/${flightId}`);

  // The glider form's comment field is a plain <input type="text" id="Comment">
  // bound to flightDetails.GliderFlightDetailsData.FlightComment.
  const commentInput = loggedInPage.locator('input#Comment');
  await expect(commentInput).toBeVisible({ timeout: 10_000 });

  // The input should be hydrated with the initial value.
  await expect(commentInput).toHaveValue(initialComment, { timeout: 5_000 });

  // 2. Edit the comment.
  await commentInput.fill(editedComment);
  await expect(commentInput).toHaveValue(editedComment);

  // 3. Submit. SAVE/Speichern, scoped to the flight form (the navbar's
  // login form has its own submit button on every page).
  const saveButton = loggedInPage
    .locator('form[name="flightDetailsForm"] button[type="submit"]')
    .filter({ hasText: /^\s*(Save|Speichern)\s*$/i })
    .first();
  await expect(saveButton).toBeEnabled();
  await saveButton.click();
  await loggedInPage.waitForURL(/#\/flights$/, { timeout: 15_000 });

  // 4. Assert persistence via the API.
  const apiAfter = await readFlightComment(loggedInPage, token, flightId);
  expect(apiAfter, 'API readback should reflect the edited FlightComment').toBe(editedComment);

  // 5. Assert persistence via the UI: re-load and read the input.
  await gotoRoute(loggedInPage, `/flights/${flightId}`);
  const reloadedInput = loggedInPage.locator('input#Comment');
  await expect(reloadedInput).toBeVisible({ timeout: 10_000 });
  await expect(reloadedInput).toHaveValue(editedComment);
  await screenshot(loggedInPage, '05-flights-edit-01');
});

async function readFlightComment(page: import('@playwright/test').Page, token: string, flightId: string): Promise<string> {
  const res = await page.request.get(`${API_BASE}/api/v1/flights/${flightId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(res.ok(), `GET /api/v1/flights/${flightId} -> ${res.status()}`).toBeTruthy();
  const body = await res.json();
  return body?.GliderFlightDetailsData?.FlightComment ?? '';
}

/** Round-trip the current FlightDetails through GET, mutate FlightComment, return for PUT. */
async function loadFlightForUpdate(page: import('@playwright/test').Page, token: string, flightId: string, newComment: string): Promise<unknown> {
  const res = await page.request.get(`${API_BASE}/api/v1/flights/${flightId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const body = await res.json() as { GliderFlightDetailsData?: { FlightComment?: string } };
  if (body.GliderFlightDetailsData) body.GliderFlightDetailsData.FlightComment = newComment;
  return body;
}
