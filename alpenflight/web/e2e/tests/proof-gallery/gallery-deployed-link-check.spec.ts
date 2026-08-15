import { type APIRequestContext } from '@playwright/test';
import { expect, test } from '../_helpers/console-guard';


const DEPLOYED_POLL_BUDGET_MS = 180_000;
const DEPLOYED_TEST_TIMEOUT_MS = DEPLOYED_POLL_BUDGET_MS + 30_000;
const RETRY_INTERVAL_MS = 5_000;

const EXPECT_GENERATED_AT = (() => {
  const raw = process.env['GALLERY_EXPECT_GENERATED_AT']?.trim();
  return raw && Number.isFinite(Date.parse(raw)) ? raw : undefined;
})();

function pageUrl(deployed: string): string {
  if (/\.html$/.test(deployed)) return deployed;
  return new URL('index.html', deployed.endsWith('/') ? deployed : `${deployed}/`).href;
}

async function getFresh(request: APIRequestContext, url: string) {
  const u = new URL(url);
  u.searchParams.set('_nocache', `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`);
  return request.get(u.href, {
    headers: { 'cache-control': 'no-cache', pragma: 'no-cache' },
  });
}

function stalenessReason(html: string): string {
  if (!EXPECT_GENERATED_AT) return '';
  const served = /<meta name="proof-generated-at" content="([^"]+)">/i.exec(html)?.[1];
  if (!served) {
    return `served page carries no proof-generated-at stamp — a pre-stamp copy, not the page this run published (${EXPECT_GENERATED_AT})`;
  }
  if (Date.parse(served) < Date.parse(EXPECT_GENERATED_AT)) {
    return `stale CDN copy: served page was generated ${served}, this run published ${EXPECT_GENERATED_AT}`;
  }
  return '';
}

function extractHrefs(html: string): string[] {
  return [...new Set([...html.matchAll(/<a\b[^>]*\bhref="([^"]+)"/gi)].map((m) => m[1]!))];
}

function extractAssetSrcs(html: string): string[] {
  return [
    ...new Set(
      [...html.matchAll(/<(?:img|video|source)\b[^>]*\bsrc="([^"]+)"/gi)].map((m) => m[1]!),
    ),
  ];
}

async function checkOnce(request: APIRequestContext, url: string): Promise<string[]> {
  const broken: string[] = [];
  const res = await getFresh(request, url);
  if (!res.ok()) return [`${url} → ${res.status()}`];
  const html = await res.text();
  const stale = stalenessReason(html);
  if (stale) return [`${url} → ${stale}`];

  for (const ref of [...extractAssetSrcs(html), ...extractHrefs(html)]) {
    if (/^(?:#|[a-z]+:|\/\/)/i.test(ref)) continue;
    const abs = new URL(ref, url).href;
    const r = await getFresh(request, abs);
    if (!r.ok()) broken.push(`${url} → ${abs} (${r.status()})`);
  }
  return broken;
}

async function checkWithRetry(request: APIRequestContext, url: string): Promise<void> {
  const deadlineMs = Date.now() + DEPLOYED_POLL_BUDGET_MS;
  let broken: string[];
  for (;;) {
    broken = await checkOnce(request, url);
    if (broken.length === 0) return;
    if (Date.now() >= deadlineMs) break;
    await new Promise((r) => setTimeout(r, RETRY_INTERVAL_MS));
  }
  expect(broken, `live dead links (after retry):\n  - ${broken.join('\n  - ')}`).toEqual([]);
}

const DEPLOYED = process.env['GALLERY_DEPLOYED_URL'];
const DEPLOYED_JOURNEY = process.env['GALLERY_DEPLOYED_JOURNEY'];

test.describe('proof-gallery deployed bookmark', () => {
  test('[deployed] the single bookmark page + every declared asset resolve 200', async ({
    request,
  }) => {
    test.skip(!DEPLOYED, 'set GALLERY_DEPLOYED_URL to check the live gh-pages bookmark');
    test.setTimeout(DEPLOYED_TEST_TIMEOUT_MS);
    await checkWithRetry(request, pageUrl(DEPLOYED!));
  });

  test('[deployed-journey] the bookmark is the in-flight journey + not a thin page', async ({
    request,
  }) => {
    test.skip(
      !DEPLOYED || !DEPLOYED_JOURNEY,
      'set GALLERY_DEPLOYED_URL + GALLERY_DEPLOYED_JOURNEY to assert the in-flight page',
    );
    test.setTimeout(DEPLOYED_TEST_TIMEOUT_MS);
    const url = pageUrl(DEPLOYED!);
    const deadlineMs = Date.now() + DEPLOYED_POLL_BUDGET_MS;

    let lastErr: string;
    for (;;) {
      lastErr = await tryAssertJourneyPage(request, url, DEPLOYED_JOURNEY!);
      if (lastErr === '') return;
      if (Date.now() >= deadlineMs) break;
      await new Promise((r) => setTimeout(r, RETRY_INTERVAL_MS));
    }
    expect(lastErr, `deployed in-flight page check (after retry):\n  ${lastErr}`).toBe('');
  });
});

async function tryAssertJourneyPage(
  request: APIRequestContext,
  url: string,
  journey: string,
): Promise<string> {
  const res = await getFresh(request, url);
  if (!res.ok()) return `bookmark page ${url} → ${res.status()}`;
  const html = await res.text();

  const stale = stalenessReason(html);
  if (stale) return `bookmark page ${url}: ${stale}`;

  if (!html.includes(`>${journey} — proof</h1>`)) {
    return `bookmark page ${url} is not journey ${journey}'s page (no "<h1>${journey} — proof</h1>")`;
  }
  const assets = extractAssetSrcs(html).filter((s) => !/^#/.test(s));
  if (assets.length === 0) {
    return `bookmark page ${url} declares NO video/screenshot assets (thin/pending page)`;
  }
  const broken: string[] = [];
  for (const src of assets) {
    if (/^(?:[a-z]+:|\/\/)/i.test(src)) continue;
    const abs = new URL(src, url).href;
    const r = await getFresh(request, abs);
    if (!r.ok()) broken.push(`${abs} (${r.status()})`);
  }
  if (broken.length) {
    return `bookmark page ${url} has ${broken.length} asset(s) not resolving 200:\n    - ${broken.join('\n    - ')}`;
  }
  console.log(
    `[deployed-journey] ${journey}: ${assets.length} live asset(s) on ${url} (generated-at ${
      /<meta name="proof-generated-at" content="([^"]+)">/i.exec(html)?.[1] ?? 'unstamped'
    })`,
  );
  return '';
}
