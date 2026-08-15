import { test, expect } from '../_helpers/console-guard';


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

    for (const testId of ['landing-topbar-sign-in', 'landing-cta-migrate', 'landing-cta-demo']) {
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

test.describe('landing — S-133 CTA routing + funnel telemetry', () => {
  const CTA_MIGRATE = 'landing-cta-migrate';
  const CTA_DEMO = 'landing-cta-demo';
  const CTA_REQUEST_ACCESS = 'landing-cta-request-access';

  test('migrate CTA reaches /signup?intent=migrate and resolves the migrate side-path', async ({
    page,
  }) => {
    await page.goto('/');
    await page.getByTestId('landing').waitFor({ state: 'visible' });

    await page.getByTestId(CTA_MIGRATE).click();
    await expect(page).toHaveURL(/\/signup\?.*intent=migrate/);

    await page.getByTestId('signup-page').waitFor({ state: 'visible' });
    await page.getByTestId('signup-local').click();
    const stamp = await page.evaluate(() =>
      sessionStorage.getItem('alpenflight.post-login-redirect'),
    );
    expect(stamp).toBe('/migrate/start');
  });

  test('demo CTA reaches /demo and the stub route resolves (no redirect)', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('landing').waitFor({ state: 'visible' });

    await page.getByTestId(CTA_DEMO).click();
    await expect(page).toHaveURL(/\/demo(\?|$|\/)/);
    await expect(page.getByTestId('demo-stub')).toBeVisible();
  });

  test('the tertiary Request access CTA points at the /signup join default', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('landing').waitFor({ state: 'visible' });

    const requestAccess = page.getByTestId(CTA_REQUEST_ACCESS);
    await expect(requestAccess).toBeVisible();
    await expect(requestAccess).toHaveAttribute('href', /\/signup(\?|$)/);
    await expect(requestAccess).not.toHaveAttribute('href', /intent=migrate/);
  });

  for (const { cta, ctaId } of [
    { cta: CTA_MIGRATE, ctaId: 'migrate' },
    { cta: CTA_DEMO, ctaId: 'demo' },
  ] as const) {
    test(`${ctaId} CTA emits landing.cta_click with cta_id=${ctaId}`, async ({ page }) => {
      const funnelEvents: string[] = [];
      page.on('console', (msg) => {
        if (msg.type() === 'info' && msg.text().startsWith('[funnel]')) {
          funnelEvents.push(msg.text());
        }
      });

      await page.goto('/');
      await page.getByTestId('landing').waitFor({ state: 'visible' });
      await page.getByTestId(cta).click();

      await expect
        .poll(
          () =>
            funnelEvents.find(
              (e) =>
                e.includes('"event_id":"landing.cta_click"') && e.includes(`"cta_id":"${ctaId}"`),
            ),
          { timeout: 5_000 },
        )
        .toBeTruthy();
      const emit = funnelEvents.find((e) => e.includes('"event_id":"landing.cta_click"'))!;
      const payload = JSON.parse(emit.replace(/^\[funnel\]\s*/, '')) as {
        event_id: string;
        timestamp: string;
        properties: { cta_id: string };
      };
      expect(payload.event_id).toBe('landing.cta_click');
      expect(payload.properties.cta_id).toBe(ctaId);
      expect(typeof payload.timestamp).toBe('string');
    });
  }
});

test.describe('landing — AC-DIR breakpoints + touch targets + wordmark', () => {
  const BREAKPOINTS = [
    { width: 360, direction: 'column', sideBySide: false },
    { width: 768, direction: 'row', sideBySide: false },
    { width: 1024, direction: 'row', sideBySide: true },
    { width: 1440, direction: 'row', sideBySide: true },
  ] as const;

  for (const { width, direction, sideBySide } of BREAKPOINTS) {
    test(`CTA row flexes ${direction}${sideBySide ? ' side-by-side' : ''} at ${width}px`, async ({
      page,
    }) => {
      await page.setViewportSize({ width, height: 800 });
      await page.goto('/');
      await page.getByTestId('landing').waitFor({ state: 'visible' });

      const migrate = page.getByTestId('landing-cta-migrate');
      const demo = page.getByTestId('landing-cta-demo');
      const resolvedDirection = await migrate.evaluate(
        (el) => window.getComputedStyle(el.parentElement as Element).flexDirection,
      );
      expect(resolvedDirection).toBe(direction);

      const migrateBox = await migrate.boundingBox();
      const demoBox = await demo.boundingBox();
      expect(migrateBox).not.toBeNull();
      expect(demoBox).not.toBeNull();

      if (direction === 'column') {
        expect(demoBox!.y).toBeGreaterThanOrEqual(migrateBox!.y + migrateBox!.height - 1);
      }
      if (sideBySide) {
        const verticalOverlap =
          Math.min(migrateBox!.y + migrateBox!.height, demoBox!.y + demoBox!.height) -
          Math.max(migrateBox!.y, demoBox!.y);
        expect(verticalOverlap).toBeGreaterThan(0);
        expect(migrateBox!.x).toBeLessThan(demoBox!.x);
      }
    });
  }

  test('every CTA button hits >= 44 x 44 CSS px at <md (360px)', async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 640 });
    await page.goto('/');
    await page.getByTestId('landing').waitFor({ state: 'visible' });

    for (const testId of ['landing-topbar-sign-in', 'landing-cta-migrate', 'landing-cta-demo']) {
      const box = await page.getByTestId(testId).boundingBox();
      expect(box, testId).not.toBeNull();
      expect(box!.height, testId).toBeGreaterThanOrEqual(44);
      expect(box!.width, testId).toBeGreaterThanOrEqual(44);
    }
  });

  test('wordmark: full at >=md, compact at <md', async ({ page }) => {
    await page.setViewportSize({ width: 1024, height: 800 });
    await page.goto('/');
    await page.getByTestId('landing-topbar').waitFor({ state: 'visible' });
    await expect(page.getByTestId('af-wordmark-full')).toBeVisible();
    await expect(page.getByTestId('af-wordmark-compact')).toBeHidden();

    await page.setViewportSize({ width: 360, height: 640 });
    await expect(page.getByTestId('af-wordmark-compact')).toBeVisible();
    await expect(page.getByTestId('af-wordmark-full')).toBeHidden();
  });
});

test.describe('landing — footer + zero-console-error', () => {
  test('footer renders copyright + Status / Documentation / Imprint', async ({ page }) => {
    await page.goto('/?lang=en');
    const footer = page.getByRole('contentinfo').or(page.locator('footer'));
    await expect(footer).toBeVisible();
    await expect(footer).toContainText(String(new Date().getFullYear()));
    await expect(footer).toContainText(/Status/i);
    await expect(footer).toContainText(/Documentation/i);
    await expect(footer).toContainText(/Imprint/i);
  });

  test('landing load emits zero console errors', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('landing')).toBeVisible();
    await expect(page.getByTestId('landing-topbar')).toBeVisible();
  });
});
