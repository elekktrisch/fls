import {
  copyFileSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  rmSync,
  statSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, relative, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

import { expect, test, type APIRequestContext } from '@playwright/test';

/**
 * J-5 T-31/T-33 — gallery LINK-INTEGRITY check (operator ask 2026-06-06).
 *
 * A reusable, autonomously-runnable DoD: "are ALL proof-gallery links live?".
 * Guards the index/link breakage the operator hit (a per-journey page or asset
 * that 404s, a previews-index row pointing at a dead page).
 *
 * T-33 — WHY THIS WAS HARDENED. The first cut (T-31) MISSED 2 of 3 dead links on
 * the live branch-preview deploy because it (a) only generated+walked the
 * CANONICAL `alpenflight/proof/J-<n>/` layout, while the live preview deploys ONE
 * LEVEL DEEPER (`alpenflight/proof-preview/<branch>/legacy-parity/J-<n>/`) where a
 * relative cross-section link breaks; and (b) checked targets with `existsSync`,
 * so a bare-DIRECTORY link passed even though gh-pages 404s a directory with no
 * `index.html`. T-33 closes BOTH gaps locally + adds a deployed-URL gate.
 *
 * WHAT IT DOES (default, local mode — no env):
 *   1. Generates the FULL gallery into a unique temp gh-pages-root, in BOTH the
 *      layouts the fanout actually deploys:
 *        - CANONICAL       `<root>/alpenflight/proof/J-<n>/`
 *        - BRANCH-PREVIEW  `<root>/alpenflight/proof-preview/<branch>/legacy-parity/J-<n>/`
 *      (the fanout publishes `public/alpenflight/proof` → `destination_dir:
 *      alpenflight/proof-preview/<branch>/legacy-parity`, so the preview is one
 *      dir deeper than canonical — exactly where the depth bug lived). Then
 *      `generatePreviewsIndex` scans the root + emits the persistent journeys
 *      index at `<root>/alpenflight/previews/index.html`.
 *   2. WALKS every link/asset, breadth-first, over BOTH layouts, starting from
 *      the previews index AND from each layout's per-journey + all-journeys pages:
 *      each `<a href>`, every page it reaches, every `<img>/<video>/<source>` src.
 *   3. ASSERTS each link resolves on disk, MODELLING gh-pages serve semantics:
 *        - a FILE link → the file exists;
 *        - a site-root-absolute link (`/fls/<X>`) → resolves against the GH-PAGES
 *          ROOT (strip the `/fls/` base), then the same existence check — NOT
 *          treated as dead (this is the back-index `/fls/alpenflight/previews/`);
 *        - a DIRECTORY link (trailing `/`, or a path that is a dir) is OK ONLY if
 *          that dir contains an `index.html` — gh-pages serves `index.html`, else
 *          404s a bare dir (this is the maintainability-dir bug class).
 *      NO dead links; every roadmap journey WITH content has a reachable page in
 *      BOTH layouts; every declared screenshot/video/maintainability-report file
 *      is present. A break fails with a precise `<page> → <broken target>`.
 *
 * BROWSERLESS. chrome-headless cannot launch in this sandbox (musl symbol-reloc),
 * so this spec uses ONLY node `fs` (+ the Playwright `request`/APIRequestContext
 * fixture for the deployed mode) — never `page` / a browser context. It runs
 * under the dedicated `proof-gallery-links` Playwright project (no browser, no
 * webServer — see playwright.config.ts), so it runs autonomously in ANY task
 * context and in CI.
 *
 * DEPLOYED MODE (the catch that makes "a deployed dead link can never ship green"
 * true). Set `GALLERY_DEPLOYED_URL=<baseURL>` (the just-deployed live gh-pages
 * branch-preview, e.g. https://…/fls/alpenflight/proof-preview/<branch>/) and the
 * `[deployed]` test fetches the previews index + every per-journey page + asserts
 * EVERY link returns HTTP 200 via the `request` fixture, polling briefly (gh-pages
 * lags a few seconds post-deploy) before failing on any 404. Wired into the fanout
 * POST-deploy step. Default (no env) = the local generate-and-walk check only.
 */

const PROOF_GALLERY = resolve(__dirname, '..', '..', 'proof-gallery');
const FIXTURES = resolve(PROOF_GALLERY, 'fixtures');
const REPORT = resolve(FIXTURES, 'proof-manifest.json');
const LEGACY_VIDEO_DIR = resolve(FIXTURES, 'legacy-video');
const SCREENSHOTS_DIR = resolve(FIXTURES, 'screenshots');
const MAINT_DIR = resolve(FIXTURES, 'maintainability');

// The 4 maintainability artifacts the per-journey page's panel links/reads.
const MAINT_FILES = ['fallow-audit.json', 'fallow-health.json', 'pmd-main.xml', 'cpd-check.xml'];

// gh-pages serves this repo under `/fls/` (https://elekktrisch.github.io/fls/),
// so a site-root-absolute href `/fls/<X>` resolves to `<ghPagesRoot>/<X>`. Keep
// in lock-step with generate-gallery.mjs's DEFAULT_SITE_BASE.
const SITE_BASE = '/fls/';

// The branch the test generates BOTH layouts for. Sanitized the same way the
// fanout's "Compute fan-out branch-preview destination" step sanitizes the ref
// (`[^A-Za-z0-9._-]` → `-`), so the preview path mirrors the live deploy.
const TEST_BRANCH = 'integration/J-5';
const SANITIZED_BRANCH = TEST_BRANCH.replace(/[^A-Za-z0-9._-]/g, '-');

// The generators ship as ESM `.mjs`; this CJS-transpiled spec loads them via
// dynamic import() — the documented surface (same as the generator specs).
async function loadGenerators(): Promise<{
  generateGallery: (o: {
    reportPath: string;
    outDir: string;
    orderPath?: string;
    branch?: string;
    legacyVideoDir?: string;
    screenshotsDir?: string;
    renderNav?: boolean;
    journeyUnderWork?: string;
  }) => {
    outFile: string;
    journeyPages: { journey: string; outFile: string }[];
    proofs: { journey: string }[];
    shots: { journey: string }[];
  };
  generatePreviewsIndex: (o: { ghPagesRoot: string; branch?: string; orderPath?: string }) => {
    outFile: string;
    journeys: { jid: string; found: boolean; href?: string }[];
    html: string;
  };
}> {
  const [gallery, previews] = await Promise.all([
    import(pathToFileURL(resolve(PROOF_GALLERY, 'generate-gallery.mjs')).href),
    import(pathToFileURL(resolve(PROOF_GALLERY, 'generate-previews-index.mjs')).href),
  ]);
  return {
    generateGallery: gallery.generateGallery,
    generatePreviewsIndex: previews.generatePreviewsIndex,
  };
}

/** Stage the maintainability artifacts into a gallery out-root before generate. */
function stageMaintainability(galleryOut: string): void {
  const outMaint = resolve(galleryOut, 'maintainability');
  mkdirSync(outMaint, { recursive: true });
  for (const f of MAINT_FILES) {
    const src = resolve(MAINT_DIR, f);
    if (existsSync(src)) copyFileSync(src, resolve(outMaint, f));
  }
}

/** Generate the full gallery (all-journeys + per-journey pages) into one out-root. */
function generateInto(
  g: Awaited<ReturnType<typeof loadGenerators>>,
  galleryOut: string,
): { journeyPages: { journey: string; outFile: string }[] } {
  mkdirSync(galleryOut, { recursive: true });
  // Stage T-12's maintainability artifacts BEFORE generate so the panel's
  // "Full reports →" link (maintainability/) has real files + an emitted
  // index.html behind it (matches CI, which stages them there first).
  stageMaintainability(galleryOut);
  const { journeyPages } = g.generateGallery({
    reportPath: REPORT,
    outDir: galleryOut,
    branch: TEST_BRANCH,
    legacyVideoDir: LEGACY_VIDEO_DIR,
    screenshotsDir: SCREENSHOTS_DIR,
    renderNav: false,
    journeyUnderWork: 'J-5',
  });
  return { journeyPages };
}

/**
 * Build a deterministic gh-pages-root with the gallery generated into BOTH the
 * layouts the fanout deploys, plus the persistent previews index:
 *   <root>/alpenflight/proof/                          — CANONICAL out-root
 *   <root>/alpenflight/proof-preview/<branch>/legacy-parity/ — BRANCH-PREVIEW (deeper)
 *   <root>/alpenflight/previews/index.html             — persistent journeys index
 * `previewsIndex` is the index file the walk starts from.
 */
function buildGallery(g: Awaited<ReturnType<typeof loadGenerators>>): {
  root: string;
  canonicalOut: string;
  previewOut: string;
  previewsIndex: string;
  canonicalPages: { journey: string; outFile: string }[];
  previewPages: { journey: string; outFile: string }[];
} {
  const root = mkdtempSync(resolve(tmpdir(), 'gallery-links-'));

  const canonicalOut = resolve(root, 'alpenflight', 'proof');
  const { journeyPages: canonicalPages } = generateInto(g, canonicalOut);

  // BRANCH-PREVIEW: mirror the fanout deploy exactly — publish_dir
  // `public/alpenflight/proof` → destination_dir
  // `alpenflight/proof-preview/<sanitized>/legacy-parity`. One level deeper than
  // canonical, so a relative cross-section link that worked canonically breaks
  // here (the depth bug T-32 fixed with a site-root-absolute back-link).
  const previewOut = resolve(
    root,
    'alpenflight',
    'proof-preview',
    SANITIZED_BRANCH,
    'legacy-parity',
  );
  const { journeyPages: previewPages } = generateInto(g, previewOut);

  // The previews index scans the gh-pages root for per-journey pages + links
  // them — exactly the canonical/branch source the deploy rebuilds.
  const { outFile: previewsIndex } = g.generatePreviewsIndex({
    ghPagesRoot: root,
    branch: SANITIZED_BRANCH,
  });

  return { root, canonicalOut, previewOut, previewsIndex, canonicalPages, previewPages };
}

/** Every `<a href>` value in an HTML string (deduped, in document order). */
function extractHrefs(html: string): string[] {
  return [...new Set([...html.matchAll(/<a\b[^>]*\bhref="([^"]+)"/gi)].map((m) => m[1]!))];
}

/**
 * Every asset src in an HTML string: `<img src>`, `<video src>`, `<source src>`
 * (deduped). Anchor hrefs are handled separately by `extractHrefs` so a
 * navigation link isn't mistaken for an asset.
 */
function extractAssetSrcs(html: string): string[] {
  const srcs = [...html.matchAll(/<(?:img|video|source)\b[^>]*\bsrc="([^"]+)"/gi)].map(
    (m) => m[1]!,
  );
  return [...new Set(srcs)];
}

/** External / mailto / protocol-relative / pure-fragment links: not fs-checkable. */
function isExternal(href: string): boolean {
  return /^(?:[a-z]+:|\/\/|#)/i.test(href);
}

/** A href starting `/` (but not `//`) is site-root-absolute (gh-pages root-rel). */
function isSiteAbsolute(href: string): boolean {
  return href.startsWith('/') && !href.startsWith('//');
}

/**
 * True iff `abs` is at-or-below one of the gallery OUT-ROOTS the generator wrote
 * into — i.e. a dir the generator is responsible for serving (it emits index.html
 * into its out-root, its per-journey dirs, and its maintainability dir). A dir
 * link inside an out-root must serve (index.html present, else gh-pages 404s the
 * bare dir — the maintainability-dir bug class). A dir link that ESCAPES an
 * out-root (the all-journeys page's `../` "alpenflight dashboard" link → the app
 * deploy / the branch-preview parent the gallery doesn't own) is only checked for
 * existence — we don't model the app's deploy here.
 */
function isInsideOutRoot(outRoots: string[], abs: string): boolean {
  return outRoots.some((root) => {
    const r = relative(resolve(root), abs).replace(/\\/g, '/');
    return r === '' || (!r.startsWith('..') && !r.startsWith('/'));
  });
}

/**
 * Resolve a link/src into a verdict, MODELLING gh-pages serve semantics:
 *   - site-root-absolute (`/fls/<X>`): strip the `/fls/` base + resolve the
 *     remainder against the gh-pages ROOT (NOT the page dir) — depth-independent.
 *   - relative: resolve against the referencing page's dir.
 *   - a FILE target: OK iff the file exists.
 *   - a DIRECTORY target (trailing `/`, or a path that is a dir): OK ONLY if it
 *     contains `index.html` (gh-pages serves index.html, else 404s a bare dir).
 *     `page` is set to that index.html so the walk follows page-to-page links.
 *   - target — the on-disk path the verdict is about (for the failure message).
 */
function resolveLink(
  ghPagesRoot: string,
  outRoots: string[],
  referencingPage: string,
  href: string,
): { ok: boolean; target: string; page?: string } {
  const clean = href.replace(/[?#].*$/, '');
  let abs: string;
  if (isSiteAbsolute(clean)) {
    // `/fls/alpenflight/previews/` → <ghPagesRoot>/alpenflight/previews/
    const withoutBase = clean.startsWith(SITE_BASE)
      ? clean.slice(SITE_BASE.length)
      : clean.replace(/^\//, '');
    abs = resolve(ghPagesRoot, withoutBase);
  } else {
    abs = resolve(dirname(referencingPage), clean);
  }
  const dirLink = clean.endsWith('/') || isDir(abs);
  if (dirLink) {
    const index = resolve(abs, 'index.html');
    if (existsSync(index)) return { ok: true, target: index, page: index };
    // Inside a gallery-owned tree, gh-pages 404s a bare dir with no index.html —
    // DEAD (e.g. a maintainability/ dir whose JSON/XML deployed but no index.html
    // emitted). A dir link that ESCAPES an out-root (the app dashboard `../`) is
    // not the gallery's deploy to model — accept if the dir exists.
    if (isInsideOutRoot(outRoots, abs)) return { ok: false, target: abs };
    return { ok: isDir(abs), target: abs };
  }
  return { ok: existsSync(abs), target: abs };
}

/** True iff `p` exists and is a directory. */
function isDir(p: string): boolean {
  try {
    return statSync(p).isDirectory();
  } catch {
    return false;
  }
}

/**
 * Breadth-first walk of EVERY link/asset reachable from `seeds`, resolved with
 * gh-pages semantics against `ghPagesRoot`. Records dead links + missing assets +
 * the set of per-journey pages reached. Stays inside the temp tree.
 */
function walk(
  ghPagesRoot: string,
  outRoots: string[],
  seeds: string[],
): { brokenLinks: string[]; brokenAssets: string[]; reachedPages: Set<string> } {
  const queue = [...seeds];
  const visited = new Set<string>();
  const brokenLinks: string[] = [];
  const brokenAssets: string[] = [];
  const reachedPages = new Set<string>();
  const rootResolved = resolve(ghPagesRoot);

  while (queue.length) {
    const page = queue.shift()!;
    if (visited.has(page)) continue;
    visited.add(page);

    if (!existsSync(page)) {
      brokenLinks.push(`(walk seed) → missing page ${rel(ghPagesRoot, page)}`);
      continue;
    }
    const html = readFileSync(page, 'utf8');

    // (1) Anchors — page-to-page links, the report-dir link, the site-absolute
    //     back-index link, the footer full-archive link.
    for (const href of extractHrefs(html)) {
      if (isExternal(href) && !isSiteAbsolute(href)) continue;
      const { ok, target, page: linkedPage } = resolveLink(ghPagesRoot, outRoots, page, href);
      if (!ok) {
        brokenLinks.push(
          `${rel(ghPagesRoot, page)} → DEAD href "${href}" (${rel(ghPagesRoot, target)})`,
        );
        continue;
      }
      if (linkedPage && linkedPage.startsWith(rootResolved) && !visited.has(linkedPage)) {
        queue.push(linkedPage);
        if (/[\\/]J-\d+[a-z]?[\\/]index\.html$/.test(linkedPage)) reachedPages.add(linkedPage);
      }
    }

    // (2) Assets — every <img>/<video>/<source> the page references.
    for (const src of extractAssetSrcs(html)) {
      if (isExternal(src) && !isSiteAbsolute(src)) {
        brokenAssets.push(`${rel(ghPagesRoot, page)} → non-relative asset src "${src}"`);
        continue;
      }
      const { ok, target } = resolveLink(ghPagesRoot, outRoots, page, src);
      if (!ok) {
        brokenAssets.push(
          `${rel(ghPagesRoot, page)} → MISSING asset "${src}" (${rel(ghPagesRoot, target)})`,
        );
      }
    }
  }
  return { brokenLinks, brokenAssets, reachedPages };
}

/** Assert the declared media + maintainability reports are present under an out-root. */
function assertMediaPresent(galleryOut: string): void {
  const videosDir = resolve(galleryOut, 'videos');
  const shotsDir = resolve(galleryOut, 'screenshots');
  expect(readdirSync(videosDir).filter((f) => f.endsWith('.webm')).length).toBeGreaterThan(0);
  expect(readdirSync(shotsDir).filter((f) => f.endsWith('.png')).length).toBeGreaterThan(0);
  for (const f of MAINT_FILES) {
    expect(
      existsSync(resolve(galleryOut, 'maintainability', f)),
      `maintainability report ${f} present`,
    ).toBe(true);
  }
}

test.describe('proof-gallery link integrity (T-31/T-33)', () => {
  test('[happy] every gallery link/asset resolves in BOTH the canonical and branch-preview layouts', async () => {
    const g = await loadGenerators();
    const built = buildGallery(g);
    const { root, canonicalOut, previewOut, previewsIndex, canonicalPages, previewPages } = built;

    try {
      // Sanity: both layouts + the index built.
      expect(existsSync(previewsIndex), 'previews index was generated').toBe(true);
      expect(existsSync(resolve(canonicalOut, 'index.html')), 'canonical all-journeys page').toBe(
        true,
      );
      expect(
        existsSync(resolve(previewOut, 'index.html')),
        'branch-preview all-journeys page',
      ).toBe(true);
      // The branch-preview pages are one dir deeper than canonical (the depth the
      // bug lived at) — assert the layout so a regression to a shallower deploy is
      // caught. Path = <root>/alpenflight/proof-preview/<branch>/legacy-parity.
      expect(
        relative(root, previewOut).split(/[\\/]/),
        'branch-preview layout under proof-preview/<branch>/legacy-parity',
      ).toEqual(['alpenflight', 'proof-preview', SANITIZED_BRANCH, 'legacy-parity']);
      const pageJourneys = canonicalPages.map((p) => p.journey).sort();
      expect(pageJourneys, 'fixtures emit J-0 / J-0c / J-1 per-journey pages').toEqual([
        'J-0',
        'J-0c',
        'J-1',
      ]);

      // ── Walk BOTH layouts + the persistent index ───────────────────────────
      // Seed with the index pages the operator opens AND each layout's pages, so
      // the branch-preview cross-section links are walked even though the index
      // probes only one source per journey.
      const seeds = [
        previewsIndex,
        resolve(canonicalOut, 'index.html'),
        resolve(previewOut, 'index.html'),
        ...canonicalPages.map((p) => resolve(p.outFile)),
        ...previewPages.map((p) => resolve(p.outFile)),
      ];
      // Out-roots the generator wrote into (strict dir-index inside these only).
      const outRoots = [canonicalOut, previewOut, resolve(previewsIndex, '..')];
      const { brokenLinks, brokenAssets, reachedPages } = walk(root, outRoots, seeds);

      // ── Assertions ─────────────────────────────────────────────────────────
      expect(brokenLinks, `dead links:\n  - ${brokenLinks.join('\n  - ')}`).toEqual([]);
      expect(brokenAssets, `missing assets:\n  - ${brokenAssets.join('\n  - ')}`).toEqual([]);

      // Every journey-with-content must be REACHABLE from the previews index — the
      // index probes ONE source per journey (branch-preview first, then canonical),
      // so assert at-least-one layout's per-journey page for each journey id was
      // reached by following a link (no journey is unlinked/orphaned). The walk
      // over the seeded pages above already proves EVERY page's OWN links resolve.
      const reached = [...reachedPages].map((p) => resolve(p));
      const journeyIds = [...new Set(canonicalPages.map((p) => p.journey))];
      for (const jid of journeyIds) {
        const pages = [...canonicalPages, ...previewPages]
          .filter((p) => p.journey === jid)
          .map((p) => resolve(p.outFile));
        expect(
          pages.some((p) => reached.includes(p)),
          `journey ${jid}'s per-journey page reachable from the previews index`,
        ).toBe(true);
      }

      // Declared media + maintainability reports present under BOTH out-roots.
      assertMediaPresent(canonicalOut);
      assertMediaPresent(previewOut);
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  });

  // DEPLOYED MODE — runs only when GALLERY_DEPLOYED_URL is set (the fanout
  // POST-deploy step). Fetches the live gh-pages branch-preview previews index +
  // every per-journey page + asserts each link returns HTTP 200, polling briefly
  // (gh-pages lags a few seconds after deploy) before failing on any 404.
  const DEPLOYED = process.env['GALLERY_DEPLOYED_URL'];
  test('[deployed] live gh-pages gallery links return 200', async ({ request }) => {
    test.skip(!DEPLOYED, 'set GALLERY_DEPLOYED_URL to check the live gh-pages gallery');
    await walkDeployedWithRetry(request, DEPLOYED!);
  });
});

/**
 * Walk the live deployed gallery; gh-pages can take a few seconds to serve a
 * just-pushed file, so retry the whole walk for up to ~60s before failing.
 *
 * `baseUrl` = the deployed branch-preview root the fanout computes
 * (`…/proof-preview/<sanitized-branch>/`). The fanout deploys the gallery one
 * level under it (`legacy-parity/`), so the all-journeys page is at
 * `<base>legacy-parity/index.html`. Also seed the persistent previews index at
 * the SITE root (`/fls/alpenflight/previews/`, the back-link target + the
 * operator's bookmark) so the deployed walk asserts the cross-section link too.
 */
async function walkDeployedWithRetry(request: APIRequestContext, baseUrl: string): Promise<void> {
  const deadlineMs = Date.now() + 60_000;
  const start = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`;
  const origin = new URL(start).origin;
  const seeds = [
    new URL('legacy-parity/index.html', start).href,
    // The persistent previews index (site-root) — the back-link's target.
    new URL(`${SITE_BASE}alpenflight/previews/index.html`, origin).href,
  ];
  let broken: string[] = [];
  for (;;) {
    broken = await walkDeployed(request, seeds);
    if (broken.length === 0) return;
    if (Date.now() >= deadlineMs) break;
    await new Promise((r) => setTimeout(r, 5_000));
  }
  expect(broken, `live dead links (after retry):\n  - ${broken.join('\n  - ')}`).toEqual([]);
}

/** One pass over the live deployed gallery; returns the list of broken URLs. */
async function walkDeployed(request: APIRequestContext, seeds: string[]): Promise<string[]> {
  const seen = new Set<string>();
  // Seed: the entry pages + walk all links reachable from them.
  const queue = [...seeds];
  const broken: string[] = [];

  while (queue.length) {
    const url = queue.shift()!;
    if (seen.has(url)) continue;
    seen.add(url);

    const res = await request.get(url);
    if (!res.ok()) {
      broken.push(`${url} → ${res.status()}`);
      continue;
    }
    const ct = res.headers()['content-type'] ?? '';
    if (!ct.includes('html')) continue;

    const html = await res.text();
    for (const href of [...extractHrefs(html), ...extractAssetSrcs(html)]) {
      if (/^#/.test(href)) continue;
      // new URL() resolves both relative AND site-root-absolute (`/fls/…`)
      // against the deployed origin — exactly how a browser would on gh-pages.
      const abs = new URL(href, url).href;
      if (seen.has(abs)) continue;
      if (/\.html$|\/$/.test(abs)) {
        queue.push(abs);
      } else {
        const r = await request.get(abs);
        if (!r.ok()) broken.push(`${url} → asset ${abs} (${r.status()})`);
        seen.add(abs);
      }
    }
  }
  return broken;
}

/** Pretty path relative to the temp root for readable failure messages. */
function rel(root: string, abs: string): string {
  const r = relative(root, abs);
  return r.startsWith('..') ? abs : r;
}
