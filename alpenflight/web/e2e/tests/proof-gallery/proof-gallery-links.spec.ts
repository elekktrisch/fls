import {
  copyFileSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
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
// J-7 T-12 — the committed per-journey expected-shots contract (the SHOTS-PRESENT
// guard's source of truth). See expected-shots.json's header.
const EXPECTED_SHOTS = resolve(PROOF_GALLERY, 'expected-shots.json');
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

/**
 * Stage a stub `index.html` for every per-run gallery declared in
 * `per-run-galleries.json` under the CANONICAL proof out-root — mirroring where the
 * fanout deploys them (`alpenflight/proof/<href>/index.html`). The all-journeys
 * page links these site-root-absolute (`/fls/alpenflight/proof/<href>`), so the
 * link target lives here regardless of which page (canonical or branch-preview)
 * referenced it. Only RELATIVE manifest hrefs (the deployed-under-canonical ones)
 * are staged; absolute/external hrefs are deploys we don't model.
 */
function stagePerRunGalleries(canonicalOut: string): void {
  const manifestPath = resolve(PROOF_GALLERY, 'per-run-galleries.json');
  if (!existsSync(manifestPath)) return;
  let galleries: { href?: string }[];
  try {
    galleries = JSON.parse(readFileSync(manifestPath, 'utf8')).galleries ?? [];
  } catch {
    return;
  }
  for (const gal of galleries) {
    const href = gal?.href;
    if (!href || /^(?:[a-z]+:|\/\/|\/)/i.test(href)) continue;
    const dir = resolve(canonicalOut, href);
    mkdirSync(dir, { recursive: true });
    writeFileSync(resolve(dir, 'index.html'), '<!doctype html><title>per-run gallery stub</title>');
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
    // renderNav ON: the all-journeys page's "Per-run proof galleries" block
    // (per-run-galleries.json → `j-0c-fanout/`) is exactly one of the 3 T-35 dead
    // links. With nav OFF (the old default) the walk never saw it. The per-run
    // hrefs are site-root-absolute (T-35 fix) → they must resolve against the
    // staged canonical `proof/j-0c-fanout/` page below in BOTH layouts.
    renderNav: true,
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

  // The per-run galleries (per-run-galleries.json) are deployed by the fanout ONLY
  // under the canonical `alpenflight/proof/` (e.g. `proof/j-0c-fanout/index.html`),
  // never under a branch preview. The all-journeys page's nav block links them
  // site-root-absolute (T-35) so the link resolves from BOTH the canonical and the
  // branch-preview page. Stage a stub `index.html` for each so the walk can follow
  // the site-absolute link to a served page (mirrors the deployed canonical layout);
  // if the generator regresses to a RELATIVE `j-0c-fanout/`, the branch-preview
  // page's relative resolution lands on a non-existent dir → the walk reports the
  // dead link (the T-35 regression guard).
  stagePerRunGalleries(canonicalOut);

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

// ─── J-7 T-12: SHOTS-PRESENT contract helpers ──────────────────────────────────

type ExpectedShotsContract = Record<
  string,
  {
    expected?: string[];
    pending?: string[];
    /**
     * Optional per-shot producing-context map (J-7 T-16). A shot is produced in
     * exactly one CI context: `proof` (the per-push `alpenflight-proof` job) or
     * `fanout` (the nightly/dispatch fanout legacy→migrate→real chain). An
     * `expected` shot is only enforced when its producing context matches the
     * guard's current `GALLERY_PROOF_CONTEXT`; in the OTHER context it is
     * produced-elsewhere → tolerated-absent. An `expected` shot with NO entry
     * here is enforced in BOTH contexts (the migration journeys whose AF shots
     * the fanout AND the per-push both produce).
     */
    producedBy?: Record<string, 'proof' | 'fanout'>;
  }
>;

/** Load + validate the committed per-journey expected-shots contract. */
function loadExpectedShots(): ExpectedShotsContract {
  expect(existsSync(EXPECTED_SHOTS), `expected-shots.json present at ${EXPECTED_SHOTS}`).toBe(true);
  const parsed = JSON.parse(readFileSync(EXPECTED_SHOTS, 'utf8')) as {
    journeys?: ExpectedShotsContract;
  };
  return parsed.journeys ?? {};
}

/**
 * The set of `<side>:<view>` keys actually STAGED for the gallery — read from the
 * staged `screenshots.json` sidecar (what add_shot/add_pair declared + the
 * generator will render). A missing dir/sidecar yields an empty set (so an
 * EXPECTED shot then reds, which is the point — a wholly-unstaged journey is a
 * thin page).
 */
function loadStagedShotKeys(shotsDir: string): Set<string> {
  const keys = new Set<string>();
  const sidecar = resolve(shotsDir, 'screenshots.json');
  if (!existsSync(sidecar)) return keys;
  let decl: { screenshots?: { side?: string; view?: string; file?: string }[] };
  try {
    decl = JSON.parse(readFileSync(sidecar, 'utf8'));
  } catch {
    return keys;
  }
  for (const s of decl.screenshots ?? []) {
    // Only count an entry whose PNG is actually on disk — a declared-but-absent
    // file would fail the generator's own AC5 check, but counting it here would
    // mask the drop the guard exists to catch.
    if (s.file && existsSync(resolve(shotsDir, s.file)) && s.side && s.view) {
      keys.add(`${s.side}:${s.view}`);
    }
  }
  return keys;
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
    // gh-pages 404s ANY bare directory with no index.html — whether it's inside a
    // gallery out-root (e.g. a maintainability/ dir whose JSON/XML deployed but no
    // index.html emitted) OR a dir the gallery merely LINKS to but doesn't own.
    // The 3 T-35 dead links were ALL of the latter class: a relative `../` resolving
    // to the bare `proof-preview/` parent, a relative `j-0c-fanout/`, and a relative
    // `../../previews/` — each a directory that exists on gh-pages but serves 404
    // because no index.html sits in it. So modelling "dir → needs index.html"
    // UNIFORMLY (not just inside out-roots) is what makes the local walk reproduce
    // the deployed 404s. `isInsideOutRoot` is kept only to sharpen the failure
    // message (gallery-owned vs linked-out).
    void isInsideOutRoot(outRoots, abs);
    return { ok: false, target: abs };
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
      // Fixtures emit content pages for J-0 / J-0c / J-1; T-01b additionally
      // emits a PENDING placeholder page for the journey-under-work (J-5 here)
      // even with zero captures — the operator's "proof window exists from the
      // start" scaffold. So J-5's per-journey page must be present + reachable
      // (and dead-link-free) BEFORE any J-5 capture lands.
      const pageJourneys = canonicalPages.map((p) => p.journey).sort();
      expect(
        pageJourneys,
        'fixtures emit J-0 / J-0c / J-1 content pages + the J-5 journey-under-work scaffold',
      ).toEqual(['J-0', 'J-0c', 'J-1', 'J-5']);
      // The J-5 scaffold is a genuine pending placeholder (no green proof yet),
      // not a broken/empty page.
      const j5Scaffold = canonicalPages.find((p) => p.journey === 'J-5');
      expect(j5Scaffold, 'J-5 journey-under-work scaffold page emitted').toBeTruthy();
      expect(readFileSync(j5Scaffold!.outFile, 'utf8')).toContain('No green proof yet');

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

  // SHOTS-PRESENT MODE (J-7 T-12) — the PRE-deploy structural guard. The
  // add_shot/add_pair staging steps silently DROP a missing PNG (graceful
  // degrade), so a partial-red capture could ship a THIN per-journey page while
  // the generator stayed green (operator grill, J-5/J-6 retro). This guard reads
  // the committed EXPECTED-shots contract (`expected-shots.json`) + the STAGED
  // `screenshots.json` sidecar and FAILS if any `expected` <side>:<view> for the
  // journey-under-work is absent from the stage. `pending` shots (deferred to the
  // fanout legacy capture / a later thicken task) are TOLERATED but surfaced.
  // Runs only when GALLERY_EXPECT_JOURNEY is set (the per-push + fanout pre-deploy
  // step); GALLERY_SHOTS_DIR points at the staged --screenshots dir.
  const EXPECT_JOURNEY = process.env['GALLERY_EXPECT_JOURNEY'];
  test('[shots-present] journey-under-work expected paired shots are staged (no silent drop)', async () => {
    test.skip(!EXPECT_JOURNEY, 'set GALLERY_EXPECT_JOURNEY to assert a journey’s expected shots');
    const jid = EXPECT_JOURNEY!;
    const contract = loadExpectedShots();
    const entry = contract[jid];
    // A journey absent from the contract is intentionally UN-guarded (no enforced
    // shots) — pass cleanly so adding a journey to the staging steps without an
    // expected-shots entry never reds (the contract is opt-in per journey).
    test.skip(!entry, `journey ${jid} is not in expected-shots.json — no shots guarded`);

    const allExpected = entry!.expected ?? [];
    const pending = entry!.pending ?? [];
    const producedBy = entry!.producedBy ?? {};

    // PER-CONTEXT enforcement (J-7 T-16). The SAME guard runs in two CI contexts
    // — the per-push `proof` job (ci.yml) and the `fanout` chain — but a shot is
    // produced in exactly ONE of them. GALLERY_PROOF_CONTEXT names the current
    // context; an `expected` shot is enforced here only when its `producedBy`
    // context matches (or it has no producedBy tag → enforced in BOTH, the
    // migration journeys both contexts produce). A shot produced in the OTHER
    // context is tolerated-absent here (it is guarded where it is produced), so a
    // read-side journey's proof-only shots don't false-red the fanout. Unset
    // context (local self-run) enforces every `expected` shot — strictest.
    const context = process.env['GALLERY_PROOF_CONTEXT'];
    const enforced = allExpected.filter((k) => {
      const pc = producedBy[k];
      return !context || !pc || pc === context;
    });
    const otherContext = allExpected.filter((k) => !enforced.includes(k));

    // The staged screenshots.json sidecar declares what the gallery WILL render.
    // GALLERY_SHOTS_DIR is the --screenshots dir CI staged; default to the
    // committed fixtures dir for a local self-run.
    const shotsDir = process.env['GALLERY_SHOTS_DIR'] || SCREENSHOTS_DIR;
    const staged = loadStagedShotKeys(shotsDir);

    const missing = enforced.filter((k) => !staged.has(k));
    expect(
      missing,
      `journey ${jid}: EXPECTED paired shots absent from the stage (${shotsDir}` +
        `${context ? `, context=${context}` : ''}) — a silent add_shot/add_pair drop would have ` +
        `shipped a thin gallery page:\n  - ${missing.join('\n  - ')}` +
        `\nstaged: [${[...staged].sort().join(', ')}]`,
    ).toEqual([]);

    // Surface (not fail) the shots deferred to the OTHER context + the pending
    // shots so the operator sees what's tolerated-absent here rather than it
    // silently vanishing. The other-context shots ARE enforced — just in their
    // producing context's guard run, not this one.
    const stillPending = pending.filter((k) => !staged.has(k));
    if (otherContext.length) {
      console.log(
        `[shots-present] ${jid}: produced in another context (enforced there, tolerated here): ` +
          otherContext.join(', '),
      );
    }
    if (stillPending.length) {
      console.log(`[shots-present] ${jid}: pending (tolerated-absent): ${stillPending.join(', ')}`);
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

  // DEPLOYED-JOURNEY MODE (J-7 T-12) — the POST-deploy structural guard the
  // operator asked for ("a procedure rule kept failing, so this must be
  // structural"). The `[deployed]` walk above proves no DEAD links exist, but it
  // does NOT prove the JOURNEY-UNDER-WORK's bookmark row is actually PRESENT +
  // links a COMPLETE page — the exact failure mode that shipped ~4× this journey:
  // the generator test was green while the DEPLOYED page was wrong (wrong probe
  // path / freshest-wins linking the thinner page), so the journey read `pending`
  // on the bookmark or the page was missing its assets. This test, against the
  // LIVE deployed URLs, asserts:
  //   1. the journey-under-work's bookmark row on the persistent previews index
  //      is a LIVE LINK (resolves 200), not a `pending` placeholder; AND
  //   2. that journey's per-journey page declares ≥1 asset (video or shot) and
  //      EVERY declared asset (videos + paired screenshots) resolves 200.
  // Runs only when GALLERY_DEPLOYED_JOURNEY is set alongside GALLERY_DEPLOYED_URL.
  const DEPLOYED_JOURNEY = process.env['GALLERY_DEPLOYED_JOURNEY'];
  test('[deployed-journey] journey-under-work bookmark is a live link + its page assets resolve 200', async ({
    request,
  }) => {
    test.skip(
      !DEPLOYED || !DEPLOYED_JOURNEY,
      'set GALLERY_DEPLOYED_URL + GALLERY_DEPLOYED_JOURNEY to assert the journey’s live page',
    );
    await assertDeployedJourneyComplete(request, DEPLOYED!, DEPLOYED_JOURNEY!);
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
  let broken: string[];
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

/**
 * J-7 T-12 — POST-deploy journey-completeness guard. Against the LIVE deployed
 * gallery, prove the journey-under-work's bookmark row is a LIVE LINK on the
 * persistent previews index (not a `pending` placeholder) AND its per-journey
 * page declares ≥1 asset with EVERY declared asset (video + paired screenshot)
 * resolving HTTP 200. This is the catch the generator unit test can't make: the
 * deploy-path / probe-path can drift independently, so a green generator can ship
 * a `pending` bookmark or a thin page (the failure that recurred ~4×). Polls ~60s
 * for gh-pages to serve the freshly-pushed files.
 *
 * `baseUrl` = the deployed branch-preview PARENT (`…/proof-preview/<branch>/`),
 * same as the `[deployed]` walk consumes; the persistent previews index lives at
 * the SITE root (`/fls/alpenflight/previews/index.html`).
 */
async function assertDeployedJourneyComplete(
  request: APIRequestContext,
  baseUrl: string,
  journey: string,
): Promise<void> {
  const start = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`;
  const origin = new URL(start).origin;
  const indexUrl = new URL(`${SITE_BASE}alpenflight/previews/index.html`, origin).href;
  const deadlineMs = Date.now() + 60_000;

  let lastErr: string;
  for (;;) {
    lastErr = await tryAssertJourneyComplete(request, indexUrl, journey);
    if (lastErr === '') return;
    if (Date.now() >= deadlineMs) break;
    await new Promise((r) => setTimeout(r, 5_000));
  }
  expect(lastErr, `deployed journey-completeness check (after retry):\n  ${lastErr}`).toBe('');
}

/** One pass; returns '' on success or a human-readable failure reason. */
async function tryAssertJourneyComplete(
  request: APIRequestContext,
  indexUrl: string,
  journey: string,
): Promise<string> {
  const idxRes = await request.get(indexUrl);
  if (!idxRes.ok()) return `previews index ${indexUrl} → ${idxRes.status()}`;
  const idxHtml = await idxRes.text();

  // Find the journey's row. A LIVE row is `<a class="journey-link" href="…">
  // <strong>J-N</strong> proof page</a>`; a PENDING row is `<span
  // class="journey-link"><strong>J-N</strong></span>` (no href). Require the live
  // anchor form for the journey-under-work.
  const strongTag = `<strong>${journey}</strong>`;
  if (!idxHtml.includes(strongTag)) {
    return `journey ${journey} has NO row on the previews index ${indexUrl}`;
  }
  // The anchor immediately wraps the <strong> on a live row. Match an <a … href>
  // whose inner text starts with this journey's <strong>.
  const liveRe = new RegExp(
    `<a\\b[^>]*class="journey-link"[^>]*\\bhref="([^"]+)"[^>]*>\\s*<strong>${journey}<\\/strong>`,
    'i',
  );
  const m = liveRe.exec(idxHtml);
  if (!m) {
    return `journey ${journey} is on the index but its row is PENDING (no live link) — the deployed page is missing/thin`;
  }
  const pageUrl = new URL(m[1]!, indexUrl).href;

  const pageRes = await request.get(pageUrl);
  if (!pageRes.ok()) return `journey ${journey} page ${pageUrl} → ${pageRes.status()}`;
  const pageHtml = await pageRes.text();

  // The per-journey page must carry ≥1 asset (video or screenshot) — a page with
  // zero media is the "thin/pending-shaped" page the guard exists to reject (it
  // would otherwise pass the no-dead-links walk trivially).
  const assets = [...extractAssetSrcs(pageHtml)].filter((s) => !/^#/.test(s));
  if (assets.length === 0) {
    return `journey ${journey} page ${pageUrl} declares NO video/screenshot assets (thin page)`;
  }
  // Every declared asset must resolve 200.
  const broken: string[] = [];
  for (const src of assets) {
    const abs = new URL(src, pageUrl).href;
    const r = await request.get(abs);
    if (!r.ok()) broken.push(`${abs} (${r.status()})`);
  }
  if (broken.length) {
    return `journey ${journey} page ${pageUrl} has ${broken.length} asset(s) not resolving 200:\n    - ${broken.join('\n    - ')}`;
  }
  return '';
}
