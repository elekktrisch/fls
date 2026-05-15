import { test, expect } from '@playwright/test';

test('landing page renders + carries tailwind class + computed style', async ({ page }) => {
  await page.goto('/');

  await expect(page).toHaveTitle(/FLS/i);

  const heading = page.locator('h1');
  await expect(heading).toBeVisible();
  await expect(heading).toHaveText(/Hello FLS/i);
  await expect(heading).toHaveClass(/text-blue-600/);

  // Tailwind v4 emits the OKLCH form of blue-600; modern browsers report
  // the same OKLCH triple in getComputedStyle (no sRGB conversion). This
  // asserts Tailwind processed the entry CSS — anything but the default
  // black would be sufficient, but pinning the exact value catches drift.
  await expect(heading).toHaveCSS('color', 'oklch(0.546 0.245 262.881)');
});
