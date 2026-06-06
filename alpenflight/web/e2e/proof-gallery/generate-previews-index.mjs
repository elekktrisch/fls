#!/usr/bin/env node
/**
 * J-5 T-13b — persistent, bookmarkable PROOF-PREVIEWS INDEX (the JOURNEY directory).
 *
 * The operator's standing landing page lives at ONE stable URL they bookmark once:
 *
 *   https://elekktrisch.github.io/fls/alpenflight/previews/index.html
 *   (gh-pages path: alpenflight/previews/index.html)
 *
 * RE-ARCH (T-13b). The original J-4 T-24 index was a per-ACTIVE-BRANCH directory:
 * it scanned `alpenflight/proof-preview/<branch>/` for OPEN-PR previews and probed
 * 4 fixed per-proof-type sub-galleries (clean-seed / dashboard / profile /
 * legacy-parity-all-journeys). That meant once J-0..J-4's PRs merged + closed,
 * `proof-preview-reap.yml` reaped their branch previews → the index showed only the
 * one still-open branch, and its links pointed at the all-in-one per-proof-type
 * galleries, not per-journey pages. The operator's complaint: "lists only J-5
 * instead of J-0…J-5, and links the old all-in-one galleries."
 *
 * The fix is to make this a PERSISTENT JOURNEY directory, driven off the roadmap +
 * the on-disk per-journey pages that SURVIVE PR close:
 *
 *   1. LIST JOURNEYS J-0…J-N (not branches), in roadmap order — parsed from
 *      `_ORDER.md` (reusing `generate-gallery.mjs`'s `parseRoadmapText`, the same
 *      authoritative parse the gallery uses), falling back to `ROADMAP_FALLBACK`.
 *   2. Each journey links its PER-JOURNEY page (`generate-gallery.mjs` T-13a emits
 *      one at `<gallery-out-root>/J-<n>/index.html`). The index probes, IN PRIORITY
 *      ORDER, the persistent + active on-disk locations of that page:
 *        a. active branch preview  alpenflight/proof-preview/<branch>/J-<n>/   (fresh, ephemeral)
 *        b. canonical (main)        alpenflight/proof/J-<n>/                    (persistent)
 *        c. fan-out archive         alpenflight/proof/legacy-parity/J-<n>/     (persistent; J-0…J-4 history)
 *      The FIRST that exists wins (prefer the freshest: branch > canonical >
 *      archive). A journey with no page anywhere renders a PENDING row — never a
 *      dead link (AC, mirrors the gallery's pending rows).
 *   3. The all-in-one per-proof-type galleries are no longer the things the operator
 *      clicks — they become SOURCES the per-journey pages assemble from. A single
 *      "full archive" link survives in the FOOTER for transition safety, but the
 *      PRIMARY listing is per-journey.
 *
 * PERSISTENCE. Because the per-journey pages live under `alpenflight/proof/`
 * (canonical, deployed `publish_dir: public` on main; and the fan-out archive at
 * `alpenflight/proof/legacy-parity/`), they are NOT under `alpenflight/proof-preview/`
 * — so `proof-preview-reap.yml` (which only ever rm's `proof-preview/<branch>/`)
 * leaves them intact when a PR closes. The index therefore shows J-0…J-N even after
 * every PR has merged. The active branch's freshly-built per-journey page is
 * additionally surfaced (priority a) while its PR is open.
 *
 * HOW IT SCANS (dependency-light: Node core only, no new deps):
 *   - Given the on-disk root of a gh-pages checkout (--gh-pages-root), and the
 *     active branch ref (--branch, default $GITHUB_REF_NAME), it builds the ordered
 *     journey list, then for each journey probes the three page locations above.
 *   - "last-updated" is read from the chosen page's mtime (the checkout's deploy
 *     timestamp). `--git` reads `git log -1` of the file instead (the deploy commit
 *     timestamp), used by the CI step that has gh-pages git history.
 *   - It regenerates `<root>/alpenflight/previews/index.html`. Fully-regenerated from
 *     a fresh scan each time → last-writer-wins is correct (concurrent per-branch
 *     deploys touch disjoint dirs; whoever rebuilds the shared index last re-scans
 *     the union on disk).
 *
 * Styling mirrors generate-gallery.mjs (same CSS tokens / flat ADR-0024 look) so the
 * landing page and the journey pages it links read as one system.
 *
 * Dual use:
 *   - CLI:    node generate-previews-index.mjs --gh-pages-root <dir> [--branch <ref>] [--git] [--order <_ORDER.md>]
 *   - import: import { generatePreviewsIndex, scanJourneys } from './generate-previews-index.mjs'
 */
import { statSync, existsSync, mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { dirname, resolve, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseRoadmapText, ROADMAP_FALLBACK } from './generate-gallery.mjs';

const __dirname = dirname(fileURLToPath(import.meta.url));

/**
 * The persistent + active on-disk sources of a journey's per-journey page, in
 * PRIORITY order (first hit wins). `base` is relative to the gh-pages checkout
 * root and (with `<jid>` appended) carries the `J-<n>/index.html` page T-13a emits.
 * `needsBranch` sources interpolate the active branch ref.
 *   - active branch preview: freshest while the PR is open; reaped on close.
 *   - canonical (main):      persistent — clean-seed per-journey pages.
 *   - fan-out archive:       persistent — legacy↔AlpenFlight parity per-journey
 *                            pages for ALL journeys (survives PR close; the J-0…J-4
 *                            history source).
 * `label` is shown on the live row so the operator knows which deploy they're seeing.
 *
 * T-37 — the branch-preview source MUST probe under `<branch>/legacy-parity/`, NOT
 * the parent `<branch>/`. The fanout (`alpenflight-proof-fanout.yml`) deploys the
 * gallery to `destination_dir: alpenflight/proof-preview/<branch>/legacy-parity`,
 * so the freshly-built per-journey pages live at
 * `proof-preview/<branch>/legacy-parity/J-<n>/`. Probing the parent level instead
 * (the old path) NEVER found the current pages — it linked whatever STALE pages an
 * older deploy scheme had left at the parent level (`keep_files:true` preserves
 * them), including a pre-T-32 J-0 page whose relative `../previews/` back-link 404s.
 * `subPath` is appended to `<base>/<branch>` for the on-disk probe + the href.
 */
export const JOURNEY_PAGE_SOURCES = [
  {
    id: 'branch',
    base: 'alpenflight/proof-preview',
    needsBranch: true,
    subPath: 'legacy-parity',
    label: 'branch preview',
  },
  { id: 'canonical', base: 'alpenflight/proof', needsBranch: false, label: 'published' },
  {
    id: 'archive',
    base: 'alpenflight/proof/legacy-parity',
    needsBranch: false,
    label: 'legacy-parity archive',
  },
];

/** The single "full archive" link kept in the footer (transition safety). */
export const FULL_ARCHIVE = {
  href: '../proof/',
  title: 'Full all-journeys gallery',
  purpose:
    'The canonical all-journeys gallery (every green proof video + paired legacy↔AlpenFlight screenshots, one page).',
};

function esc(s) {
  return String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/** ISO-ish "YYYY-MM-DD HH:MM UTC" for a Date (or null). */
function fmtWhen(d) {
  if (!d || Number.isNaN(d.getTime())) return null;
  const iso = d.toISOString();
  return `${iso.slice(0, 10)} ${iso.slice(11, 16)} UTC`;
}

/**
 * Last-updated for a file. `--git` mode reads the file's last gh-pages commit
 * date (the deploy commit); otherwise the on-disk mtime (the checkout's deploy
 * timestamp). Returns a Date or null.
 */
function lastUpdated(absFile, { git, gitRoot }) {
  if (git && gitRoot) {
    try {
      const rel = relative(gitRoot, absFile);
      const out = execFileSync('git', ['log', '-1', '--format=%cI', '--', rel], {
        cwd: gitRoot,
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'ignore'],
      }).trim();
      if (out) return new Date(out);
    } catch {
      /* fall through to mtime */
    }
  }
  try {
    return statSync(absFile).mtime;
  } catch {
    return null;
  }
}

/**
 * Resolve the ordered roadmap journey ids. Reuses `generate-gallery.mjs`'s
 * `parseRoadmapText` (the authoritative parse the gallery uses), reading
 * `_ORDER.md` from `orderPath` (or the repo location relative to this script).
 * Falls back to `ROADMAP_FALLBACK` if _ORDER.md is unreachable/empty.
 */
export function resolveRoadmap(orderPath) {
  let path = orderPath;
  if (!path) {
    const guess = resolve(
      __dirname,
      '..',
      '..',
      '..',
      '..',
      'docs',
      'modernization',
      'stories',
      '_ORDER.md',
    );
    path = existsSync(guess) ? guess : undefined;
  }
  if (!path || !existsSync(path)) return ROADMAP_FALLBACK;
  const ids = parseRoadmapText(readFileSync(path, 'utf8'));
  return ids.length ? ids : ROADMAP_FALLBACK;
}

/**
 * Probe the on-disk per-journey page for one journey id, in source-priority
 * order. Returns the first hit as
 *   { found: true, href, source, label, updated }
 * (`href` is relative to the previews/index.html location), or
 *   { found: false }
 * if no page exists anywhere. `branch` is the active ref (for the branch-preview
 * source); a falsy branch simply skips the branch source.
 */
export function locateJourneyPage(ghPagesRoot, jid, { branch, git = false }) {
  const gitRoot = git ? resolve(ghPagesRoot) : undefined;
  for (const src of JOURNEY_PAGE_SOURCES) {
    if (src.needsBranch && !branch) continue;
    // On-disk base dir for this source: <base>[/<branch>][/<subPath>].
    let baseDir = resolve(ghPagesRoot, src.base);
    if (src.needsBranch) baseDir = join(baseDir, branch);
    if (src.subPath) baseDir = join(baseDir, src.subPath);
    const pageFile = join(baseDir, jid, 'index.html');
    if (!existsSync(pageFile)) continue;
    const updated = lastUpdated(pageFile, { git, gitRoot });
    // Href is relative to alpenflight/previews/index.html. The page lives at
    // <base>[/<branch>][/<subPath>]/<jid>/index.html → from previews/, that's
    // ../<base-without-leading-alpenflight>/[<branch>/][<subPath>/]<jid>/.
    const relParts = [src.base.replace(/^alpenflight\//, '')];
    if (src.needsBranch) relParts.push(branch);
    if (src.subPath) relParts.push(src.subPath);
    relParts.push(jid);
    const rel = `${relParts.join('/')}/`;
    return { found: true, href: `../${rel}`, source: src.id, label: src.label, updated };
  }
  return { found: false };
}

/**
 * Build the ordered journey listing for the index. Pure-ish (filesystem-in):
 * returns
 *   [{ jid, found, href?, source?, label?, updated? }]
 * one row per roadmap journey, in roadmap order. With-proof journeys carry a
 * live `href`; pending journeys carry `found: false` (no link). Never drops a
 * journey (so the directory always shows J-0…J-N).
 */
export function scanJourneys(ghPagesRoot, { branch, git = false, orderPath } = {}) {
  const roadmap = resolveRoadmap(orderPath);
  return roadmap.map((jid) => {
    const loc = locateJourneyPage(ghPagesRoot, jid, { branch, git });
    return { jid, ...loc };
  });
}

/** Render the full standalone index.html. Style mirrors generate-gallery.mjs. */
export function renderIndexHtml(journeys, { generatedAt, branch }) {
  const liveCount = journeys.filter((j) => j.found).length;

  const rows = journeys
    .map((j) => {
      if (j.found) {
        const when = fmtWhen(j.updated);
        const whenSpan = when
          ? `<span class="updated">updated ${esc(when)}</span>`
          : '<span class="updated">updated —</span>';
        return `        <li class="journey-row">
          <a class="journey-link" href="${esc(j.href)}"><strong>${esc(j.jid)}</strong> proof page</a>
          <span class="source">${esc(j.label)}</span>
          ${whenSpan}
        </li>`;
      }
      return `        <li class="journey-row pending">
          <span class="journey-link"><strong>${esc(j.jid)}</strong></span>
          <span class="source">pending</span>
          <span class="updated">no proof page yet</span>
        </li>`;
    })
    .join('\n');

  const branchNote = branch
    ? ` The active branch <code>${esc(branch)}</code>'s freshly-built per-journey pages are surfaced as soon as its PR deploy lands.`
    : '';

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>AlpenFlight proof — journeys index</title>
<style>
:root {
  --bg: #f7f8fa; --surface: #ffffff; --surface-2: #eef1f4;
  --text: #1a1d21; --muted: #5c6470; --border: #e3e6ea;
  --primary: #0a66c2; --primary-hover: #0552a3;
  --success: #0a7f3f; --success-bg: #e3f5ea;
  --pending: #8a6d00; --pending-bg: #fbf3d6;
  --shadow: 0 1px 2px rgba(0,0,0,.04), 0 1px 3px rgba(0,0,0,.06);
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #0d1117; --surface: #161b22; --surface-2: #1c222b;
    --text: #e6edf3; --muted: #8d96a0; --border: #2a313b;
    --primary: #4da3ff; --primary-hover: #79bbff;
    --success: #3fb950; --success-bg: #14271b;
    --pending: #d6a700; --pending-bg: #2a2410;
    --shadow: 0 1px 2px rgba(0,0,0,.4), 0 1px 3px rgba(0,0,0,.3);
  }
}
* { box-sizing: border-box; }
html, body { margin: 0; padding: 0; }
body {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", system-ui, "Helvetica Neue", Arial, sans-serif;
  background: var(--bg); color: var(--text); line-height: 1.5;
}
.container { max-width: 1100px; margin: 0 auto; padding: clamp(1rem, 3vw, 2.5rem) clamp(1rem, 3vw, 1.5rem); }
header { margin-bottom: 2rem; }
h1 { font-size: clamp(1.5rem, 4vw, 2.25rem); margin: 0 0 .5rem; letter-spacing: -.02em; }
p { margin: 0; color: var(--muted); }
code { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: .85em; background: var(--surface-2); padding: .1em .4em; border-radius: 4px; }
a { color: var(--primary); text-decoration: none; }
a:hover { color: var(--primary-hover); text-decoration: underline; }
.meta { color: var(--muted); font-size: .9rem; }
.bookmark { margin-top: .75rem; padding: .6rem .8rem; border: 2px solid var(--primary); border-radius: 4px; background: var(--surface-2); color: var(--text); font-size: .9rem; }
.journey-list { list-style: none; margin: 0; padding: 0; }
.journey-row {
  display: flex; align-items: baseline; gap: .8rem; flex-wrap: wrap;
  padding: .8rem 1.1rem; background: var(--surface);
  border: 1px solid var(--border); border-radius: 0; margin: 0 0 -1px;
  border-left: 2px solid var(--success);
}
.journey-row.pending { border-left-color: var(--border); opacity: .8; }
.journey-link { font-size: 1.02rem; }
.journey-row.pending .journey-link { color: var(--muted); }
.source { display: inline-block; padding: .2em .65em; border-radius: 4px; font-size: .78rem; font-weight: 500; background: var(--success-bg); color: var(--success); }
.journey-row.pending .source { background: var(--pending-bg); color: var(--pending); }
.updated { color: var(--muted); font-size: .78rem; margin-left: auto; }
footer { margin-top: 2.5rem; color: var(--muted); font-size: .85em; }
.archive-link { margin-top: 1.5rem; padding: .6rem .8rem; border: 1px solid var(--border); border-radius: 4px; background: var(--surface); }
</style>
</head>
<body>
<div class="container">
  <header>
    <h1>AlpenFlight proof — journeys</h1>
    <p class="meta">
      The single, permanent entry point — one row per modernization journey, each
      linking its per-journey proof page. Generated <code>${esc(generatedAt)}</code> —
      rebuilt on every proof deploy. ${liveCount} journey${liveCount === 1 ? '' : 's'} with a published proof page.
    </p>
    <p class="bookmark">
      <strong>Bookmark this page.</strong> Its URL never changes
      (<code>/alpenflight/previews/index.html</code>). Journeys persist here after
      their PR merges — the per-journey pages live under <code>alpenflight/proof/</code>
      and are never reaped.${branchNote}
    </p>
  </header>

  <section>
    <ul class="journey-list">
${rows}
    </ul>
    <p class="archive-link">
      &rarr; <a href="${esc(FULL_ARCHIVE.href)}"><strong>${esc(FULL_ARCHIVE.title)}</strong></a>
      — ${esc(FULL_ARCHIVE.purpose)}
    </p>
  </section>

  <footer>
    Each per-journey page is assembled from the green <code>real-idp</code> proof
    videos + paired legacy↔AlpenFlight screenshots for that journey, published under
    <code>alpenflight/proof/J-&lt;n&gt;/</code> (canonical) or
    <code>alpenflight/proof/legacy-parity/J-&lt;n&gt;/</code> (fan-out archive). These
    survive PR close — only the ephemeral <code>alpenflight/proof-preview/&lt;branch&gt;/</code>
    previews are reaped.
  </footer>
</div>
</body>
</html>
`;
}

/**
 * Scan + regenerate the previews index in place under a gh-pages checkout root.
 * Writes `<ghPagesRoot>/alpenflight/previews/index.html`.
 * @returns {{ outFile: string, journeys: Array, html: string }}
 */
export function generatePreviewsIndex({ ghPagesRoot, branch, git = false, orderPath }) {
  const journeys = scanJourneys(ghPagesRoot, { branch, git, orderPath });
  const html = renderIndexHtml(journeys, {
    generatedAt: new Date().toISOString(),
    branch,
  });
  const outDir = resolve(ghPagesRoot, 'alpenflight', 'previews');
  mkdirSync(outDir, { recursive: true });
  const outFile = resolve(outDir, 'index.html');
  writeFileSync(outFile, html, 'utf8');
  return { outFile, journeys, html };
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--gh-pages-root') out.ghPagesRoot = argv[++i];
    else if (a === '--branch') out.branch = argv[++i];
    else if (a === '--order') out.orderPath = argv[++i];
    else if (a === '--git') out.git = true;
  }
  return out;
}

// CLI entrypoint (only when run directly, not when imported).
if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  const args = parseArgs(process.argv.slice(2));
  if (!args.ghPagesRoot) {
    console.error('generate-previews-index: --gh-pages-root <dir> is required');
    process.exit(2);
  }
  try {
    const branch = args.branch ?? process.env.GITHUB_REF_NAME;
    const { outFile, journeys } = generatePreviewsIndex({
      ghPagesRoot: args.ghPagesRoot,
      branch,
      git: args.git === true,
      orderPath: args.orderPath,
    });
    const live = journeys.filter((j) => j.found).length;
    console.log(`previews-index: wrote ${outFile}`);
    console.log(
      `  ${journeys.length} roadmap journey(s); ${live} with a published per-journey page.`,
    );
  } catch (err) {
    console.error(`previews-index: ${err.message}`);
    process.exit(1);
  }
}
