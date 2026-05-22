import { test, expect } from '@playwright/test';

// `?lang=` query param pins the cold-start locale so tests don't depend on
// the test browser's Accept-Language (Chromium defaults to en-US, which
// the resolver correctly maps to `en`). See `core/i18n/lang-resolver.ts`.

test.describe('landing — i18n + locale switch', () => {
  test('renders the German tagline when ?lang=de and html[lang=de]', async ({ page }) => {
    await page.goto('/?lang=de');

    await expect(page).toHaveTitle(/AlpenFlight/i);
    await expect(page.locator('html')).toHaveAttribute('lang', 'de');
    await expect(page.getByTestId('landing-tagline')).toContainText(/Flugbuch/);
  });

  test('switches locale to English without reloading the page', async ({ page }) => {
    await page.goto('/?lang=de');
    await expect(page.locator('html')).toHaveAttribute('lang', 'de');
    await expect(page.getByTestId('landing-tagline')).toContainText(/Flugbuch/);

    // Stamp a witness on the live document; if the locale switch caused
    // a full reload, the stamped attribute would disappear when the new
    // document loads. SPA-internal route/state changes preserve it.
    await page.evaluate(() => document.documentElement.setAttribute('data-reload-witness', 'kept'));
    const startUrl = page.url();

    await page.getByTestId('af-lang-en').click();

    await expect(page.locator('html')).toHaveAttribute('lang', 'en');
    await expect(page.getByTestId('landing-tagline')).toContainText(/logbook/);
    expect(page.url()).toBe(startUrl);
    await expect(page.locator('html')).toHaveAttribute('data-reload-witness', 'kept');
  });

  test('cycles through all four locales (de → fr → it → en → de)', async ({ page }) => {
    await page.goto('/?lang=de');
    await expect(page.locator('html')).toHaveAttribute('lang', 'de');

    const cases = [
      { testId: 'af-lang-fr', lang: 'fr', match: /carnet de vol/ },
      { testId: 'af-lang-it', lang: 'it', match: /diario di volo/ },
      { testId: 'af-lang-en', lang: 'en', match: /logbook/ },
      { testId: 'af-lang-de', lang: 'de', match: /Flugbuch/ },
    ];
    for (const c of cases) {
      await page.getByTestId(c.testId).click();
      await expect(page.locator('html')).toHaveAttribute('lang', c.lang);
      await expect(page.getByTestId('landing-tagline')).toContainText(c.match);
    }
  });

  test('AC-DIR-1: locale picker is reachable at a mobile viewport', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/?lang=de');

    for (const id of ['af-lang-de', 'af-lang-fr', 'af-lang-it', 'af-lang-en']) {
      const btn = page.getByTestId(id);
      await expect(btn).toBeVisible();
      const box = await btn.boundingBox();
      expect(box).not.toBeNull();
      expect(box!.width).toBeGreaterThan(0);
      expect(box!.height).toBeGreaterThan(0);
    }

    await page.getByTestId('af-lang-fr').click();
    await expect(page.locator('html')).toHaveAttribute('lang', 'fr');
  });

  test('C15: no /api/v1/translations and no /i18n/* fetches — translations ride the JS bundle', async ({
    page,
  }) => {
    await page.route('**/api/v1/translations**', (route) => route.abort());
    await page.route('**/i18n/**', (route) => route.abort());

    await page.goto('/?lang=de');

    await expect(page.getByTestId('landing-tagline')).toContainText(/Flugbuch/);
    await page.getByTestId('af-lang-fr').click();
    await expect(page.getByTestId('landing-tagline')).toContainText(/carnet de vol/);
  });

  test('AC-DIR-2: every landing CTA hits >= 44 x 44 CSS px at <md', async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 640 });
    await page.goto('/?lang=de');

    for (const testId of [
      'landing-topbar-sign-in',
      'landing-sign-in',
      'landing-request-access',
      'landing-try-demo',
    ]) {
      const btn = page.getByTestId(testId);
      await expect(btn).toBeVisible();
      const box = await btn.boundingBox();
      expect(box).not.toBeNull();
      expect(box!.height).toBeGreaterThanOrEqual(44);
      expect(box!.width).toBeGreaterThanOrEqual(44);
    }
  });

  test('AC-DIR-3: splash slot renders with object-fit cover', async ({ page }) => {
    await page.goto('/?lang=de');
    const splash = page.getByTestId('landing-splash');
    await expect(splash).toBeVisible();
    const objectFit = await splash.evaluate((el) =>
      window.getComputedStyle(el).getPropertyValue('object-fit'),
    );
    expect(objectFit).toBe('cover');
  });

  test('AC-DIR-5: landing has exactly one af-lang-picker (the inline one)', async ({ page }) => {
    await page.goto('/?lang=de');
    await expect(page.locator('af-lang-picker')).toHaveCount(1);
  });
});
