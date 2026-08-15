import type { TestInfo } from '@playwright/test';

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
