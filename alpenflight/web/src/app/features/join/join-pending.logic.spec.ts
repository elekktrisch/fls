import { describe, expect, it } from 'vitest';

import { actionForStatus, parseStatusChanged } from './join-pending.logic';

/**
 * Pure decision logic for `/join/pending` (T-11). Tested in vitest per the web
 * testing posture (§8: vitest for logic, Playwright for DOM) — the rendered
 * club projection / withdraw link / SSE-driven navigation are proven by the
 * real-idp spec `e2e/tests/real-idp/join-request.spec.ts`.
 */
describe('parseStatusChanged', () => {
  it('extracts the status from a status-changed frame', () => {
    expect(parseStatusChanged('{"requestId":"jr-1","status":"APPROVED"}')).toBe('APPROVED');
  });

  it('returns null for a frame without a string status', () => {
    expect(parseStatusChanged('{"requestId":"jr-1"}')).toBeNull();
  });

  it('returns null for undecodable data rather than throwing', () => {
    expect(parseStatusChanged('not json')).toBeNull();
  });
});

describe('actionForStatus', () => {
  it('refreshes the token and lands /start on approval', () => {
    expect(actionForStatus('APPROVED')).toBe('refresh-and-start');
  });

  it('surfaces the deny reason on denial', () => {
    expect(actionForStatus('DENIED')).toBe('show-denied');
  });

  it('routes back to /join on withdrawal', () => {
    expect(actionForStatus('WITHDRAWN')).toBe('to-join');
  });

  it('is a no-op for a pending echo or an unknown status', () => {
    expect(actionForStatus('PENDING')).toBe('none');
    expect(actionForStatus('BOGUS')).toBe('none');
    expect(actionForStatus(null)).toBe('none');
  });
});
