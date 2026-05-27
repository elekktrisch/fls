// Self-service signup intent (S-134). The `?intent=` query param rides through
// the Keycloak round-trip via the existing `post-login-redirect` sessionStorage
// allowlist (auth-owned, see core/auth/post-login-redirect.ts). The post-callback
// landing reads the resolved target path and routes there.
//
// Per refinement grill: `intent=demo` is *not* a post-signup destination
// (`/demo` is anonymous-pre-signup, owned by S-136). All valid + invalid intents
// resolve to `/migrate/start`. Keeping the enum as an extension surface for
// future intents (e.g. an authenticated demo seeder, post-S-138).

export type SignupIntent = 'migrate';

export const POST_SIGNUP_DEFAULT_PATH = '/migrate/start';

/**
 * Normalize the raw `?intent=` query string into the SignupIntent enum.
 * Unknown / missing / structurally-invalid values fall through to the default —
 * see signup-intent.spec.ts. Never returns a free-form string; the router
 * switches on the enum, never `Router.navigateByUrl(rawIntent)`.
 */
export function resolveSignupIntent(raw: string | null | undefined): SignupIntent {
  if (raw === 'migrate') return 'migrate';
  // `demo` (per grill), missing, garbage, empty all coerce to migrate.
  return 'migrate';
}

/**
 * Map the resolved enum to the SPA route the post-callback navigation should
 * land on. The single-arm switch is deliberate — future intents add an arm
 * without breaking the resolver/router contract.
 */
export function postSignupLandingPath(intent: SignupIntent): string {
  switch (intent) {
    case 'migrate':
      return '/migrate/start';
  }
}
