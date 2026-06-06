#!/usr/bin/env node
/**
 * J-4 T-24 — persistent, bookmarkable PREVIEWS INDEX generator.
 *
 * The operator's problem: after T-21+T-22 the J-4 proof is spread across three
 * gh-pages galleries (clean-seed root, dashboard, /profile) + a fan-out
 * legacy-parity gallery, each with its own sticky PR comment — three competing
 * links, none of them a stable bookmark. The fix is ONE persistent landing page
 * at a STABLE URL the operator bookmarks ONCE:
 *
 *   https://elekktrisch.github.io/fls/alpenflight/previews/index.html
 *   (gh-pages path: alpenflight/previews/index.html)
 *
 * It lists every ACTIVE branch/PR preview under `alpenflight/proof-preview/<branch>/`
 * and, under each branch, links to whichever sub-galleries that branch has
 * published, each with a title + one-line purpose + last-updated. The per-job
 * galleries KEEP their own disjoint sub-paths (no path-conflict, no freshness
 * fight) — this index is just the single entry point.
 *
 * HOW IT SCANS (dependency-light: Node core only, no new deps):
 *   - Given the on-disk root of a gh-pages checkout (--gh-pages-root), it scans
 *     `<root>/alpenflight/proof-preview/<branch>/` for active branch subdirs.
 *   - For each branch it probes the THREE known sub-galleries by their on-disk
 *     `index.html`:
 *       root  index.html            → "Clean-seed tenant-isolation"  → <branch>/
 *       dashboard/index.html        → "Showcase /profile + role dashboards" → <branch>/dashboard/
 *       profile/index.html          → "Showcase /profile self-edit"  → <branch>/profile/
 *       legacy-parity/index.html    → "Paired legacy↔AlpenFlight (all journeys)" → <branch>/legacy-parity/
 *   - "last-updated" is read from the file's mtime (the gh-pages checkout's
 *     deploy timestamp). The optional --git mode reads `git log -1` of each
 *     file instead (a deploy commit timestamp), used by the CI step that has the
 *     gh-pages git history available; mtime is the dependency-free fallback.
 *   - It regenerates `<root>/alpenflight/previews/index.html`. Last-writer-wins
 *     is correct because the index is FULLY regenerated from a fresh scan each
 *     time — concurrent per-branch deploys touch disjoint dirs, and whoever
 *     rebuilds the shared index last simply re-scans the union on disk.
 *
 * Styling matches generate-gallery.mjs (the same CSS tokens / flat ADR-0024
 * look) so the landing page and the galleries it links read as one system.
 *
 * Dual use:
 *   - CLI:    node generate-previews-index.mjs --gh-pages-root <dir> [--git] [--branch-label <ref>]
 *   - import: import { generatePreviewsIndex, scanPreviews } from './generate-previews-index.mjs'
 */
import { readdirSync, statSync, existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { dirname, resolve, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));

/**
 * The known sub-galleries a branch preview can carry, in display order. `key` is
 * the sub-dir under `proof-preview/<branch>/` ('' = the branch root itself);
 * `title` + `purpose` render in the index card; `href` is built per-branch.
 */
export const SUB_GALLERIES = [
  {
    key: 'profile',
    title: 'Showcase /profile self-edit',
    purpose:
      'Real-idp PILOT self-edit — Account / Personal / Pilot / Notifications tabs (per-push, required gate).',
  },
  {
    key: 'dashboard',
    title: 'Showcase role dashboards',
    purpose:
      'Real-idp /start dashboard variants — pilot / club-admin / sysadmin, showcase-seeded (per-push).',
  },
  {
    key: '',
    title: 'Clean-seed tenant-isolation',
    purpose: 'Real-idp two-club Locations tenant-isolation — the J-0 vertical proof (per-push).',
  },
  {
    key: 'legacy-parity',
    title: 'Paired legacy ↔ AlpenFlight (all journeys)',
    purpose:
      'Fan-out: legacy → migrate + Keycloak → AlpenFlight parity videos + paired screenshots (fanout dispatch).',
  },
];

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
 * Scan a gh-pages checkout root for active branch previews + their sub-galleries.
 * Pure-ish (filesystem-in): returns
 *   [{ branch, updated: Date|null, galleries: [{ key, title, purpose, href, updated }] }]
 * sorted newest-branch-first. A branch with NO probe-able sub-gallery index.html
 * is dropped (a stale/empty dir is not an "active preview").
 */
export function scanPreviews(ghPagesRoot, { git = false } = {}) {
  const previewRoot = resolve(ghPagesRoot, 'alpenflight', 'proof-preview');
  if (!existsSync(previewRoot)) return [];
  const gitRoot = git ? resolve(ghPagesRoot) : undefined;

  const branches = [];
  for (const entry of readdirSync(previewRoot, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    const branch = entry.name;
    const branchDir = join(previewRoot, branch);
    const galleries = [];
    let newest = null;

    for (const sub of SUB_GALLERIES) {
      const galleryDir = sub.key ? join(branchDir, sub.key) : branchDir;
      const indexFile = join(galleryDir, 'index.html');
      if (!existsSync(indexFile)) continue;
      const updated = lastUpdated(indexFile, { git, gitRoot });
      if (updated && (!newest || updated > newest)) newest = updated;
      // Href is relative to the previews/index.html location
      // (alpenflight/previews/index.html → ../proof-preview/<branch>/<key>/).
      const href = `../proof-preview/${branch}/${sub.key ? `${sub.key}/` : ''}`;
      galleries.push({ key: sub.key, title: sub.title, purpose: sub.purpose, href, updated });
    }

    if (galleries.length === 0) continue; // not an active preview
    branches.push({ branch, updated: newest, galleries });
  }

  // Newest branch first (by its newest gallery); tie-break by name for stability.
  branches.sort((a, b) => {
    const ta = a.updated ? a.updated.getTime() : 0;
    const tb = b.updated ? b.updated.getTime() : 0;
    if (tb !== ta) return tb - ta;
    return a.branch.localeCompare(b.branch);
  });
  return branches;
}

/** Render the full standalone index.html. Style mirrors generate-gallery.mjs. */
export function renderIndexHtml(branches, { generatedAt }) {
  const sections = branches.length
    ? branches
        .map((b) => {
          const rows = b.galleries
            .map((g) => {
              const when = fmtWhen(g.updated);
              const whenSpan = when
                ? `<span class="updated">updated ${esc(when)}</span>`
                : '<span class="updated">updated —</span>';
              return `          <li class="proof-row">
            <a class="proof-link" href="${esc(g.href)}"><strong>${esc(g.title)}</strong></a>
            <span class="purpose">${esc(g.purpose)}</span>
            ${whenSpan}
          </li>`;
            })
            .join('\n');
          const branchWhen = fmtWhen(b.updated);
          const branchMeta = branchWhen
            ? `<span class="branch-updated">last activity ${esc(branchWhen)}</span>`
            : '';
          return `      <details class="branch" open>
        <summary>
          <span class="branch-name">${esc(b.branch)}</span>
          <span class="branch-count">${b.galleries.length} proof${b.galleries.length === 1 ? '' : 's'}</span>
          ${branchMeta}
        </summary>
        <ul class="proof-list">
${rows}
        </ul>
      </details>`;
        })
        .join('\n')
    : `      <p class="empty">No active branch previews right now. A per-push proof run on an open PR publishes its galleries here; this page rebuilds itself on every deploy.</p>`;

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>AlpenFlight proof previews — index</title>
<style>
:root {
  --bg: #f7f8fa; --surface: #ffffff; --surface-2: #eef1f4;
  --text: #1a1d21; --muted: #5c6470; --border: #e3e6ea;
  --primary: #0a66c2; --primary-hover: #0552a3;
  --success: #0a7f3f; --success-bg: #e3f5ea;
  --shadow: 0 1px 2px rgba(0,0,0,.04), 0 1px 3px rgba(0,0,0,.06);
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #0d1117; --surface: #161b22; --surface-2: #1c222b;
    --text: #e6edf3; --muted: #8d96a0; --border: #2a313b;
    --primary: #4da3ff; --primary-hover: #79bbff;
    --success: #3fb950; --success-bg: #14271b;
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
.branch {
  background: var(--surface); border: 1px solid var(--border);
  border-radius: 0; padding: 0; margin: 0 0 -1px;
}
.branch + .branch { margin-top: 0; }
.branch > summary {
  list-style: none; cursor: pointer; user-select: none;
  display: flex; align-items: center; gap: .6rem; flex-wrap: wrap;
  padding: .9rem 1.1rem .9rem 2.1rem; position: relative;
  background: var(--surface);
  border-left: 2px solid transparent;
}
.branch > summary::-webkit-details-marker { display: none; }
.branch > summary::before {
  content: ''; position: absolute; left: 1rem; top: 1.25rem;
  width: 0; height: 0;
  border-left: 5px solid var(--muted);
  border-top: 4px solid transparent; border-bottom: 4px solid transparent;
  transform: rotate(0deg); transform-origin: 25% 50%;
}
.branch[open] > summary::before { transform: rotate(90deg); }
.branch[open] > summary { border-left-color: var(--primary); }
.branch > summary:hover { background: var(--surface-2); }
.branch-name { font-weight: 500; font-size: 1.05rem; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
.branch-count { display: inline-block; padding: .2em .65em; border-radius: 4px; font-size: .8rem; font-weight: 500; background: var(--success-bg); color: var(--success); }
.branch-updated { color: var(--muted); font-size: .8rem; }
.proof-list { list-style: none; margin: 0; padding: .25rem 1.1rem 1.1rem; }
.proof-row { display: flex; flex-direction: column; gap: .15rem; padding: .65rem .8rem; border: 1px solid var(--border); border-radius: 4px; margin-top: .6rem; background: var(--surface-2); }
.proof-link { font-size: 1rem; }
.purpose { color: var(--muted); font-size: .85rem; }
.updated { color: var(--muted); font-size: .78rem; }
.empty { padding: 1.25rem; border: 1px dashed var(--border); border-radius: 4px; background: var(--surface); }
footer { margin-top: 3rem; color: var(--muted); font-size: .85em; }
</style>
</head>
<body>
<div class="container">
  <header>
    <h1>AlpenFlight proof previews</h1>
    <p class="meta">
      The single, permanent entry point to every active branch/PR proof gallery.
      Generated <code>${esc(generatedAt)}</code> — rebuilt on every proof deploy.
    </p>
    <p class="bookmark">
      <strong>Bookmark this page.</strong> Its URL never changes
      (<code>/alpenflight/previews/index.html</code>); always come back here to
      find the current proofs. The per-branch galleries below come and go as PRs
      open and close.
    </p>
  </header>

  <section>
${sections}
  </section>

  <footer>
    Each linked gallery is a live <code>real-idp</code> (or fan-out) proof run published to
    its own per-branch sub-path under <code>alpenflight/proof-preview/&lt;branch&gt;/</code>.
    Branches drop off this index when their PR closes.
  </footer>
</div>
</body>
</html>
`;
}

/**
 * Scan + regenerate the previews index in place under a gh-pages checkout root.
 * Writes `<ghPagesRoot>/alpenflight/previews/index.html`.
 * @returns {{ outFile: string, branches: Array, html: string }}
 */
export function generatePreviewsIndex({ ghPagesRoot, git = false }) {
  const branches = scanPreviews(ghPagesRoot, { git });
  const html = renderIndexHtml(branches, { generatedAt: new Date().toISOString() });
  const outDir = resolve(ghPagesRoot, 'alpenflight', 'previews');
  mkdirSync(outDir, { recursive: true });
  const outFile = resolve(outDir, 'index.html');
  writeFileSync(outFile, html, 'utf8');
  return { outFile, branches, html };
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--gh-pages-root') out.ghPagesRoot = argv[++i];
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
    const { outFile, branches } = generatePreviewsIndex({
      ghPagesRoot: args.ghPagesRoot,
      git: args.git === true,
    });
    const total = branches.reduce((n, b) => n + b.galleries.length, 0);
    console.log(`previews-index: wrote ${outFile}`);
    console.log(`  ${branches.length} active branch preview(s); ${total} linked gallery(ies).`);
  } catch (err) {
    console.error(`previews-index: ${err.message}`);
    process.exit(1);
  }
}
