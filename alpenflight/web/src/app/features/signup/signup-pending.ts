const SIGNUP_PENDING_STORAGE_KEY = 'alpenflight.signup-pending';

interface SignupPending {
  idp: 'local' | 'google';
  startedAt: string;
}

function storage(): Storage | null {
  return typeof sessionStorage === 'undefined' ? null : sessionStorage;
}

export function markSignupPending(idp: SignupPending['idp']): void {
  const payload: SignupPending = { idp, startedAt: new Date().toISOString() };
  storage()?.setItem(SIGNUP_PENDING_STORAGE_KEY, JSON.stringify(payload));
}

export function consumeSignupPending(): SignupPending | null {
  const s = storage();
  if (!s) return null;
  const raw = s.getItem(SIGNUP_PENDING_STORAGE_KEY);
  if (!raw) return null;
  s.removeItem(SIGNUP_PENDING_STORAGE_KEY);
  try {
    const parsed = JSON.parse(raw) as Partial<SignupPending>;
    if (parsed.idp !== 'local' && parsed.idp !== 'google') return null;
    if (typeof parsed.startedAt !== 'string') return null;
    return { idp: parsed.idp, startedAt: parsed.startedAt };
  } catch {
    return null;
  }
}
