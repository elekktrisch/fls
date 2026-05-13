const { test, expect } = require('@playwright/test');

test('landing page renders and screenshot captures the login view', async ({ page }) => {
  await page.goto('/', { waitUntil: 'networkidle' });
  await expect(page).toHaveTitle(/FLS|Flight/i);
  await page.screenshot({ path: 'screenshots/landing.png', fullPage: true });
});
