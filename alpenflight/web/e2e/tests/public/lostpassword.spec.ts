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
const CALL_TO_ACTION_TEST_IDS = ['lostpassword-start', 'lostpassword-sign-in-link'] as const;

test.describe('lostpassword — SPA-side wiring (mock-auth)', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      delete (window as Window).__lastAuthorizeArgs;
    });
  });

  test('/lostpassword renders the page, the recovery steps and the start action', async ({
    page,
  }) => {
    await page.goto('/lostpassword');

    await expect(page.getByTestId('lostpassword-page')).toBeVisible();
    await expect(page.getByTestId('lostpassword-headline')).toBeVisible();
    await expect(page.getByTestId('lostpassword-caution')).toBeVisible();
    await expect(page.getByTestId('lostpassword-steps').locator('li')).toHaveCount(4);
    await expect(page.getByTestId('lostpassword-start')).toBeVisible();
    await expect(page.getByTestId('lostpassword-sign-in-link')).toBeVisible();

    await page.screenshot({ path: 'screenshots/public/02-lostpassword.png', fullPage: true });
  });

  test('/lostpassword keeps the URL, shows no nav-bar and writes nothing to AlpenFlight', async ({
    page,
  }) => {
    const writes: string[] = [];
    page.on('request', (req: Request) => {
      const url = new URL(req.url());
      const isWrite = req.method() !== 'GET' && req.method() !== 'HEAD';
      if (isWrite && url.pathname.startsWith('/api/'))
        writes.push(`${req.method()} ${url.pathname}`);
    });

    await page.goto('/lostpassword');
    await page.waitForLoadState('networkidle');

    await expect(page.getByTestId('lostpassword-page')).toBeVisible();
    expect(new URL(page.url()).pathname).toBe('/lostpassword');
    expect(new URL(page.url()).pathname).not.toContain('/realms/');
    expect(
      writes,
      'the recovery page adds no unauthenticated write surface — Keycloak owns credentials',
    ).toEqual([]);
    await expect(page.locator('af-nav-bar')).toHaveCount(0);
  });

  test('every lostpassword CTA hits >= 44 x 44 CSS px and the page fits 360 x 640 portrait', async ({
    page,
  }) => {
    await page.setViewportSize(MOBILE_PORTRAIT_VIEWPORT);
    await page.goto('/lostpassword');
    await expect(page.getByTestId('lostpassword-page')).toBeVisible();

    const fitsWithoutHorizontalScroll = await page.evaluate(
      () => document.documentElement.scrollWidth <= document.documentElement.clientWidth,
    );
    expect(fitsWithoutHorizontalScroll).toBe(true);

    for (const testId of CALL_TO_ACTION_TEST_IDS) {
      const cta = page.getByTestId(testId);
      await expect(cta).toBeVisible();
      const box = await cta.boundingBox();
      expect(box, `${testId} has no bounding box`).not.toBeNull();
      expect(box!.height).toBeGreaterThanOrEqual(MINIMUM_TOUCH_TARGET_EDGE_PX);
      expect(box!.width).toBeGreaterThanOrEqual(MINIMUM_TOUCH_TARGET_EDGE_PX);
    }
  });

  test('the start action authorizes to the Keycloak login page, with no reset-credentials deep link', async ({
    page,
  }) => {
    await page.goto('/lostpassword?lang=de');
    await page.getByTestId('lostpassword-start').click();

    const args = await page.evaluate(() => window.__lastAuthorizeArgs);
    expect(args?.params?.customParams).toMatchObject({ ui_locales: 'de' });
    expect(args?.params?.customParams).not.toHaveProperty('prompt');
    expect(args?.params?.customParams).not.toHaveProperty('kc_idp_hint');
    expect(JSON.stringify(args ?? {})).not.toContain('reset-credentials');
  });

  test('the start action carries the picked locale, so Keycloak renders the reset chrome translated', async ({
    page,
  }) => {
    await page.goto('/lostpassword?lang=fr');
    await page.getByTestId('lostpassword-start').click();

    const args = await page.evaluate(() => window.__lastAuthorizeArgs);
    expect(args?.params?.customParams).toMatchObject({ ui_locales: 'fr' });
  });

  test('the sign-in action also reaches Keycloak, so a member who remembers the password is not stranded', async ({
    page,
  }) => {
    await page.goto('/lostpassword?lang=it');
    await page.getByTestId('lostpassword-sign-in-link').click();

    const args = await page.evaluate(() => window.__lastAuthorizeArgs);
    expect(args?.params?.customParams).toMatchObject({ ui_locales: 'it' });
  });
});
