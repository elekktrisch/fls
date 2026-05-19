import { test, expect } from '@playwright/test';

test.describe('landing — i18n + locale switch', () => {
  test('renders the German tagline by default and html[lang=de]', async ({ page }) => {
    await page.goto('/');

    await expect(page).toHaveTitle(/AlpenFlight/i);
    await expect(page.locator('html')).toHaveAttribute('lang', 'de');
    await expect(page.locator('p').first()).toContainText(/Flugbuch/);
  });

  test('switches locale to English without reloading the page', async ({ page }) => {
    await page.goto('/');

    let navigations = 0;
    page.on('framenavigated', () => navigations++);
    const startUrl = page.url();

    await page.getByRole('button', { name: 'EN' }).click();

    await expect(page.locator('html')).toHaveAttribute('lang', 'en');
    await expect(page.locator('p').first()).toContainText(/Flight logging/);
    expect(page.url()).toBe(startUrl);
    expect(navigations).toBe(0);
  });

  test('cycles through all four locales (de → fr → it → en → de)', async ({ page }) => {
    await page.goto('/');

    const cases = [
      { btn: 'FR', lang: 'fr', match: /Carnet de vol/ },
      { btn: 'IT', lang: 'it', match: /Diario di volo/ },
      { btn: 'EN', lang: 'en', match: /Flight logging/ },
      { btn: 'DE', lang: 'de', match: /Flugbuch/ },
    ];
    for (const c of cases) {
      await page.getByRole('button', { name: c.btn }).click();
      await expect(page.locator('html')).toHaveAttribute('lang', c.lang);
      await expect(page.locator('p').first()).toContainText(c.match);
    }
  });

  test('AC-DIR-1: locale picker is reachable at a mobile viewport', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/');

    for (const code of ['DE', 'FR', 'IT', 'EN']) {
      const btn = page.getByRole('button', { name: code });
      await expect(btn).toBeVisible();
      const box = await btn.boundingBox();
      expect(box).not.toBeNull();
      expect(box!.width).toBeGreaterThan(0);
      expect(box!.height).toBeGreaterThan(0);
    }

    await page.getByRole('button', { name: 'FR' }).click();
    await expect(page.locator('html')).toHaveAttribute('lang', 'fr');
  });

  test('C15: no /api/v1/translations and no /i18n/* fetches — translations ride the JS bundle', async ({
    page,
  }) => {
    await page.route('**/api/v1/translations**', (route) => route.abort());
    await page.route('**/i18n/**', (route) => route.abort());

    await page.goto('/');

    await expect(page.locator('p').first()).toContainText(/Flugbuch/);
    await page.getByRole('button', { name: 'FR' }).click();
    await expect(page.locator('p').first()).toContainText(/Carnet de vol/);
  });
});
