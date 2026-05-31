// Self-service signup intent. The `?intent=` query param rides through the
// Keycloak round-trip via the existing `post-login-redirect` sessionStorage
// allowlist (auth-owned). The post-callback landing reads the resolved target
// path and routes there. `/demo` is anonymous-pre-signup (a different feature),
// so it's coerced here — never a post-auth destination.

export type SignupIntent = 'migrate';

export const POST_SIGNUP_DEFAULT_PATH = '/migrate/start';

/**
 * Normalize the raw `?intent=` query string into the SignupIntent enum. The
 * enum exists so the router can switch on a known value rather than
 * `navigateByUrl(rawIntent)` — that path would be an open redirect. Single-arm
 * today; reintroduce an `if (raw === ...) return ...` when a second intent lands.
 */
export function resolveSignupIntent(raw: string | null | undefined): SignupIntent {
  void raw;
  return 'migrate';
}

/**
 * Map the resolved enum to the post-callback SPA route. Single-arm today;
 * additional intents add an arm without breaking the resolver/router contract.
 */
export function postSignupLandingPath(intent: SignupIntent): string {
  switch (intent) {
    case 'migrate':
      return '/migrate/start';
  }
}
