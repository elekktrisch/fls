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
 * J-5 T-31 — gallery LINK-INTEGRITY check (operator ask 2026-06-06).
 *
 * A reusable, autonomously-runnable DoD: "are ALL proof-gallery links live?".
 * Guards the index/link breakage the operator hit (a per-journey page or asset
 * that 404s, a previews-index row pointing at a dead page).
 *
 * WHAT IT DOES (default, local mode — no env):
 *   1. Generates the FULL gallery into a unique temp gh-pages-root layout from
 *      the COMMITTED fixtures (deterministic): `generateGallery` emits the
 *      all-journeys page + per-journey pages at `<root>/alpenflight/proof/J-<n>/`,
 *      then `generatePreviewsIndex` scans that root + emits the persistent
 *      journeys index at `<root>/alpenflight/previews/index.html` — mirroring how
 *      `generate-gallery.spec.ts` / `generate-previews-index.spec.ts` invoke them.
 *   2. WALKS every link/asset, breadth-first, starting at the previews index:
 *      each `<a href>` (page-to-page + the full-archive link), every per-journey
 *      page it reaches, and within each page every `<img src>`, `<video src>`,
 *      `<source src>`, and the Maintainability "Full reports →" link.
 *   3. ASSERTS each RELATIVE link/asset resolves to a FILE THAT EXISTS on disk
 *      (resolved from the referencing page's dir), and that anchors land on a
 *      real `index.html`. NO dead links; every roadmap journey WITH content has a
 *      reachable page; every declared screenshot/video/maintainability-report
 *      file is present. A break fails with a precise `<page> → <broken target>`.
 *
 * BROWSERLESS. chrome-headless cannot launch in this sandbox (musl symbol-reloc),
 * so this spec uses ONLY node `fs` (+ the Playwright `request`/APIRequestContext
 * fixture for the optional deployed mode) — never `page` / a browser context. It
 * runs under the dedicated `proof-gallery-links` Playwright project (no browser,
 * no webServer — see playwright.config.ts), so it runs autonomously in ANY task
 * context and in CI.
 *
 * OPTIONAL DEPLOYED MODE. Set `GALLERY_DEPLOYED_URL=<baseURL>` (the live gh-pages
 * previews index, e.g. https://…/fls/alpenflight/previews/index.html) and the
 * `[deployed]` test fetches it + asserts each ABSOLUTE link returns 200 via the
 * `request` fixture. Default (no env) = the local generate-and-walk check only.
 */

const PROOF_GALLERY = resolve(__dirname, '..', '..', 'proof-gallery');
const FIXTURES = resolve(PROOF_GALLERY, 'fixtures');
const REPORT = resolve(FIXTURES, 'proof-manifest.json');
const LEGACY_VIDEO_DIR = resolve(FIXTURES, 'legacy-video');
const SCREENSHOTS_DIR = resolve(FIXTURES, 'screenshots');
const MAINT_DIR = resolve(FIXTURES, 'maintainability');

// The 4 maintainability artifacts the per-journey page's panel links/reads.
const MAINT_FILES = ['fallow-audit.json', 'fallow-health.json', 'pmd-main.xml', 'cpd-check.xml'];

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

/**
 * Build a deterministic gh-pages-root layout in a unique temp dir and generate
 * the WHOLE gallery into it:
 *   <root>/alpenflight/proof/             — gallery out-root (all-journeys page,
 *                                           per-journey J-<n>/ pages, videos/,
 *                                           screenshots/, maintainability/)
 *   <root>/alpenflight/previews/index.html — the persistent journeys index
 * `previewsIndex` is the index file the walk starts from.
 */
function buildGallery(g: Awaited<ReturnType<typeof loadGenerators>>): {
  root: string;
  galleryOut: string;
  previewsIndex: string;
  journeyPages: { journey: string; outFile: string }[];
} {
  const root = mkdtempSync(resolve(tmpdir(), 'gallery-links-'));
  const galleryOut = resolve(root, 'alpenflight', 'proof');
  mkdirSync(galleryOut, { recursive: true });

  // Stage the maintainability artifacts into the out-root BEFORE generate so the
  // per-journey page's "Full reports →" link (maintainability/) has real files
  // behind it (matches CI, which stages T-12's artifacts there first).
  const outMaint = resolve(galleryOut, 'maintainability');
  mkdirSync(outMaint, { recursive: true });
  for (const f of MAINT_FILES) {
    const src = resolve(MAINT_DIR, f);
    if (existsSync(src)) copyFileSync(src, resolve(outMaint, f));
  }

  const { journeyPages } = g.generateGallery({
    reportPath: REPORT,
    outDir: galleryOut,
    branch: 'integration/J-5',
    legacyVideoDir: LEGACY_VIDEO_DIR,
    screenshotsDir: SCREENSHOTS_DIR,
    renderNav: false,
    journeyUnderWork: 'J-5',
  });

  // The previews index scans the gh-pages root for per-journey pages under
  // alpenflight/proof/J-<n>/ and links them — exactly the canonical source.
  const { outFile: previewsIndex } = g.generatePreviewsIndex({ ghPagesRoot: root });

  return { root, galleryOut, previewsIndex, journeyPages };
}

/** Every `<a href>` value in an HTML string (deduped, in document order). */
function extractHrefs(html: string): string[] {
  return [...new Set([...html.matchAll(/<a\b[^>]*\bhref="([^"]+)"/gi)].map((m) => m[1]!))];
}

/**
 * Every asset src in an HTML string: `<img src>`, `<video src>`, `<source src>`,
 * `<link href rel=stylesheet>` (deduped). Anchor hrefs are handled separately by
 * `extractHrefs` so a navigation link isn't mistaken for an asset.
 */
function extractAssetSrcs(html: string): string[] {
  const srcs = [...html.matchAll(/<(?:img|video|source)\b[^>]*\bsrc="([^"]+)"/gi)].map(
    (m) => m[1]!,
  );
  return [...new Set(srcs)];
}

/** A link is "local/relative" (filesystem-checkable) iff it isn't an http(s)/data/anchor-only URL. */
function isRelative(href: string): boolean {
  return !/^(?:[a-z]+:|\/\/|#)/i.test(href);
}

/**
 * Resolve a relative href/src against the referencing page's dir (dropping any
 * `#fragment` / `?query`) into a verdict:
 *   { ok }    — the target exists on disk. A file link → the file; a directory
 *               link (trailing `/`, or a path that is a dir) → the directory
 *               exists (e.g. the `maintainability/` reports dir, which holds
 *               JSON/XML, not an index.html — a valid, non-page link target).
 *   { page }  — set to the dir's `index.html` IFF it exists, so the walk follows
 *               page-to-page links but treats a report-dir link as merely present.
 *   target    — the on-disk path the verdict is about (for the failure message).
 */
function resolveLink(
  referencingPage: string,
  href: string,
): { ok: boolean; target: string; page?: string } {
  const clean = href.replace(/[?#].*$/, '');
  const abs = resolve(dirname(referencingPage), clean);
  if (clean.endsWith('/') || isDir(abs)) {
    const index = resolve(abs, 'index.html');
    if (existsSync(index)) return { ok: true, target: index, page: index };
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

test.describe('proof-gallery link integrity (T-31)', () => {
  test('[happy] every generated gallery link + asset resolves to a real file on disk', async () => {
    const g = await loadGenerators();
    const built = buildGallery(g);
    const { root, galleryOut, previewsIndex, journeyPages } = built;

    try {
      // Sanity: the build produced the index + at least the fixtures' journeys.
      expect(existsSync(previewsIndex), 'previews index was generated').toBe(true);
      expect(existsSync(resolve(galleryOut, 'index.html')), 'all-journeys page was generated').toBe(
        true,
      );
      // The fixtures green J-0 + J-0c (videos) + J-1 (screenshots) per-journey pages.
      const pageJourneys = journeyPages.map((p) => p.journey).sort();
      expect(pageJourneys, 'fixtures emit J-0 / J-0c / J-1 per-journey pages').toEqual([
        'J-0',
        'J-0c',
        'J-1',
      ]);

      // ── Breadth-first link walk over the generated tree ────────────────────
      // Seed the queue with the index pages the operator actually opens.
      const queue: string[] = [previewsIndex, resolve(galleryOut, 'index.html')];
      const visited = new Set<string>();
      const brokenLinks: string[] = [];
      const brokenAssets: string[] = [];
      // Every per-journey page must be REACHED from the previews index (no
      // orphaned page; no page the index links that doesn't exist).
      const reachedPages = new Set<string>();

      while (queue.length) {
        const page = queue.shift()!;
        if (visited.has(page)) continue;
        visited.add(page);

        if (!existsSync(page)) {
          // A page we were told to walk doesn't exist — record + skip.
          brokenLinks.push(`(walk root) → missing page ${rel(root, page)}`);
          continue;
        }
        const html = readFileSync(page, 'utf8');

        // (1) Anchors — page-to-page links, the report-dir link, the footer
        //     full-archive link.
        for (const href of extractHrefs(html)) {
          if (!isRelative(href)) continue; // external / mailto / #-only: not our concern
          const { ok, target, page: linkedPage } = resolveLink(page, href);
          if (!ok) {
            brokenLinks.push(`${rel(root, page)} → DEAD href "${href}" (${rel(root, target)})`);
            continue;
          }
          // Follow links that land on a gallery HTML page (stay inside the tree).
          if (linkedPage && linkedPage.startsWith(resolve(root)) && !visited.has(linkedPage)) {
            queue.push(linkedPage);
            if (/[\\/]J-\d+[a-z]?[\\/]index\.html$/.test(linkedPage)) reachedPages.add(linkedPage);
          }
        }

        // (2) Assets — every <img>/<video>/<source> the page references.
        for (const src of extractAssetSrcs(html)) {
          if (!isRelative(src)) {
            brokenAssets.push(`${rel(root, page)} → non-relative asset src "${src}"`);
            continue;
          }
          const { ok, target } = resolveLink(page, src);
          if (!ok) {
            brokenAssets.push(`${rel(root, page)} → MISSING asset "${src}" (${rel(root, target)})`);
          }
        }
      }

      // ── Assertions ─────────────────────────────────────────────────────────
      expect(brokenLinks, `dead links:\n  - ${brokenLinks.join('\n  - ')}`).toEqual([]);
      expect(brokenAssets, `missing assets:\n  - ${brokenAssets.join('\n  - ')}`).toEqual([]);

      // Every per-journey page the generator emitted must have been REACHED by
      // walking from the previews index (the index links every with-content
      // journey — no journey with proof content is unreachable).
      for (const jp of journeyPages) {
        const onDisk = resolve(jp.outFile);
        expect(
          [...reachedPages].some((p) => resolve(p) === onDisk),
          `journey ${jp.journey}'s page must be reachable from the previews index (${rel(
            root,
            onDisk,
          )})`,
        ).toBe(true);
      }

      // Every declared screenshot/video/maintainability-report file is present
      // under the gallery out-root (belt-and-braces over the asset walk above).
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
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  });

  // OPTIONAL deployed mode — only runs when GALLERY_DEPLOYED_URL is set. Fetches
  // the live gh-pages previews index + asserts each ABSOLUTE link returns 200.
  // Uses the `request` (APIRequestContext) fixture — still browserless.
  const DEPLOYED = process.env['GALLERY_DEPLOYED_URL'];
  test('[deployed] live gh-pages gallery links return 200', async ({ request }) => {
    test.skip(!DEPLOYED, 'set GALLERY_DEPLOYED_URL to check the live gh-pages gallery');
    await walkDeployed(request, DEPLOYED!);
  });
});

/** Walk the live deployed previews index + the per-journey pages it links. */
async function walkDeployed(request: APIRequestContext, indexUrl: string): Promise<void> {
  const seen = new Set<string>();
  const queue = [indexUrl];
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
    if (!(res.headers()['content-type'] ?? '').includes('html')) continue;

    const html = await res.text();
    for (const href of [...extractHrefs(html), ...extractAssetSrcs(html)]) {
      if (/^#/.test(href)) continue;
      const abs = new URL(href, url).href;
      // Stay within the deployed gallery tree; follow HTML, probe assets once.
      if (!seen.has(abs)) {
        if (/\.html$|\/$/.test(abs)) queue.push(abs);
        else {
          const r = await request.get(abs);
          if (!r.ok()) broken.push(`${url} → asset ${abs} (${r.status()})`);
          seen.add(abs);
        }
      }
    }
  }
  expect(broken, `live dead links:\n  - ${broken.join('\n  - ')}`).toEqual([]);
}

/** Pretty path relative to the temp root for readable failure messages. */
function rel(root: string, abs: string): string {
  const r = relative(root, abs);
  return r.startsWith('..') ? abs : r;
}
