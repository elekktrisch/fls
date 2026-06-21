import { expect, test } from '../_helpers/console-guard';

/**
 * S-134 self-service signup. The e2e harness here is mock-auth: the SPA boots
 * under `app.config.mock.ts` with no live Keycloak. The CTAs call into a
 * stubbed `OidcSecurityService` that records its last `authorize` args on
 * `window.__lastAuthorizeArgs` (see app.config.mock.ts) — that's the seam
 * we assert on. The real-Keycloak end-to-end is a separate harness (S-021
 * follow-up; tracked there).
 *
 * Coverage:
 *   - /signup renders + CTAs visible.
 *   - "Sign up" → authorize() with { prompt: 'create', ui_locales }.
 *   - "Continue with Google" → authorize() with { kc_idp_hint: 'google', ui_locales }.
 *   - intent=migrate stamps /migrate/start in post-login-redirect.
 *   - intent=demo (per S-134 grill) is silently coerced to /migrate/start.
 *   - /migrate/start with signup-pending stamp emits PII-free signup.completed.
 *   - /migrate/start with NO stamp does not emit signup.completed.
 */

interface AuthorizeArgs {
  configId?: string;
  params?: { customParams?: Record<string, string> };
}

declare global {
  interface Window {
    __lastAuthorizeArgs?: AuthorizeArgs;
  }
}

test.describe('signup — SPA-side wiring (mock-auth)', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      // Mock OIDC writes `__lastAuthorizeArgs` on call; clear before each spec.
      delete (window as Window).__lastAuthorizeArgs;
    });
  });

  test('/signup renders both CTAs', async ({ page }) => {
    await page.goto('/signup');
    await expect(page.getByTestId('signup-page')).toBeVisible();
    await expect(page.getByTestId('signup-headline')).toBeVisible();
    await expect(page.getByTestId('signup-local')).toBeVisible();
    await expect(page.getByTestId('signup-google')).toBeVisible();

    await page.screenshot({ path: 'screenshots/public/01-signup.png', fullPage: true });
  });

  // Vision §2 NFR (touch targets at <md, retained on the gloves rationale post
  // amendment 2026-05-20d). Same shape as landing.spec.ts AC-DIR-2.
  test('every signup CTA hits >= 44 x 44 CSS px at <md', async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 640 });
    await page.goto('/signup');

    for (const testId of ['signup-local', 'signup-google', 'signup-sign-in-link']) {
      const btn = page.getByTestId(testId);
      await expect(btn).toBeVisible();
      const box = await btn.boundingBox();
      expect(box).not.toBeNull();
      expect(box!.height).toBeGreaterThanOrEqual(44);
      expect(box!.width).toBeGreaterThanOrEqual(44);
    }
  });

  test('clicking "Sign up" calls authorize with prompt=create + ui_locales', async ({ page }) => {
    await page.goto('/signup?lang=de');
    await page.getByTestId('signup-local').click();

    const args = await page.evaluate(() => window.__lastAuthorizeArgs);
    expect(args?.params?.customParams).toMatchObject({
      prompt: 'create',
      ui_locales: 'de',
    });
    // No kc_idp_hint on the local CTA — would silently route to Google.
    expect(args?.params?.customParams).not.toHaveProperty('kc_idp_hint');
  });

  test('clicking "Continue with Google" calls authorize with kc_idp_hint=google', async ({
    page,
  }) => {
    await page.goto('/signup?lang=de');
    await page.getByTestId('signup-google').click();

    const args = await page.evaluate(() => window.__lastAuthorizeArgs);
    expect(args?.params?.customParams).toMatchObject({
      kc_idp_hint: 'google',
      ui_locales: 'de',
    });
  });

  test('intent=migrate stamps /migrate/start as the post-login redirect', async ({ page }) => {
    await page.goto('/signup?intent=migrate&lang=de');
    await page.getByTestId('signup-local').click();

    const stamp = await page.evaluate(() =>
      sessionStorage.getItem('alpenflight.post-login-redirect'),
    );
    expect(stamp).toBe('/migrate/start');
  });

  test('intent=demo is silently coerced to /migrate/start (per S-134 grill)', async ({ page }) => {
    await page.goto('/signup?intent=demo&lang=de');
    await page.getByTestId('signup-local').click();

    const stamp = await page.evaluate(() =>
      sessionStorage.getItem('alpenflight.post-login-redirect'),
    );
    expect(stamp).toBe('/migrate/start');
  });

  test('signup-local writes a `local` signup-pending stamp', async ({ page }) => {
    await page.goto('/signup?intent=migrate&lang=de');
    await page.getByTestId('signup-local').click();

    const stamp = await page.evaluate(() => sessionStorage.getItem('alpenflight.signup-pending'));
    expect(stamp).not.toBeNull();
    expect(JSON.parse(stamp!)).toMatchObject({ idp: 'local' });
  });

  test('signup-google writes a `google` signup-pending stamp', async ({ page }) => {
    await page.goto('/signup?intent=migrate&lang=de');
    await page.getByTestId('signup-google').click();

    const stamp = await page.evaluate(() => sessionStorage.getItem('alpenflight.signup-pending'));
    expect(stamp).not.toBeNull();
    expect(JSON.parse(stamp!)).toMatchObject({ idp: 'google' });
  });
});

test.describe('signup — post-signup landing emits funnel event', () => {
  test('/migrate/start with signup-pending stamp emits PII-free signup.completed', async ({
    page,
  }) => {
    const funnelEvents: string[] = [];
    page.on('console', (msg) => {
      if (msg.type() === 'info' && msg.text().startsWith('[funnel]')) {
        funnelEvents.push(msg.text());
      }
    });

    await page.addInitScript(() => {
      sessionStorage.setItem(
        'alpenflight.signup-pending',
        JSON.stringify({ idp: 'google', startedAt: new Date().toISOString() }),
      );
    });

    await page.goto('/migrate/start');
    await expect(page.getByTestId('migrate-handshake')).toBeVisible();

    // Settle: the OnInit emit is synchronous after the component mounts.
    await expect.poll(() => funnelEvents.length, { timeout: 5_000 }).toBeGreaterThan(0);
    const emit = funnelEvents[0];
    expect(emit).toContain('"event_id":"signup.completed"');
    expect(emit).toContain('"idp":"google"');
    expect(emit).toContain('"intent":"migrate"');

    // PII assertions (S-134 security plan): no email, no given/family name, no raw IP.
    expect(emit).not.toMatch(/@/);
    expect(emit).not.toMatch(/given_name|family_name|firstName|lastName/i);
    expect(emit).not.toMatch(/\b(?:\d{1,3}\.){3}\d{1,3}\b/);

    // One-shot: stamp is consumed; reload does not re-emit.
    const cleared = await page.evaluate(() => sessionStorage.getItem('alpenflight.signup-pending'));
    expect(cleared).toBeNull();
  });

  test('/migrate/start with NO signup-pending stamp does not emit signup.completed', async ({
    page,
  }) => {
    const funnelEvents: string[] = [];
    page.on('console', (msg) => {
      if (msg.type() === 'info' && msg.text().startsWith('[funnel]')) {
        funnelEvents.push(msg.text());
      }
    });

    await page.goto('/migrate/start');
    await expect(page.getByTestId('migrate-handshake')).toBeVisible();

    // Give the OnInit a frame to attempt + give up.
    await page.waitForTimeout(250);
    expect(funnelEvents.filter((e) => e.includes('signup.completed'))).toHaveLength(0);
  });
});
