import { test, expect, screenshot } from '../../fixtures';

test('landing page renders and screenshot captures the login view', async ({ page }) => {
  await page.goto('/', { waitUntil: 'domcontentloaded' });
  await expect(page).toHaveTitle(/FLS|Flight/i);
  await screenshot(page, 'landing');
});
