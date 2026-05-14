import { test, expect } from '@playwright/test';

test('landing page renders and screenshot captures the login view', async ({ page }) => {
  await page.goto('/', { waitUntil: 'domcontentloaded' });
  await expect(page).toHaveTitle(/FLS|Flight/i);
  await page.screenshot({ path: 'screenshots/landing.png', fullPage: true });
});
