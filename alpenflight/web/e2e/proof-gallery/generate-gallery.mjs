#!/usr/bin/env node
/**
 * J-24 proof-video gallery generator.
 *
 * Reads a Playwright JSON-reporter report (the "manifest" — see README.md for
 * the exact shape T-03 conforms to), pairs each passing proof test's
 * `proof-video` .webm with its `proof-caption`/`proof-ac-tag`/`proof-journey`
 * annotations, and emits a static `index.html` that renders one captioned
 * `<video controls>` per proof, grouped by journey. Roadmap journeys with no
 * green proof render a "pending" row (never a broken link / 404 — AC4).
 *
 * AC5 link-check (the [key-error] path — runs fully real, never mocked):
 * the generator throws (CLI: exits non-zero) if a published video has no
 * caption, or a caption references a .webm not present in the proof output.
 *
 * Dual use:
 *   - CLI:    node generate-gallery.mjs --report <json> --out <dir> [--order <_ORDER.md>] [--legacy-video <dir>] [--screenshots <dir>]
 *             (also: `pnpm proof:gallery`). `--legacy-video <dir>` adds declared
 *             legacy (non-AlpenFlight) parity videos from a `legacy-video.json`
 *             sidecar — see README.md "Legacy parity videos". `--screenshots <dir>`
 *             adds declared legacy↔AlpenFlight parity PNGs (list + form) from a
 *             `screenshots.json` sidecar, rendered paired per journey/view.
 *   - import: `import { generateGallery } from './generate-gallery.mjs'`
 *             generateGallery({ reportPath, outDir, orderPath?, legacyVideoDir?, screenshotsDir? }) — T-02 drives this.
 */
import { readFileSync, writeFileSync, mkdirSync, existsSync, copyFileSync } from 'node:fs';
import { dirname, resolve, basename } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));

/**
 * Static roadmap fallback — the journey IDs the gallery iterates when
 * `_ORDER.md` is not reachable from the run dir. Kept in roadmap order.
 * Source of truth is docs/modernization/stories/_ORDER.md; this mirror exists
 * so the generator runs standalone inside a CI artifact dir without the repo.
 */
export const ROADMAP_FALLBACK = [
  'J-0',
  'J-0b',
  'J-0c',
  'J-1',
  'J-2',
  'J-3',
  'J-4',
  'J-5',
  'J-6',
  'J-7',
  'J-8',
  'J-9',
  'J-10',
  'J-11',
  'J-12',
  'J-13',
  'J-14',
  'J-15',
  'J-16',
  'J-17',
  'J-18',
  'J-19',
  'J-20',
  'J-21',
  'J-22',
  'J-24',
];

/**
 * Parse the journey ids out of the `| … | J-N | …` roadmap table rows of an
 * _ORDER.md body, in table order. Exported (text-in, pure) so the ordering
 * contract is unit-testable without a temp file.
 *
 * The leading table cell may carry decoration before the id — a shipped journey
 * is marked with a `✅ ` prefix in _ORDER.md (`| ✅ **J-0** |`, `| ✅ J-24 |`),
 * and ids are optionally bold (`**J-0**`). We must skip that leading emoji /
 * whitespace / bold so a shipped journey parses in its roadmap-table position —
 * not dropped (which would let the `generateGallery` append-loop tack it onto
 * the BOTTOM, the operator's "J-0 not back" complaint).
 */
export function parseRoadmapText(text) {
  const ids = [];
  const seen = new Set();
  // First table cell of a row, then any leading decoration (✅ / whitespace /
  // bold `**`) before the `J-NN` id, then the id, bold-close optional.
  //   | ✅ **J-0** | …   | ✅ J-24 | …   | **J-0c** | …   | J-1 | …
  const re = /^\|\s*(?:[^\sA-Za-z|]+\s*)*\*{0,2}(J-\d+[a-z]?)\*{0,2}\s*\|/gm;
  let m;
  while ((m = re.exec(text)) !== null) {
    const id = m[1];
    if (!seen.has(id)) {
      seen.add(id);
      ids.push(id);
    }
  }
  return ids;
}

/** Parse the `| **J-N** | …` roadmap table rows out of _ORDER.md, in order. */
function parseRoadmap(orderPath) {
  if (!orderPath || !existsSync(orderPath)) return ROADMAP_FALLBACK;
  const ids = parseRoadmapText(readFileSync(orderPath, 'utf8'));
  return ids.length ? ids : ROADMAP_FALLBACK;
}

/** Walk nested suites → flat list of { spec, test } for every test. */
function* walkTests(suite) {
  for (const spec of suite.specs ?? []) {
    for (const test of spec.tests ?? []) {
      yield { spec, test };
    }
  }
  for (const child of suite.suites ?? []) {
    yield* walkTests(child);
  }
}

function annotation(test, spec, type) {
  const fromTest = (test.annotations ?? []).find((a) => a.type === type);
  if (fromTest) return fromTest.description ?? '';
  const fromSpec = (spec.annotations ?? []).find((a) => a.type === type);
  return fromSpec ? (fromSpec.description ?? '') : undefined;
}

/** Derive a journey id (e.g. J-0) from a spec file path as a fallback. */
function journeyFromFile(file) {
  if (!file) return undefined;
  const m = basename(file).match(/\b(j-?(\d+[a-z]?))\b/i);
  return m ? `J-${m[2]}` : undefined;
}

/**
 * Extract published proofs from a parsed Playwright JSON report.
 * Returns { proofs: [{ journey, caption, acTag, videoPath, title }], errors: [] }.
 * `errors` collects AC5 violations (no caption / missing .webm).
 */
export function extractProofs(report, { reportDir }) {
  const proofs = [];
  const errors = [];
  for (const suite of report.suites ?? []) {
    for (const { spec, test } of walkTests(suite)) {
      const result = (test.results ?? []).find((r) =>
        (r.attachments ?? []).some((a) => a.name === 'proof-video'),
      );
      if (!result) continue; // not a proof test
      const att = result.attachments.find((a) => a.name === 'proof-video');
      // Only publish green proofs.
      if (result.status !== 'passed') continue;

      const title = spec.title ?? test.title ?? '(untitled)';
      const caption = annotation(test, spec, 'proof-caption');
      const acTag = annotation(test, spec, 'proof-ac-tag');
      const journey =
        annotation(test, spec, 'proof-journey') ||
        journeyFromFile(spec.file ?? suite.file) ||
        'unknown';
      const videoPath = att.path ? resolve(reportDir, att.path) : undefined;

      // AC5 — link-check (the [key-error] case).
      if (!caption || !caption.trim()) {
        errors.push(`published proof video has no caption: "${title}" (${att.path ?? '?'})`);
      }
      if (!videoPath || !existsSync(videoPath)) {
        errors.push(
          `caption references a .webm not present in the proof output: "${title}" → ${att.path ?? '(no path)'}`,
        );
      }

      proofs.push({ journey, caption: caption ?? '', acTag, videoPath, title });
    }
  }
  return { proofs, errors };
}

/**
 * Extract declared LEGACY parity videos (J-0c+) from a `legacy-video.json`
 * sidecar in `legacyVideoDir`. The Playwright JSON report only carries
 * AlpenFlight `real-idp` proofs; a legacy (e.g. flsweb) parity video has no
 * manifest path, so it is declared in a sidecar keyed to a journey:
 *
 *   { "videos": [ { "journey": "J-0c", "file": "x.webm",
 *                   "acTag": "happy", "caption": "Legacy flsweb: …" } ] }
 *
 * `file` resolves relative to the sidecar dir. Returns the same proof shape as
 * `extractProofs`, flagged `legacy: true`, plus the AC5 link-check `errors`
 * (caption required; .webm must exist on disk) — identical bar to AlpenFlight
 * proofs. A missing dir / sidecar is a no-op (no legacy video this run).
 */
export function extractLegacyVideos(legacyVideoDir) {
  const proofs = [];
  const errors = [];
  if (!legacyVideoDir) return { proofs, errors };
  const sidecar = resolve(legacyVideoDir, 'legacy-video.json');
  if (!existsSync(sidecar)) return { proofs, errors };

  const decl = JSON.parse(readFileSync(sidecar, 'utf8'));
  for (const v of decl.videos ?? []) {
    const title = v.file ?? '(legacy video)';
    const caption = v.caption;
    const videoPath = v.file ? resolve(legacyVideoDir, v.file) : undefined;

    if (!caption || !String(caption).trim()) {
      errors.push(`published legacy proof video has no caption: "${title}"`);
    }
    if (!videoPath || !existsSync(videoPath)) {
      errors.push(
        `legacy caption references a .webm not present in the proof output: "${title}" → ${v.file ?? '(no file)'}`,
      );
    }

    proofs.push({
      journey: v.journey ?? 'unknown',
      caption: caption ?? '',
      acTag: v.acTag,
      videoPath,
      title,
      legacy: true,
    });
  }
  return { proofs, errors };
}

/**
 * Extract declared PARITY SCREENSHOTS (J-1+) from a `screenshots.json` sidecar
 * in `screenshotsDir`. Mirrors `extractLegacyVideos`: still PNGs (legacy +
 * AlpenFlight, list + form) have no Playwright-manifest path — the legacy ones
 * are captured against the legacy flsweb stack, the AlpenFlight ones against the
 * real chain, both in the fan-out — so they are DECLARED in a sidecar keyed to a
 * journey + side + view so the generator can PAIR them:
 *
 *   { "screenshots": [
 *       { "journey": "J-1", "side": "legacy",      "view": "list",
 *         "file": "legacy-aircraft-list.png",      "caption": "Legacy flsweb: …" },
 *       { "journey": "J-1", "side": "alpenflight", "view": "form",
 *         "file": "alpenflight-aircraft-form.png", "caption": "AlpenFlight: …" }
 *     ] }
 *
 * `file` resolves relative to the sidecar dir. `side` ∈ {legacy, alpenflight};
 * `view` is the pairing key (e.g. list | form). Returns flat shots plus the AC5
 * link-check `errors` (caption required; PNG must exist on disk) — identical bar
 * to videos. A missing dir / sidecar is a no-op (no screenshots this run).
 */
export function extractScreenshots(screenshotsDir) {
  const shots = [];
  const errors = [];
  if (!screenshotsDir) return { shots, errors };
  const sidecar = resolve(screenshotsDir, 'screenshots.json');
  if (!existsSync(sidecar)) return { shots, errors };

  const decl = JSON.parse(readFileSync(sidecar, 'utf8'));
  for (const s of decl.screenshots ?? []) {
    const title = s.file ?? '(parity screenshot)';
    const caption = s.caption;
    const imgPath = s.file ? resolve(screenshotsDir, s.file) : undefined;

    if (!caption || !String(caption).trim()) {
      errors.push(`published parity screenshot has no caption: "${title}"`);
    }
    if (!imgPath || !existsSync(imgPath)) {
      errors.push(
        `screenshot caption references a .png not present in the proof output: "${title}" → ${s.file ?? '(no file)'}`,
      );
    }

    shots.push({
      journey: s.journey ?? 'unknown',
      side: s.side ?? 'alpenflight',
      view: s.view ?? 'view',
      caption: caption ?? '',
      imgPath,
      title,
    });
  }
  return { shots, errors };
}

function esc(s) {
  return String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function tagClass(acTag) {
  if (acTag === 'key-error') return 'failure';
  if (acTag === 'edge') return 'pending';
  return 'success';
}

/**
 * Load the committed per-run-galleries manifest (sibling to this script). These
 * are heavy-chain galleries deployed to a namespaced subpath under
 * /alpenflight/proof/ — rendered as a nav block on the canonical index so they
 * survive regeneration. Returns [] if the manifest is absent/empty/malformed
 * (nav is a nicety, never a hard failure).
 */
export function loadNavGalleries() {
  const manifestPath = resolve(__dirname, 'per-run-galleries.json');
  if (!existsSync(manifestPath)) return [];
  try {
    const decl = JSON.parse(readFileSync(manifestPath, 'utf8'));
    return (decl.galleries ?? []).filter((g) => g && g.label && g.href);
  } catch {
    return [];
  }
}

function renderNavBlock(navGalleries) {
  if (!navGalleries.length) return '';
  const links = navGalleries
    .map(
      (g) =>
        `      &rarr; <a href="${esc(g.href)}"><strong>${esc(g.label)}</strong></a>` +
        (g.note ? ` <em>(${esc(g.note)})</em>` : ''),
    )
    .join('<br>\n');
  return `
    <p class="meta nav-galleries">
      <strong>Per-run proof galleries</strong> (heavy chains not in the index below):<br>
${links}
    </p>`;
}

/**
 * Render the per-journey PARITY-SCREENSHOTS block: one row per `view` (e.g.
 * list, form), legacy `<img>` LEFT + AlpenFlight `<img>` RIGHT, so the operator
 * eyeballs the field set side by side. Views render in first-seen declaration
 * order; within a view, legacy is forced left. A view with only one side still
 * renders that side (the other slot is simply absent). Empty `shots` → no block.
 */
function renderScreenshots(shots) {
  if (!shots || shots.length === 0) return '';
  // Group by view, preserving declaration order of views.
  const byView = new Map();
  for (const s of shots) {
    if (!byView.has(s.view)) byView.set(s.view, []);
    byView.get(s.view).push(s);
  }
  const rows = [];
  for (const [view, list] of byView) {
    // Legacy first, AlpenFlight second.
    const ordered = [...list].sort((a, b) =>
      a.side === b.side ? 0 : a.side === 'legacy' ? -1 : 1,
    );
    const figs = ordered
      .map(
        (s) => `          <figure class="shot shot-${esc(s.side)}">
            <span class="shot-side">${esc(s.side)}</span>
            <a href="${esc(s.imgSrc)}" target="_blank" rel="noopener"><img src="${esc(s.imgSrc)}" alt="${esc(s.side)} ${esc(view)}" loading="lazy"></a>
            <figcaption>${esc(s.caption)}</figcaption>
          </figure>`,
      )
      .join('\n');
    rows.push(`        <div class="shot-pair">
          <div class="shot-view-label">${esc(view)}</div>
          <div class="shot-grid">
${figs}
          </div>
        </div>`);
  }
  return `        <div class="parity-screenshots">
          <h4>Legacy &harr; AlpenFlight parity screenshots</h4>
${rows.join('\n')}
        </div>`;
}

function renderHtml({ byJourney, shotsByJourney, roadmap, generatedAt, branch, navGalleries = [] }) {
  const sections = roadmap
    .map((jid) => {
      const proofs = byJourney.get(jid);
      const shots = shotsByJourney?.get(jid);
      if ((!proofs || proofs.length === 0) && (!shots || shots.length === 0)) {
        return `      <div class="journey pending-journey">
        <h3>${esc(jid)} <span class="status pending">pending</span></h3>
        <p>No green proof yet — this journey has not shipped a captioned pass-video.</p>
      </div>`;
      }
      // A journey with parity screenshots but no green video still renders its
      // screenshot block (the videos section is just empty).
      if (!proofs || proofs.length === 0) {
        return `      <div class="journey">
        <h3>${esc(jid)} <span class="status success">parity screenshots</span></h3>
${renderScreenshots(shots)}
      </div>`;
      }
      const videos = proofs
        .map((p) => {
          const tag = p.acTag
            ? `<span class="status ${tagClass(p.acTag)}">${esc(p.acTag)}</span>`
            : '';
          // A legacy parity video (e.g. legacy flsweb) is labelled as the
          // legacy side so a reviewer reads legacy-vs-AlpenFlight side by side.
          const legacyLabel = p.legacy ? '<span class="legacy-label">legacy parity</span>' : '';
          const figClass = p.legacy ? 'proof legacy-proof' : 'proof';
          return `        <figure class="${figClass}">
          <video controls preload="metadata" src="${esc(p.videoSrc)}"></video>
          <figcaption>${legacyLabel}${tag}<span class="caption">${esc(p.caption)}</span></figcaption>
        </figure>`;
        })
        .join('\n');
      const screenshotsBlock = renderScreenshots(shots);
      return `      <div class="journey">
        <h3>${esc(jid)} <span class="status success">${proofs.length} proof${proofs.length === 1 ? '' : 's'}</span></h3>
        <div class="proofs">
${videos}
        </div>
${screenshotsBlock}
      </div>`;
    })
    .join('\n');

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>AlpenFlight proof gallery</title>
<style>
:root {
  --bg: #f7f8fa; --surface: #ffffff; --surface-2: #eef1f4;
  --text: #1a1d21; --muted: #5c6470; --border: #e3e6ea;
  --primary: #0a66c2; --primary-hover: #0552a3;
  --success: #0a7f3f; --success-bg: #e3f5ea;
  --failure: #c0353a; --failure-bg: #fbe6e7;
  --pending: #8a6d00; --pending-bg: #fbf3d6;
  --shadow: 0 1px 2px rgba(0,0,0,.04), 0 1px 3px rgba(0,0,0,.06);
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #0d1117; --surface: #161b22; --surface-2: #1c222b;
    --text: #e6edf3; --muted: #8d96a0; --border: #2a313b;
    --primary: #4da3ff; --primary-hover: #79bbff;
    --success: #3fb950; --success-bg: #14271b;
    --failure: #f85149; --failure-bg: #2a1416;
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
.container { max-width: 1200px; margin: 0 auto; padding: clamp(1rem, 3vw, 2.5rem) clamp(1rem, 3vw, 1.5rem); }
header { margin-bottom: 2rem; }
h1 { font-size: clamp(1.5rem, 4vw, 2.25rem); margin: 0 0 .5rem; letter-spacing: -.02em; }
h3 { font-size: 1.05rem; margin: 0 0 .5rem; display: flex; align-items: center; gap: .6rem; }
p { margin: 0; color: var(--muted); }
code { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: .85em; background: var(--surface-2); padding: .1em .4em; border-radius: 4px; }
a { color: var(--primary); text-decoration: none; }
a:hover { color: var(--primary-hover); text-decoration: underline; }
.meta { color: var(--muted); font-size: .9rem; }
.status { display: inline-block; padding: .2em .65em; border-radius: 4px; font-size: .8rem; font-weight: 500; }
.status.success { background: var(--success-bg); color: var(--success); }
.status.failure { background: var(--failure-bg); color: var(--failure); }
.status.pending { background: var(--pending-bg); color: var(--pending); }
.journey {
  background: var(--surface); border: 1px solid var(--border);
  border-radius: 6px; box-shadow: var(--shadow); padding: 1.25rem; margin: 1rem 0;
}
.pending-journey { opacity: .85; }
.proofs { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 1rem; margin-top: 1rem; }
.proof {
  margin: 0; background: var(--surface-2); border: 1px solid var(--border);
  border-radius: 6px; overflow: hidden;
}
.proof video { display: block; width: 100%; height: auto; background: #000; }
.proof figcaption { padding: .65rem .75rem; font-size: .9rem; display: flex; flex-direction: column; gap: .4rem; }
.proof .caption { color: var(--text); }
.proof.legacy-proof { border-color: var(--pending); }
.legacy-label {
  display: inline-block; align-self: flex-start; padding: .2em .65em; border-radius: 4px;
  font-size: .8rem; font-weight: 500; text-transform: uppercase; letter-spacing: .03em;
  background: var(--pending-bg); color: var(--pending);
}
.parity-screenshots { margin-top: 1.25rem; border-top: 1px solid var(--border); padding-top: 1rem; }
.parity-screenshots h4 { margin: 0 0 .75rem; font-size: .95rem; font-weight: 500; }
.shot-pair { margin-bottom: 1.25rem; }
.shot-view-label { font-size: .8rem; font-weight: 500; text-transform: uppercase; letter-spacing: .03em; color: var(--muted); margin-bottom: .4rem; }
.shot-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 1rem; }
.shot { margin: 0; background: var(--surface-2); border: 1px solid var(--border); border-radius: 6px; overflow: hidden; }
.shot.shot-legacy { border-color: var(--pending); }
.shot img { display: block; width: 100%; height: auto; background: #fff; }
.shot-side { display: inline-block; padding: .2em .65em; margin: .5rem .5rem 0; border-radius: 4px; font-size: .75rem; font-weight: 500; text-transform: uppercase; letter-spacing: .03em; background: var(--success-bg); color: var(--success); }
.shot.shot-legacy .shot-side { background: var(--pending-bg); color: var(--pending); }
.shot figcaption { padding: .65rem .75rem; font-size: .85rem; color: var(--text); }
.nav-galleries { margin-top: .75rem; padding: .6rem .8rem; border: 2px solid var(--primary); border-radius: 4px; background: var(--surface-2); }
footer { margin-top: 3rem; color: var(--muted); font-size: .85em; }
</style>
</head>
<body>
<div class="container">
  <header>
    <h1>AlpenFlight proof gallery</h1>
    <p class="meta">
      Each video is a green <code>real-idp</code> proof pass-video, captioned with the
      assertion it proves. Generated on <code>${esc(branch)}</code> &middot; ${esc(generatedAt)}
    </p>
    <p class="meta" style="margin-top:.5rem;">
      <a href="../">&larr; alpenflight dashboard</a>
    </p>${renderNavBlock(navGalleries)}
  </header>

  <section>
${sections}
  </section>

  <footer>
    Proof source: <code>real-idp</code> Playwright run (live Keycloak + Spring + Postgres).
    Pending journeys have not yet shipped a green captioned proof.
  </footer>
</div>
</body>
</html>
`;
}

/**
 * Generate the proof gallery.
 * @param {object} o
 * @param {string} o.reportPath   Path to the Playwright JSON report (the manifest).
 * @param {string} o.outDir       Directory to write index.html (+ copied videos/) into.
 * @param {string} [o.orderPath]  Path to _ORDER.md (roadmap). Falls back to ROADMAP_FALLBACK.
 * @param {string} [o.branch]     Branch label for the header.
 * @param {string} [o.legacyVideoDir] Dir holding a `legacy-video.json` sidecar +
 *   its `.webm`(s) — declared LEGACY parity videos (J-0c+), rendered side-by-side
 *   with the AlpenFlight proof under the same journey. Absent dir/sidecar = no-op.
 * @param {string} [o.screenshotsDir] Dir holding a `screenshots.json` sidecar +
 *   its `.png`(s) — declared legacy↔AlpenFlight parity screenshots (J-1+, list +
 *   form), rendered paired under the same journey. Absent dir/sidecar = no-op.
 * @returns {{ html: string, outFile: string, proofs: Array, shots: Array, roadmap: string[] }}
 * @throws on any AC5 link-check violation (no caption / missing .webm / missing .png).
 */
export function generateGallery({
  reportPath,
  outDir,
  orderPath,
  branch = process.env.GITHUB_REF_NAME ?? 'local',
  legacyVideoDir,
  screenshotsDir,
  renderNav = true,
}) {
  const reportDir = dirname(resolve(reportPath));
  const report = JSON.parse(readFileSync(reportPath, 'utf8'));
  const { proofs: manifestProofs, errors: manifestErrors } = extractProofs(report, { reportDir });
  const { proofs: legacyProofs, errors: legacyErrors } = extractLegacyVideos(legacyVideoDir);
  const { shots, errors: shotErrors } = extractScreenshots(screenshotsDir);
  const proofs = [...manifestProofs, ...legacyProofs];
  const errors = [...manifestErrors, ...legacyErrors, ...shotErrors];

  if (errors.length) {
    throw new Error(`proof-gallery link-check failed (AC5):\n  - ${errors.join('\n  - ')}`);
  }

  // Copy each video into outDir/videos/ and rewrite src to a relative path so
  // the published page is self-contained.
  mkdirSync(resolve(outDir, 'videos'), { recursive: true });
  for (const p of proofs) {
    // Namespace legacy copies so a legacy .webm can never collide with an
    // AlpenFlight one of the same basename in the shared videos/ dir.
    const name = p.legacy ? `legacy-${basename(p.videoPath)}` : basename(p.videoPath);
    copyFileSync(p.videoPath, resolve(outDir, 'videos', name));
    p.videoSrc = `videos/${name}`;
  }

  // Copy each declared screenshot into outDir/screenshots/ and rewrite src so the
  // published page is self-contained (same self-containment contract as videos).
  if (shots.length) {
    mkdirSync(resolve(outDir, 'screenshots'), { recursive: true });
    for (const s of shots) {
      const name = basename(s.imgPath);
      copyFileSync(s.imgPath, resolve(outDir, 'screenshots', name));
      s.imgSrc = `screenshots/${name}`;
    }
  }

  const roadmap = parseRoadmap(orderPath);
  const byJourney = new Map();
  for (const p of proofs) {
    if (!byJourney.has(p.journey)) byJourney.set(p.journey, []);
    byJourney.get(p.journey).push(p);
  }
  // Within a journey, render the legacy parity video FIRST so the reviewer reads
  // legacy → AlpenFlight left-to-right (the side-by-side parity framing).
  for (const list of byJourney.values()) {
    list.sort((a, b) => (a.legacy === b.legacy ? 0 : a.legacy ? -1 : 1));
  }
  const shotsByJourney = new Map();
  for (const s of shots) {
    if (!shotsByJourney.has(s.journey)) shotsByJourney.set(s.journey, []);
    shotsByJourney.get(s.journey).push(s);
  }
  // A green proof OR a declared screenshot for a journey not in the roadmap still
  // gets shown (appended).
  for (const jid of [...byJourney.keys(), ...shotsByJourney.keys()]) {
    if (!roadmap.includes(jid)) roadmap.push(jid);
  }

  const html = renderHtml({
    byJourney,
    shotsByJourney,
    roadmap,
    generatedAt: new Date().toISOString(),
    branch,
    navGalleries: renderNav ? loadNavGalleries() : [],
  });

  mkdirSync(outDir, { recursive: true });
  const outFile = resolve(outDir, 'index.html');
  writeFileSync(outFile, html, 'utf8');
  return { html, outFile, proofs, shots, roadmap };
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--report') out.reportPath = argv[++i];
    else if (a === '--out') out.outDir = argv[++i];
    else if (a === '--order') out.orderPath = argv[++i];
    else if (a === '--branch') out.branch = argv[++i];
    else if (a === '--legacy-video') out.legacyVideoDir = argv[++i];
    else if (a === '--screenshots') out.screenshotsDir = argv[++i];
    else if (a === '--no-nav') out.renderNav = false;
  }
  return out;
}

// CLI entrypoint (only when run directly, not when imported).
if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  const args = parseArgs(process.argv.slice(2));
  // Defaults wired for `pnpm proof:gallery`: build the committed fixtures into
  // a local out dir so a generator change can be eyeballed without CI.
  const reportPath = args.reportPath ?? resolve(__dirname, 'fixtures', 'proof-manifest.json');
  const outDir = args.outDir ?? resolve(__dirname, '..', '..', 'public', 'alpenflight', 'proof');
  // Default legacy-video dir for `pnpm proof:gallery` (fixtures); CI passes
  // --legacy-video <staged dir>. Only picked up if a legacy-video.json exists.
  const legacyVideoDir = args.legacyVideoDir ?? resolve(__dirname, 'fixtures', 'legacy-video');
  // Default screenshots dir for `pnpm proof:gallery` (fixtures); CI passes
  // --screenshots <staged dir>. Only picked up if a screenshots.json exists.
  const screenshotsDir = args.screenshotsDir ?? resolve(__dirname, 'fixtures', 'screenshots');
  let orderPath = args.orderPath;
  if (!orderPath) {
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
    orderPath = existsSync(guess) ? guess : undefined;
  }
  try {
    const { outFile, proofs, shots, roadmap } = generateGallery({
      reportPath,
      outDir,
      orderPath,
      branch: args.branch,
      legacyVideoDir,
      screenshotsDir,
      renderNav: args.renderNav !== false,
    });
    const pending = roadmap.filter((j) => !proofs.some((p) => p.journey === j));
    console.log(`proof-gallery: wrote ${outFile}`);
    console.log(
      `  ${proofs.length} green proof video(s); ${shots.length} parity screenshot(s); ${pending.length} pending journey(s).`,
    );
  } catch (err) {
    console.error(`proof-gallery: ${err.message}`);
    process.exit(1);
  }
}
