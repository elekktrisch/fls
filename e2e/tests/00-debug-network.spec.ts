// Throwaway debug spec to surface the actual API failure behind
// "Failed to load masterdata" on /flights/<id>.
import { test, expect, gotoRoute } from '../fixtures';

test('debug: capture failing requests on flight-edit', async ({ freshLoggedInPage: loggedInPage }) => {
  const page = loggedInPage;
  const failed: { url: string; status: number; body: string }[] = [];
  page.on('response', async (r) => {
    if (r.status() >= 400 && r.url().includes('/api/v1/')) {
      let body = '';
      try { body = (await r.text()).slice(0, 200); } catch {}
      failed.push({ url: r.url(), status: r.status(), body });
    }
  });
  page.on('requestfailed', (req) => {
    failed.push({ url: req.url(), status: 0, body: req.failure()?.errorText ?? '' });
  });
  await gotoRoute(page, '/flights/F1500005-0000-0000-0000-000000000001');
  await page.waitForTimeout(5000);
  console.log('FAILED REQUESTS:', JSON.stringify(failed, null, 2));
  expect(failed.length, `failed requests: ${JSON.stringify(failed)}`).toBe(0);
});
