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
const THE_LINK_KEYCLOAK_SENDS_WHEN_THE_ACTION_FAILED = '/confirm?outcome=expired';

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
    await expect(page.getByTestId('confirm-outcome-expired')).toHaveCount(0);

    await page.screenshot({ path: 'screenshots/public/03-confirm-verified.png', fullPage: true });
  });

  test('/confirm renders the expired outcome and offers only the reset action', async ({
    page,
  }) => {
    await page.goto(THE_LINK_KEYCLOAK_SENDS_WHEN_THE_ACTION_FAILED);

    await expect(page.getByTestId('confirm-outcome-expired')).toBeVisible();
    await expect(page.getByTestId('confirm-expired-headline')).toBeVisible();
    await expect(page.getByTestId('confirm-outcome-verified')).toHaveCount(0);
    await expect(page.getByTestId('confirm-sign-in')).toHaveCount(0);

    await page.screenshot({ path: 'screenshots/public/04-confirm-expired.png', fullPage: true });
  });

  test('the expired outcome returns the member to /lostpassword', async ({ page }) => {
    await page.goto(THE_LINK_KEYCLOAK_SENDS_WHEN_THE_ACTION_FAILED);
    await page.getByTestId('confirm-restart-recovery').click();

    await page.waitForURL((url) => url.pathname === '/lostpassword');
    await expect(page.getByTestId('lostpassword-page')).toBeVisible();
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

  test('every confirm CTA hits >= 44 x 44 CSS px and each outcome fits 360 x 640 portrait', async ({
    page,
  }) => {
    await page.setViewportSize(MOBILE_PORTRAIT_VIEWPORT);

    for (const outcome of [
      { path: '/confirm', callToActionTestId: 'confirm-sign-in' },
      {
        path: THE_LINK_KEYCLOAK_SENDS_WHEN_THE_ACTION_FAILED,
        callToActionTestId: 'confirm-restart-recovery',
      },
    ]) {
      await page.goto(outcome.path);
      await expect(page.getByTestId('confirm-page')).toBeVisible();

      const fitsWithoutHorizontalScroll = await page.evaluate(
        () => document.documentElement.scrollWidth <= document.documentElement.clientWidth,
      );
      expect(fitsWithoutHorizontalScroll, `${outcome.path} scrolls sideways`).toBe(true);

      const cta = page.getByTestId(outcome.callToActionTestId);
      await expect(cta).toBeVisible();
      const box = await cta.boundingBox();
      expect(box, `${outcome.callToActionTestId} has no bounding box`).not.toBeNull();
      expect(box!.height).toBeGreaterThanOrEqual(MINIMUM_TOUCH_TARGET_EDGE_PX);
      expect(box!.width).toBeGreaterThanOrEqual(MINIMUM_TOUCH_TARGET_EDGE_PX);
    }
  });

  test('the sign-in action carries the picked locale to Keycloak', async ({ page }) => {
    await page.goto('/confirm?lang=fr');
    await page.getByTestId('confirm-sign-in').click();

    const args = await page.evaluate(() => window.__lastAuthorizeArgs);
    expect(args?.params?.customParams).toMatchObject({ ui_locales: 'fr' });
    expect(JSON.stringify(args ?? {})).not.toContain('reset-credentials');
  });
});
