import type { TestInfo } from '@playwright/test';

/**
 * Stable per-test identifier derived from the test description (not from
 * Date.now() or random). Same test title -> same slug, every run. That
 * means re-running a test sees the same row (and tests that clean up
 * leave a clean state; tests that don't, leave a single deterministic
 * row in the DB — useful for debugging because you can find it by name).
 *
 * Returns three forms of the id:
 *  - `slug`:  lowercase, hyphenated, max 60 chars
 *             ("12-locations-create-edit-delete")
 *  - `name`:  human-readable prefix + slug, suitable for entity
 *             "Name" / "FriendlyName" columns
 *             ("E2E 12 locations create-edit-delete")
 *  - `short`: 6 alphanumeric chars derived from a hash of the slug,
 *             for fixed-width fields like ICAO codes (max 6).
 */
export type TestId = { slug: string; name: string; short: string };

export function testId(testInfo: TestInfo): TestId {
  // Use file + test title so two tests with the same title in different
  // files don't collide.
  const file = (testInfo.titlePath[0] ?? '').replace(/\.spec\.ts$/, '');
  const titleParts = testInfo.titlePath.slice(1).join(' ');
  const raw = `${file} ${titleParts}`;
  const slug = raw
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 60);
  // 32-bit FNV-1a hash -> base36 string; take 6 chars for the short form.
  let h = 0x811c9dc5;
  for (let i = 0; i < slug.length; i++) {
    h ^= slug.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  const short = h.toString(36).toUpperCase().padStart(7, '0').slice(0, 6);
  return { slug, name: `E2E ${slug}`, short };
}
