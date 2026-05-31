// Auth-owned-adjacent: this file (with core/auth/post-login-redirect.ts) is
// the *only* additional sessionStorage write site introduced by S-134.
// Documented in alpenflight/web/CLAUDE.md §10 (signup feature is bracketed
// as auth-adjacent because it drives the same OIDC round-trip).
//
// The signup-pending stamp marks "a signup flow is in progress" so that the
// post-callback landing fires `signup.completed` exactly once per signup
// round-trip — see post-signup-landing.component.ts.

const KEY = 'alpenflight.signup-pending';

interface SignupPending {
  // The IdP the user chose at /signup CTA-click time. Keycloak echoes back
  // an `identity_provider` token claim only when the federated flow ran;
  // capturing it at outbound-time is more robust than relying on the claim's
  // presence (e.g. behind a token-mapper drift).
  idp: 'local' | 'google';
  // ISO-8601; primarily for diagnostic / cleanup logic if a stamp ever
  // survives a non-completion exit (browser close mid-signup).
  startedAt: string;
}

function storage(): Storage | null {
  return typeof sessionStorage === 'undefined' ? null : sessionStorage;
}

export function markSignupPending(idp: SignupPending['idp']): void {
  const payload: SignupPending = { idp, startedAt: new Date().toISOString() };
  storage()?.setItem(KEY, JSON.stringify(payload));
}

/**
 * One-shot consume. Returns the recorded {@link SignupPending} on first call,
 * `null` thereafter; the consumer is the post-signup landing — re-rendering it
 * must not re-emit the funnel event.
 */
export function consumeSignupPending(): SignupPending | null {
  const s = storage();
  if (!s) return null;
  const raw = s.getItem(KEY);
  if (!raw) return null;
  s.removeItem(KEY);
  try {
    const parsed = JSON.parse(raw) as Partial<SignupPending>;
    if (parsed.idp !== 'local' && parsed.idp !== 'google') return null;
    if (typeof parsed.startedAt !== 'string') return null;
    return { idp: parsed.idp, startedAt: parsed.startedAt };
  } catch {
    return null;
  }
}
