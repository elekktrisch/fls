import { type Page } from '@playwright/test';
import {
  allowConsoleErrors,
  consoleErrorAllowanceForStatusesOnEndpoint,
  test,
  expect,
} from './console-guard';
import {
  countAuthorizationCodeGrantsKeycloakAccepted,
  countRefreshTokenGrantsKeycloakAccepted,
  countRefreshTokenGrantsKeycloakRejected,
} from './keycloak-grant-counters';

const KEYCLOAK_ORIGIN_THE_COUNTERS_NEVER_CONTACT_FOR_REAL = 'http://af-grant-counters.test';
const KC_TOKEN_ENDPOINT = `${KEYCLOAK_ORIGIN_THE_COUNTERS_NEVER_CONTACT_FOR_REAL}/realms/alpenflight/protocol/openid-connect/token`;

const REFRESH_TOKEN_GRANT_BODY = 'grant_type=refresh_token&refresh_token=whatever';
const AUTHORIZATION_CODE_GRANT_BODY = 'grant_type=authorization_code&code=whatever';

const ACCEPTED_GRANT_ANSWER = { status: 200, body: '{"access_token":"a"}' };
const REJECTED_GRANT_ANSWER = { status: 400, body: '{"error":"invalid_grant"}' };

async function keycloakAnswersTheNextTokenPostWith(
  page: Page,
  answer: { status: number; body: string },
): Promise<void> {
  await page.route(KC_TOKEN_ENDPOINT, (route) =>
    route.fulfill({ status: answer.status, contentType: 'application/json', body: answer.body }),
  );
}

async function postGrant(page: Page, grantBody: string): Promise<void> {
  await page.evaluate(
    async (grant: { endpoint: string; body: string }) => {
      await fetch(grant.endpoint, {
        method: 'POST',
        headers: { 'content-type': 'application/x-www-form-urlencoded' },
        body: grant.body,
      }).catch(() => undefined);
    },
    { endpoint: KC_TOKEN_ENDPOINT, body: grantBody },
  );
}

async function openAPageThatCanPostToKeycloak(page: Page): Promise<void> {
  await page.route(`${KEYCLOAK_ORIGIN_THE_COUNTERS_NEVER_CONTACT_FOR_REAL}/`, (route) =>
    route.fulfill({ status: 200, contentType: 'text/html', body: '<html><body></body></html>' }),
  );
  await page.goto(`${KEYCLOAK_ORIGIN_THE_COUNTERS_NEVER_CONTACT_FOR_REAL}/`);
}

test.describe('Keycloak grant counters — an accepted grant is not a request', () => {
  test('a rejected rotation raises the rejected count and leaves the accepted count at zero', async ({
    page,
  }, testInfo) => {
    allowConsoleErrors(
      testInfo,
      consoleErrorAllowanceForStatusesOnEndpoint(
        [REJECTED_GRANT_ANSWER.status],
        new URL(KC_TOKEN_ENDPOINT).pathname,
      ),
    );
    const accepted = countRefreshTokenGrantsKeycloakAccepted(page);
    const rejected = countRefreshTokenGrantsKeycloakRejected(page);
    await openAPageThatCanPostToKeycloak(page);

    await keycloakAnswersTheNextTokenPostWith(page, REJECTED_GRANT_ANSWER);
    await postGrant(page, REFRESH_TOKEN_GRANT_BODY);

    expect(
      accepted(),
      'the accepted-rotation counter counted a rejection, so a silent-refresh assertion would ' +
        'also pass on a rejected rotation followed by a re-authorize',
    ).toBe(0);
    expect(rejected()).toBe(1);
  });

  test('an accepted rotation raises the accepted count', async ({ page }) => {
    const accepted = countRefreshTokenGrantsKeycloakAccepted(page);
    const rejected = countRefreshTokenGrantsKeycloakRejected(page);
    await openAPageThatCanPostToKeycloak(page);

    await keycloakAnswersTheNextTokenPostWith(page, ACCEPTED_GRANT_ANSWER);
    await postGrant(page, REFRESH_TOKEN_GRANT_BODY);

    expect(accepted()).toBe(1);
    expect(rejected()).toBe(0);
  });

  test('the refresh counters ignore an authorization-code grant, and the reverse', async ({
    page,
  }) => {
    const refreshAccepted = countRefreshTokenGrantsKeycloakAccepted(page);
    const codeAccepted = countAuthorizationCodeGrantsKeycloakAccepted(page);
    await openAPageThatCanPostToKeycloak(page);

    await keycloakAnswersTheNextTokenPostWith(page, ACCEPTED_GRANT_ANSWER);
    await postGrant(page, AUTHORIZATION_CODE_GRANT_BODY);

    expect(refreshAccepted()).toBe(0);
    expect(codeAccepted()).toBe(1);
  });
});
