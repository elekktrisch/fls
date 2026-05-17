import { expect, test } from '@playwright/test';

// Smoke for S-004: the generated TS client actually reaches the backend.
// The dev `ng serve` proxies `/api/v1/*` to http://localhost:8080 (see
// proxy.conf.json), but this CI spec mocks the response so we don't depend
// on a live AlpenFlight server. Real-backend e2e lands with S-110.
test('hello: generated client fetches greeting + renders into the page', async ({ page }) => {
  await page.route('**/api/v1/hello', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ message: 'Hello AlpenFlight', timestamp: '2026-01-01T00:00:00Z' }),
    }),
  );

  await page.goto('/hello');

  const heading = page.locator('h1');
  await expect(heading).toBeVisible();
  await expect(heading).toHaveText(/Hello AlpenFlight/);
  await expect(page.locator('p')).toContainText(/2026/);
});
