// Auth-owned: this is the one allowlisted file for sessionStorage writes
// per alpenflight/web/CLAUDE.md §10. The OIDC library doesn't preserve the
// originally-requested URL across the Keycloak redirect; we persist it
// here before authorize() and consume it after the callback lands.

const KEY = 'alpenflight.post-login-redirect';

export const DEFAULT_POST_LOGIN_ROUTE = '/clubs';

function storage(): Storage | null {
  // eslint-disable-next-line no-restricted-globals
  return typeof sessionStorage === 'undefined' ? null : sessionStorage;
}

export function rememberPostLoginRedirect(url: string): void {
  storage()?.setItem(KEY, url);
}

export function consumePostLoginRedirect(): string | null {
  const s = storage();
  if (!s) return null;
  const v = s.getItem(KEY);
  if (v) s.removeItem(KEY);
  return v;
}
