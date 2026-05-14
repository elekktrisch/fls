import type { TestInfo } from '@playwright/test';

/**
 * Stable per-test id derived from the test's title path. Same title → same
 * slug → same row, every run. See e2e/TEST_WRITING.md §1.
 *
 *  - slug:  lowercase-hyphenated, max 60 chars
 *  - name:  "E2E <slug>" — for Name / FriendlyName columns
 *  - short: 6-char base36 FNV-1a hash, for fixed-width fields like ICAO
 */
export type TestId = { slug: string; name: string; short: string };

export function testId(testInfo: TestInfo): TestId {
  const file = (testInfo.titlePath[0] ?? '').replace(/\.spec\.ts$/, '');
  const titleParts = testInfo.titlePath.slice(1).join(' ');
  const raw = `${file} ${titleParts}`;
  const slug = raw
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 60);
  let h = 0x811c9dc5;
  for (let i = 0; i < slug.length; i++) {
    h ^= slug.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  const short = h.toString(36).toUpperCase().padStart(7, '0').slice(0, 6);
  return { slug, name: `E2E ${slug}`, short };
}
