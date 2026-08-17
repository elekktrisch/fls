import { type Page, type Response } from '@playwright/test';

const KC_TOKEN_ENDPOINT_PATH = '/protocol/openid-connect/token';
const REFRESH_TOKEN_GRANT_BODY = 'grant_type=refresh_token';
const AUTHORIZATION_CODE_GRANT_BODY = 'grant_type=authorization_code';
const HTTP_STATUS_KEYCLOAK_ANSWERS_AN_ACCEPTED_GRANT_WITH = 200;
const LOWEST_HTTP_STATUS_THAT_MEANS_KEYCLOAK_REJECTED_THE_GRANT = 400;

function countTokenEndpointAnswers(
  page: Page,
  grantBody: string,
  answerCounts: (status: number) => boolean,
): () => number {
  let observed = 0;
  page.on('response', (response: Response) => {
    if (
      response.url().includes(KC_TOKEN_ENDPOINT_PATH) &&
      (response.request().postData() ?? '').includes(grantBody) &&
      answerCounts(response.status())
    ) {
      observed += 1;
    }
  });
  return () => observed;
}

export function countRefreshTokenGrantsKeycloakAccepted(page: Page): () => number {
  return countTokenEndpointAnswers(
    page,
    REFRESH_TOKEN_GRANT_BODY,
    (status) => status === HTTP_STATUS_KEYCLOAK_ANSWERS_AN_ACCEPTED_GRANT_WITH,
  );
}

export function countRefreshTokenGrantsKeycloakRejected(page: Page): () => number {
  return countTokenEndpointAnswers(
    page,
    REFRESH_TOKEN_GRANT_BODY,
    (status) => status >= LOWEST_HTTP_STATUS_THAT_MEANS_KEYCLOAK_REJECTED_THE_GRANT,
  );
}

export function countAuthorizationCodeGrantsKeycloakAccepted(page: Page): () => number {
  return countTokenEndpointAnswers(
    page,
    AUTHORIZATION_CODE_GRANT_BODY,
    (status) => status === HTTP_STATUS_KEYCLOAK_ANSWERS_AN_ACCEPTED_GRANT_WITH,
  );
}
