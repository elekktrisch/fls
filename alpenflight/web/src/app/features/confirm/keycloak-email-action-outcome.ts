export type KeycloakEmailActionOutcome = 'verified' | 'expiredOrAlreadyUsed';

export interface QueryParameterReader {
  get(name: string): string | null;
}

export const EMAIL_ACTION_OUTCOME_PARAM = 'outcome';
export const VERIFIED_OUTCOME_VALUE = 'verified';
export const EXPIRED_OR_ALREADY_USED_OUTCOME_VALUE = 'expired';
export const OIDC_ERROR_PARAM = 'error';
export const OIDC_ERROR_DESCRIPTION_PARAM = 'error_description';

function presentValueOrNull(raw: string | null): string | null {
  const trimmed = raw?.trim() ?? '';
  return trimmed.length === 0 ? null : trimmed;
}

export function readTheOutcomeTheKeycloakEmailActionEndedIn(
  queryParameters: QueryParameterReader,
): KeycloakEmailActionOutcome {
  const outcomeTheThemeBackLinkNames = presentValueOrNull(
    queryParameters.get(EMAIL_ACTION_OUTCOME_PARAM),
  )?.toLowerCase();
  if (outcomeTheThemeBackLinkNames === EXPIRED_OR_ALREADY_USED_OUTCOME_VALUE) {
    return 'expiredOrAlreadyUsed';
  }

  const keycloakReportedAnOidcError =
    presentValueOrNull(queryParameters.get(OIDC_ERROR_PARAM)) !== null ||
    presentValueOrNull(queryParameters.get(OIDC_ERROR_DESCRIPTION_PARAM)) !== null;

  return keycloakReportedAnOidcError ? 'expiredOrAlreadyUsed' : 'verified';
}
