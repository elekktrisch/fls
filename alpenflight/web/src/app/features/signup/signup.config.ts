// Single source for SPA-side signup feature flags. Kept out of the component
// so a future env-driven config (S-041 prod cutover) can replace this file via
// `angular.json` `fileReplacements` — the same seam `app.config.ts` →
// `app.config.mock.ts` already uses — without touching the component.

export const SIGNUP_FEATURE_FLAGS = {
  // The Keycloak realm ships a Google IdP entry; flip this to false locally if
  // KEYCLOAK_GOOGLE_CLIENT_ID/SECRET aren't wired and you'd rather hide a
  // button that errors on click. Stays true by default — assumes a real Google
  // OAuth client in shared dev / prod.
  googleSignupEnabled: true,
} as const;
