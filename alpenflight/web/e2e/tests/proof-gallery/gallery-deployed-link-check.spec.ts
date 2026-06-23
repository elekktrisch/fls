import { type APIRequestContext } from '@playwright/test';
import { expect, test } from '../_helpers/console-guard';

/**
 * The DEPLOYED single-bookmark link-check — the one-page gallery's DoD.
 *
 * The gallery is now ONE stable-bookmark page (`generate-gallery.mjs` emits a
 * single `index.html` for the in-flight journey). This asserts the DEPLOYED
 * bookmark is a LIVE link AND every asset it declares (videos + paired
 * screenshots + maintainability reports) resolves HTTP 200 — the catch the
 * generator unit test cannot make, because the deploy path can drift independently
 * of the generator (the failure that recurred ~4×: a green generator while the
 * deployed page was missing/thin). Verify the DEPLOYED artifact, not the unit test.
 *
 * BROWSERLESS. chrome-headless cannot launch in this sandbox (musl symbol-reloc),
 * so this spec uses ONLY the Playwright `request` (APIRequestContext) fixture —
 * never `page` / a browser context. It runs under the dedicated
 * `proof-gallery-links` Playwright project (no browser, no webServer), so it runs
 * autonomously in any task context and in CI.
 *
 * ENV CONTRACT (the deploy step sets these post-deploy):
 *   - GALLERY_DEPLOYED_URL  the deployed bookmark — the page URL or its parent dir
 *                           (a trailing-`/` dir is resolved to `index.html`).
 *   - GALLERY_DEPLOYED_JOURNEY  optional; when set, the page's `<h1>` must read
 *                           `<journey> — proof` and the page must NOT be a thin
 *                           "pending" placeholder (it must declare ≥1 asset).
 * Default (neither set) = the test skips (no deployed URL to check).
 */

// gh-pages CDN propagation can lag a freshly-pushed deploy by more than a minute,
// so the deployed walk polls this long before failing on a 404. The per-test
// timeout sits ABOVE the poll budget so Playwright never kills the retry mid-loop.
const DEPLOYED_POLL_BUDGET_MS = 120_000;
const DEPLOYED_TEST_TIMEOUT_MS = DEPLOYED_POLL_BUDGET_MS + 30_000;
const RETRY_INTERVAL_MS = 5_000;

/** Resolve a deployed URL to the page URL gh-pages serves (dir → index.html). */
function pageUrl(deployed: string): string {
  if (/\.html$/.test(deployed)) return deployed;
  return new URL('index.html', deployed.endsWith('/') ? deployed : `${deployed}/`).href;
}

/** Every `<a href>` in an HTML string (deduped, in document order). */
function extractHrefs(html: string): string[] {
  return [...new Set([...html.matchAll(/<a\b[^>]*\bhref="([^"]+)"/gi)].map((m) => m[1]!))];
}

/** Every asset src: `<img src>`, `<video src>`, `<source src>` (deduped). */
function extractAssetSrcs(html: string): string[] {
  return [
    ...new Set(
      [...html.matchAll(/<(?:img|video|source)\b[^>]*\bsrc="([^"]+)"/gi)].map((m) => m[1]!),
    ),
  ];
}

/**
 * One pass over the deployed page: fetch it + every asset + every same-document
 * link it declares, asserting each resolves 200. Returns the list of broken URLs
 * (empty on success). A page-level non-200 is itself a break (a dead bookmark).
 */
async function checkOnce(request: APIRequestContext, url: string): Promise<string[]> {
  const broken: string[] = [];
  const res = await request.get(url);
  if (!res.ok()) return [`${url} → ${res.status()}`];
  const html = await res.text();

  for (const ref of [...extractAssetSrcs(html), ...extractHrefs(html)]) {
    if (/^(?:#|[a-z]+:|\/\/)/i.test(ref)) continue; // fragment / external / protocol-relative
    const abs = new URL(ref, url).href;
    const r = await request.get(abs);
    if (!r.ok()) broken.push(`${url} → ${abs} (${r.status()})`);
  }
  return broken;
}

/** Poll `checkOnce` over the propagation budget before failing (CDN slack). */
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

  // When the deploy declares which journey the bookmark is for, additionally
  // assert the page is the RIGHT journey and is not a thin "pending" placeholder
  // — the page declares ≥1 asset (video or screenshot). This is the catch the
  // unit test can't make: the deploy path can drift so a green generator ships a
  // missing/thin page.
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

/** One pass; returns '' on success or a human-readable failure reason. */
async function tryAssertJourneyPage(
  request: APIRequestContext,
  url: string,
  journey: string,
): Promise<string> {
  const res = await request.get(url);
  if (!res.ok()) return `bookmark page ${url} → ${res.status()}`;
  const html = await res.text();

  // The page must be the in-flight journey's page (the generator's `<h1>`).
  if (!html.includes(`>${journey} — proof</h1>`)) {
    return `bookmark page ${url} is not journey ${journey}'s page (no "<h1>${journey} — proof</h1>")`;
  }
  // It must NOT be a thin "pending" placeholder — declare ≥1 asset (video/shot).
  const assets = extractAssetSrcs(html).filter((s) => !/^#/.test(s));
  if (assets.length === 0) {
    return `bookmark page ${url} declares NO video/screenshot assets (thin/pending page)`;
  }
  // Every declared asset resolves 200.
  const broken: string[] = [];
  for (const src of assets) {
    if (/^(?:[a-z]+:|\/\/)/i.test(src)) continue;
    const abs = new URL(src, url).href;
    const r = await request.get(abs);
    if (!r.ok()) broken.push(`${abs} (${r.status()})`);
  }
  if (broken.length) {
    return `bookmark page ${url} has ${broken.length} asset(s) not resolving 200:\n    - ${broken.join('\n    - ')}`;
  }
  return '';
}
