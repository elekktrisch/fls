import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { consumeSignupPending, markSignupPending } from './signup-pending';

describe('signup-pending', () => {
  beforeEach(() => {
    sessionStorage.clear();
  });
  afterEach(() => {
    sessionStorage.clear();
  });

  it('round-trips a local stamp', () => {
    markSignupPending('local');
    const consumed = consumeSignupPending();
    expect(consumed?.idp).toBe('local');
    expect(consumed?.startedAt).toMatch(/^\d{4}-\d{2}-\d{2}T/);
  });

  it('round-trips a google stamp', () => {
    markSignupPending('google');
    expect(consumeSignupPending()?.idp).toBe('google');
  });

  it('is one-shot: second consume returns null (post-callback re-render guard)', () => {
    markSignupPending('local');
    consumeSignupPending();
    expect(consumeSignupPending()).toBeNull();
  });

  it('returns null when no stamp present', () => {
    expect(consumeSignupPending()).toBeNull();
  });

  it('returns null on a corrupt stamp (and clears it)', () => {
    sessionStorage.setItem('alpenflight.signup-pending', 'not-json');
    expect(consumeSignupPending()).toBeNull();
  });
});
