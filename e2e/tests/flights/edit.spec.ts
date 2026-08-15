import { expect, gotoRoute, screenshot, test } from '../../fixtures';
import { testId } from '../../test-id';
import { API_BASE, authHeaders, ensureGliderFlight, getBearerToken } from '../../test-data';

test('flights-edit: round-trip FlightComment via form submit', async ({ loggedInPage }, testInfo) => {
  const id = testId(testInfo);
  const initialComment = `${id.name} initial`;
  const editedComment = `${id.name} edited`;

  const token = await getBearerToken(loggedInPage);

  const { flightId } = await ensureGliderFlight(loggedInPage.request, token, {
    comment: initialComment,
  });
  await loggedInPage.request.put(`${API_BASE}/api/v1/flights/${flightId}`, {
    headers: authHeaders(token),
    data: await flightDetailsWithNewComment(loggedInPage, token, flightId, initialComment),
  }).then(r => r.ok()
    ? null
    : Promise.reject(new Error(`PUT /flights/${flightId} init -> ${r.status()}: ${r.text()}`)));

  await gotoRoute(loggedInPage, `/flights/${flightId}`);

  const commentInput = loggedInPage.locator('input#Comment');
  await expect(commentInput).toBeVisible({ timeout: 10_000 });

  await expect(commentInput).toHaveValue(initialComment, { timeout: 5_000 });

  await commentInput.fill(editedComment);
  await expect(commentInput).toHaveValue(editedComment);

  const saveButton = loggedInPage
    .locator('form[name="flightDetailsForm"] button[type="submit"]')
    .filter({ hasText: /^\s*(Save|Speichern)\s*$/i })
    .first();
  await expect(saveButton).toBeEnabled();
  await saveButton.click();
  await loggedInPage.waitForURL(/#\/flights$/, { timeout: 15_000 });

  const apiAfter = await readFlightComment(loggedInPage, token, flightId);
  expect(apiAfter, 'API readback should reflect the edited FlightComment').toBe(editedComment);

  await gotoRoute(loggedInPage, `/flights/${flightId}`);
  const reloadedInput = loggedInPage.locator('input#Comment');
  await expect(reloadedInput).toBeVisible({ timeout: 10_000 });
  await expect(reloadedInput).toHaveValue(editedComment);
  await screenshot(loggedInPage, 'edit-01');
});

async function readFlightComment(page: import('@playwright/test').Page, token: string, flightId: string): Promise<string> {
  const res = await page.request.get(`${API_BASE}/api/v1/flights/${flightId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(res.ok(), `GET /api/v1/flights/${flightId} -> ${res.status()}`).toBeTruthy();
  const body = await res.json();
  return body?.GliderFlightDetailsData?.FlightComment ?? '';
}

async function flightDetailsWithNewComment(page: import('@playwright/test').Page, token: string, flightId: string, newComment: string): Promise<unknown> {
  const res = await page.request.get(`${API_BASE}/api/v1/flights/${flightId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const body = await res.json() as { GliderFlightDetailsData?: { FlightComment?: string } };
  if (body.GliderFlightDetailsData) body.GliderFlightDetailsData.FlightComment = newComment;
  return body;
}
