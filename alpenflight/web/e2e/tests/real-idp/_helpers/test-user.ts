import { randomUUID } from 'node:crypto';

/**
 * Synthetic test-user identity factory for the real-idp suite.
 *
 * Every registered user gets an `e2e-${runId}-${uuid8}@example.com` address
 * — the `e2e-` prefix is the cleanup-predicate safety pin (seed users like
 * `pilot1@example.com` share the `@example.com` suffix; the prefix is what
 * disambiguates). `runId` comes from `setup.ts` via `process.env.E2E_RUN_ID`
 * so a globalTeardown crash-recovery sweep can scope to one run if needed.
 *
 * The canned password satisfies S-134's realm policy
 * `length(12) and notUsername and notEmail and specialChars(1)`.
 */

export const E2E_EMAIL_PREFIX = 'e2e-';
export const E2E_EMAIL_SUFFIX = '@example.com';
export const E2E_OCCUPIED_EMAIL = `${E2E_EMAIL_PREFIX}occupied${E2E_EMAIL_SUFFIX}`;
export const E2E_CANNED_PASSWORD = 'E2eTest-2026!';

function runId(): string {
  const id = process.env['E2E_RUN_ID'];
  if (!id) {
    throw new Error(
      'E2E_RUN_ID not set — real-idp-setup project must run before any real-idp spec',
    );
  }
  return id;
}

export interface TestUser {
  email: string;
  password: string;
  firstName: string;
  lastName: string;
}

export function freshTestUser(): TestUser {
  const tail = randomUUID().replace(/-/g, '').slice(0, 8);
  return {
    email: `${E2E_EMAIL_PREFIX}${runId()}-${tail}${E2E_EMAIL_SUFFIX}`,
    password: E2E_CANNED_PASSWORD,
    firstName: 'E2e',
    lastName: 'Test',
  };
}

/**
 * Cleanup-predicate gate. The Admin REST helper calls this before every
 * DELETE — seed users (`pilot1@example.com` etc.) must NEVER pass.
 */
export function isCleanupCandidate(email: string | undefined): boolean {
  if (!email) return false;
  return email.startsWith(E2E_EMAIL_PREFIX) && email.endsWith(E2E_EMAIL_SUFFIX);
}
