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
 *   - CLI:    node generate-gallery.mjs --report <json> --out <dir> [--order <_ORDER.md>] [--videos <dir>]
 *             (also: `pnpm proof:gallery`)
 *   - import: `import { generateGallery } from './generate-gallery.mjs'`
 *             generateGallery({ reportPath, outDir, orderPath?, videosDir? }) — T-02 drives this.
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

/** Parse the `| **J-N** | …` roadmap table rows out of _ORDER.md, in order. */
function parseRoadmap(orderPath) {
  if (!orderPath || !existsSync(orderPath)) return ROADMAP_FALLBACK;
  const text = readFileSync(orderPath, 'utf8');
  const ids = [];
  const seen = new Set();
  // Match a leading table cell whose content is a journey id, bold or plain:
  //   | **J-0** | …   or   | J-1 | …
  const re = /^\|\s*\*{0,2}(J-\d+[a-z]?)\*{0,2}\s*\|/gm;
  let m;
  while ((m = re.exec(text)) !== null) {
    const id = m[1];
    if (!seen.has(id)) {
      seen.add(id);
      ids.push(id);
    }
  }
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

function renderHtml({ byJourney, roadmap, generatedAt, branch }) {
  const sections = roadmap
    .map((jid) => {
      const proofs = byJourney.get(jid);
      if (!proofs || proofs.length === 0) {
        return `      <div class="journey pending-journey">
        <h3>${esc(jid)} <span class="status pending">pending</span></h3>
        <p>No green proof yet — this journey has not shipped a captioned pass-video.</p>
      </div>`;
      }
      const videos = proofs
        .map((p) => {
          const tag = p.acTag
            ? `<span class="status ${tagClass(p.acTag)}">${esc(p.acTag)}</span>`
            : '';
          return `        <figure class="proof">
          <video controls preload="metadata" src="${esc(p.videoSrc)}"></video>
          <figcaption>${tag}<span class="caption">${esc(p.caption)}</span></figcaption>
        </figure>`;
        })
        .join('\n');
      return `      <div class="journey">
        <h3>${esc(jid)} <span class="status success">${proofs.length} proof${proofs.length === 1 ? '' : 's'}</span></h3>
        <div class="proofs">
${videos}
        </div>
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
    </p>
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
 * @returns {{ html: string, outFile: string, proofs: Array, roadmap: string[] }}
 * @throws on any AC5 link-check violation (no caption / missing .webm).
 */
export function generateGallery({
  reportPath,
  outDir,
  orderPath,
  branch = process.env.GITHUB_REF_NAME ?? 'local',
}) {
  const reportDir = dirname(resolve(reportPath));
  const report = JSON.parse(readFileSync(reportPath, 'utf8'));
  const { proofs, errors } = extractProofs(report, { reportDir });

  if (errors.length) {
    throw new Error(`proof-gallery link-check failed (AC5):\n  - ${errors.join('\n  - ')}`);
  }

  // Copy each video into outDir/videos/ and rewrite src to a relative path so
  // the published page is self-contained.
  mkdirSync(resolve(outDir, 'videos'), { recursive: true });
  for (const p of proofs) {
    const dest = resolve(outDir, 'videos', basename(p.videoPath));
    copyFileSync(p.videoPath, dest);
    p.videoSrc = `videos/${basename(p.videoPath)}`;
  }

  const roadmap = parseRoadmap(orderPath);
  const byJourney = new Map();
  for (const p of proofs) {
    if (!byJourney.has(p.journey)) byJourney.set(p.journey, []);
    byJourney.get(p.journey).push(p);
  }
  // A green proof for a journey not in the roadmap still gets shown (appended).
  for (const jid of byJourney.keys()) {
    if (!roadmap.includes(jid)) roadmap.push(jid);
  }

  const html = renderHtml({
    byJourney,
    roadmap,
    generatedAt: new Date().toISOString(),
    branch,
  });

  mkdirSync(outDir, { recursive: true });
  const outFile = resolve(outDir, 'index.html');
  writeFileSync(outFile, html, 'utf8');
  return { html, outFile, proofs, roadmap };
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--report') out.reportPath = argv[++i];
    else if (a === '--out') out.outDir = argv[++i];
    else if (a === '--order') out.orderPath = argv[++i];
    else if (a === '--branch') out.branch = argv[++i];
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
    const { outFile, proofs, roadmap } = generateGallery({
      reportPath,
      outDir,
      orderPath,
      branch: args.branch,
    });
    const pending = roadmap.filter((j) => !proofs.some((p) => p.journey === j));
    console.log(`proof-gallery: wrote ${outFile}`);
    console.log(`  ${proofs.length} green proof video(s); ${pending.length} pending journey(s).`);
  } catch (err) {
    console.error(`proof-gallery: ${err.message}`);
    process.exit(1);
  }
}
