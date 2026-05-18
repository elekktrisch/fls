import { expect, test } from '@playwright/test';

/**
 * Happy-path OIDC smoke against a real Keycloak. Skips when Keycloak is
 * not reachable on `:8090` — the spec is intended for the
 * `next/ops/dev-up-full.sh` bring-up + nightly runs, NOT the PR-CI
 * mock-auth lane that runs `clubs-crud.spec.ts`.
 *
 * Deferred coverage (S-021 test plan note): silent-refresh via
 * `accessTokenLifespan` shortening, refresh-expired via SSO-session
 * shortening, multi-tab logout detection. Those need Keycloak Admin REST
 * orchestration + serial-describe scaffolding; defer to a Playwright
 * harness story once the dev-up-full + Keycloak combo is wired into CI.
 */

const KEYCLOAK_URL = process.env['KEYCLOAK_URL'] ?? 'http://localhost:8090';

async function keycloakReachable(): Promise<boolean> {
  try {
    const res = await fetch(`${KEYCLOAK_URL}/realms/alpenflight/.well-known/openid-configuration`, {
      signal: AbortSignal.timeout(2_000),
    });
    return res.ok;
  } catch {
    return false;
  }
}

test.beforeEach(async () => {
  test.skip(
    !(await keycloakReachable()),
    `Keycloak unreachable at ${KEYCLOAK_URL}; OIDC e2e requires next/ops/dev-up-full.sh`,
  );
});

test('cold-start /clubs redirects to Keycloak with PKCE + ui_locales=de', async ({ page }) => {
  const navigationPromise = page.waitForURL(/realms\/alpenflight\/protocol\/openid-connect\/auth/, {
    timeout: 10_000,
  });
  await page.goto('/clubs');
  await navigationPromise;

  const url = page.url();
  expect(url).toContain('response_type=code');
  expect(url).toContain('code_challenge=');
  expect(url).toContain('code_challenge_method=S256');
  expect(url).toContain('ui_locales=de');
  expect(url).toContain('client_id=alpenflight-web');
});

test('public route /landing stays public without redirect', async ({ page }) => {
  await page.goto('/');
  await expect(page).toHaveURL(/\/$/);
  await expect(page.locator('h1')).toContainText(/AlpenFlight/i);
});
