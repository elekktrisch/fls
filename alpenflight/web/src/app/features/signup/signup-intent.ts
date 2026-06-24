// Self-service signup intent. The `?intent=` query param rides through the
// Keycloak round-trip via the existing `post-login-redirect` sessionStorage
// allowlist (auth-owned). The post-callback landing reads the resolved target
// path and routes there. `/demo` is anonymous-pre-signup (a different feature),
// so it's coerced here — never a post-auth destination.

export type SignupIntent = 'join' | 'migrate';

// S-179: join is the dominant new-member path, so a new signup lands on /join
// by default; migration becomes a side path reachable via `intent=migrate` or
// the direct `/migrate/start` deep link.
export const POST_SIGNUP_DEFAULT_PATH = '/join';

/**
 * Normalize the raw `?intent=` query string into the SignupIntent enum. The
 * enum exists so the router can switch on a known value rather than
 * `navigateByUrl(rawIntent)` — that path would be an open redirect. Only an
 * explicit `migrate` opts out of the join default; everything else (including
 * `demo`, which is anonymous-pre-signup, and any garbage) coerces to `join`.
 */
export function resolveSignupIntent(raw: string | null | undefined): SignupIntent {
  return raw === 'migrate' ? 'migrate' : 'join';
}

/** Map the resolved enum to the post-callback SPA route. */
export function postSignupLandingPath(intent: SignupIntent): string {
  switch (intent) {
    case 'join':
      return '/join';
    case 'migrate':
      return '/migrate/start';
  }
}
