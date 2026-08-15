import { test, expect } from '../_helpers/console-guard';


test('google CTA on /signup redirects to accounts.google.com', async ({ page }) => {
  await page.goto('/signup');
  await expect(page.getByTestId('signup-google')).toBeVisible();

  const googleRequest = page.waitForRequest(
    (req) => {
      try {
        return new URL(req.url()).hostname.endsWith('accounts.google.com');
      } catch {
        return false;
      }
    },
    { timeout: 15_000 },
  );

  await page.getByTestId('signup-google').click();
  const matched = await googleRequest;

  const url = new URL(matched.url());
  expect(url.hostname).toMatch(/(^|\.)accounts\.google\.com$/);

  await page.goBack().catch(() => {
  });
});
