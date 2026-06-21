import { test, expect } from '../_helpers/console-guard';

/**
 * S-174 — Google IdP click-redirect smoke. Unconditional (no env-gate)
 * per refinement grill: the click reaches `accounts.google.com` even
 * with the committed placeholder Google client_id, because the bounce
 * to Google happens BEFORE Keycloak's invalid_client response.
 *
 * No Google credentials ever enter Playwright; we don't drive the
 * accounts.google.com page (that's manual-smoke territory, per
 * alpenflight/auth/README.md).
 */

test('google CTA on /signup redirects to accounts.google.com', async ({ page }) => {
  await page.goto('/signup');
  await expect(page.getByTestId('signup-google')).toBeVisible();

  // Detect the first cross-origin navigation toward accounts.google.com.
  // Following the redirect chain manually beats `page.waitForURL` because
  // the SPA navigates first to KC's `/broker/google/endpoint` and only
  // then 302s onward; `waitForRequest` catches the outbound regardless.
  const googleRequest = page.waitForRequest(
    (req) => {
      try {
        return new URL(req.url()).hostname.endsWith('accounts.google.com');
      } catch {
        return false;
      }
    },
    { timeout: 15_000 },
  );

  await page.getByTestId('signup-google').click();
  const matched = await googleRequest;

  // URL host is the contract — assertion target stays here. DOM on
  // accounts.google.com is intentionally NOT inspected.
  const url = new URL(matched.url());
  expect(url.hostname).toMatch(/(^|\.)accounts\.google\.com$/);

  // Step back to the SPA so we don't end the test parked on a Google-owned
  // page (matches the refinement security plan's `page.goBack()` contract).
  // Playwright disposes the per-test context after the assertion anyway;
  // this is for the operator viewing trace replay, not isolation.
  await page.goBack().catch(() => {
    /* Google may have already redirected; goBack is best-effort. */
  });
});
