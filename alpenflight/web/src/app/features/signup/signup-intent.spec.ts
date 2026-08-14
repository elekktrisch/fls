import { describe, expect, it } from 'vitest';

import {
  POST_SIGNUP_DEFAULT_PATH,
  postSignupLandingPath,
  resolveSignupIntent,
} from './signup-intent';

describe('resolveSignupIntent', () => {
  it('returns `join` for the canonical join value', () => {
    expect(resolveSignupIntent('join')).toBe('join');
  });

  it('returns `migrate` for the canonical migrate value', () => {
    expect(resolveSignupIntent('migrate')).toBe('migrate');
  });

  it.each([null, undefined, '', 'demo', 'DEMO', 'garbage', 'https://evil.com/'])(
    'coerces %p to `join`',
    (raw) => {
      expect(resolveSignupIntent(raw)).toBe('join');
    },
  );
});

describe('postSignupLandingPath', () => {
  it('maps `join` to /join (the post-signup default)', () => {
    expect(postSignupLandingPath('join')).toBe('/join');
    expect(postSignupLandingPath('join')).toBe(POST_SIGNUP_DEFAULT_PATH);
  });

  it('maps `migrate` to /migrate/start (the legacy side path)', () => {
    expect(postSignupLandingPath('migrate')).toBe('/migrate/start');
  });
});
