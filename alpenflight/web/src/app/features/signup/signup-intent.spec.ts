import { describe, expect, it } from 'vitest';

import {
  POST_SIGNUP_DEFAULT_PATH,
  postSignupLandingPath,
  resolveSignupIntent,
} from './signup-intent';

describe('resolveSignupIntent', () => {
  it('returns `migrate` for the canonical raw value', () => {
    expect(resolveSignupIntent('migrate')).toBe('migrate');
  });

  // Per refinement grill (S-134): `intent=demo` is silently coerced to
  // `migrate` because /demo is anonymous-pre-signup (S-136), not a
  // post-auth destination.
  it.each([null, undefined, '', 'demo', 'DEMO', 'garbage', 'https://evil.com/'])(
    'coerces %p to `migrate`',
    (raw) => {
      expect(resolveSignupIntent(raw)).toBe('migrate');
    },
  );
});

describe('postSignupLandingPath', () => {
  it('maps `migrate` to /migrate/start', () => {
    expect(postSignupLandingPath('migrate')).toBe('/migrate/start');
    expect(postSignupLandingPath('migrate')).toBe(POST_SIGNUP_DEFAULT_PATH);
  });
});
