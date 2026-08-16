import { describe, expect, it } from 'vitest';

import {
  EMAIL_ACTION_OUTCOME_PARAM,
  EXPIRED_OR_ALREADY_USED_OUTCOME_VALUE,
  OIDC_ERROR_DESCRIPTION_PARAM,
  OIDC_ERROR_PARAM,
  type QueryParameterReader,
  VERIFIED_OUTCOME_VALUE,
  readTheOutcomeTheKeycloakEmailActionEndedIn,
} from './keycloak-email-action-outcome';

function queryOf(search: string): QueryParameterReader {
  return new URLSearchParams(search);
}

function angularParamMapShapedReader(values: Record<string, string>): QueryParameterReader {
  return { get: (name: string): string | null => values[name] ?? null };
}

describe('readTheOutcomeTheKeycloakEmailActionEndedIn', () => {
  it('reads the verified outcome from a bare /confirm, because only a failed action adds a signal', () => {
    expect(readTheOutcomeTheKeycloakEmailActionEndedIn(queryOf(''))).toBe('verified');
  });

  it('reads the verified outcome when the theme back link names it', () => {
    expect(
      readTheOutcomeTheKeycloakEmailActionEndedIn(
        queryOf(`${EMAIL_ACTION_OUTCOME_PARAM}=${VERIFIED_OUTCOME_VALUE}`),
      ),
    ).toBe('verified');
  });

  it('reads the failed outcome when the theme back link names the expired value', () => {
    expect(
      readTheOutcomeTheKeycloakEmailActionEndedIn(
        queryOf(`${EMAIL_ACTION_OUTCOME_PARAM}=${EXPIRED_OR_ALREADY_USED_OUTCOME_VALUE}`),
      ),
    ).toBe('expiredOrAlreadyUsed');
  });

  it('ignores the letter case and the spaces around the outcome value', () => {
    expect(readTheOutcomeTheKeycloakEmailActionEndedIn(queryOf('outcome=%20EXPIRED%20'))).toBe(
      'expiredOrAlreadyUsed',
    );
  });

  it('reads the failed outcome from the OIDC error parameter alone', () => {
    expect(
      readTheOutcomeTheKeycloakEmailActionEndedIn(queryOf(`${OIDC_ERROR_PARAM}=access_denied`)),
    ).toBe('expiredOrAlreadyUsed');
  });

  it('reads the failed outcome from the OIDC error description alone, in every locale', () => {
    expect(
      readTheOutcomeTheKeycloakEmailActionEndedIn(
        queryOf(`${OIDC_ERROR_DESCRIPTION_PARAM}=Der%20Link%20ist%20abgelaufen`),
      ),
    ).toBe('expiredOrAlreadyUsed');
  });

  it('treats an empty error parameter as no signal, so a trailing ?error= keeps the verified state', () => {
    expect(readTheOutcomeTheKeycloakEmailActionEndedIn(queryOf(`${OIDC_ERROR_PARAM}=`))).toBe(
      'verified',
    );
  });

  it('keeps the verified state for an outcome value it does not know', () => {
    expect(
      readTheOutcomeTheKeycloakEmailActionEndedIn(
        queryOf(`${EMAIL_ACTION_OUTCOME_PARAM}=something-keycloak-26-invents`),
      ),
    ).toBe('verified');
  });

  it('accepts the Angular ParamMap shape, so the component passes its query map unchanged', () => {
    expect(
      readTheOutcomeTheKeycloakEmailActionEndedIn(
        angularParamMapShapedReader({
          [EMAIL_ACTION_OUTCOME_PARAM]: EXPIRED_OR_ALREADY_USED_OUTCOME_VALUE,
        }),
      ),
    ).toBe('expiredOrAlreadyUsed');
  });
});
