
const KEY = 'alpenflight.post-login-redirect';

export const DEFAULT_POST_LOGIN_ROUTE = '/start';

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
