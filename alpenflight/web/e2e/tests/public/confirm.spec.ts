import { type Request } from '@playwright/test';
import { expect, test } from '../_helpers/console-guard';

interface AuthorizeArgs {
  configId?: string;
  params?: { customParams?: Record<string, string> };
}

declare global {
  interface Window {
    __lastAuthorizeArgs?: AuthorizeArgs;
  }
}

const MOBILE_PORTRAIT_VIEWPORT = { width: 360, height: 640 } as const;
const MINIMUM_TOUCH_TARGET_EDGE_PX = 44;

test.describe('confirm — SPA-side wiring (mock-auth)', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      delete (window as Window).__lastAuthorizeArgs;
    });
  });

  test('/confirm renders the verified outcome and the sign-in action', async ({ page }) => {
    await page.goto('/confirm');

    await expect(page.getByTestId('confirm-page')).toBeVisible();
    await expect(page.getByTestId('confirm-outcome-verified')).toBeVisible();
    await expect(page.getByTestId('confirm-verified-headline')).toBeVisible();
    await expect(page.getByTestId('confirm-sign-in')).toBeVisible();

    await page.screenshot({ path: 'screenshots/public/03-confirm-verified.png', fullPage: true });
  });

  test('/confirm keeps the URL, shows no nav-bar and writes nothing to AlpenFlight', async ({
    page,
  }) => {
    const writes: string[] = [];
    page.on('request', (req: Request) => {
      const url = new URL(req.url());
      const isWrite = req.method() !== 'GET' && req.method() !== 'HEAD';
      if (isWrite && url.pathname.startsWith('/api/'))
        writes.push(`${req.method()} ${url.pathname}`);
    });

    await page.goto('/confirm');
    await page.waitForLoadState('networkidle');

    await expect(page.getByTestId('confirm-page')).toBeVisible();
    expect(new URL(page.url()).pathname).toBe('/confirm');
    expect(new URL(page.url()).pathname).not.toContain('/realms/');
    expect(
      writes,
      'the confirmation page adds no unauthenticated write surface — Keycloak owns credentials',
    ).toEqual([]);
    await expect(page.locator('af-nav-bar')).toHaveCount(0);
  });

  test('the confirm CTA hits >= 44 x 44 CSS px and the page fits 360 x 640 portrait', async ({
    page,
  }) => {
    await page.setViewportSize(MOBILE_PORTRAIT_VIEWPORT);
    await page.goto('/confirm');
    await expect(page.getByTestId('confirm-page')).toBeVisible();

    const fitsWithoutHorizontalScroll = await page.evaluate(
      () => document.documentElement.scrollWidth <= document.documentElement.clientWidth,
    );
    expect(fitsWithoutHorizontalScroll, '/confirm scrolls sideways').toBe(true);

    const signInCallToAction = page.getByTestId('confirm-sign-in');
    await expect(signInCallToAction).toBeVisible();
    const box = await signInCallToAction.boundingBox();
    expect(box, 'confirm-sign-in has no bounding box').not.toBeNull();
    expect(box!.height).toBeGreaterThanOrEqual(MINIMUM_TOUCH_TARGET_EDGE_PX);
    expect(box!.width).toBeGreaterThanOrEqual(MINIMUM_TOUCH_TARGET_EDGE_PX);
  });

  test('the sign-in action carries the picked locale to Keycloak', async ({ page }) => {
    await page.goto('/confirm?lang=fr');
    await page.getByTestId('confirm-sign-in').click();

    const args = await page.evaluate(() => window.__lastAuthorizeArgs);
    expect(args?.params?.customParams).toMatchObject({ ui_locales: 'fr' });
    expect(JSON.stringify(args ?? {})).not.toContain('reset-credentials');
  });
});
