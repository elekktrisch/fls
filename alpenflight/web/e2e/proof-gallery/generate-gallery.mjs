#!/usr/bin/env node
import { readFileSync, writeFileSync, mkdirSync, existsSync, copyFileSync } from 'node:fs';
import { dirname, resolve, basename } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));

export const DEFAULT_SITE_BASE = '/fls/';

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
  'J-26',
  'J-8',
  'J-9',
  'J-10',
  'J-10b',
  'J-11',
  'J-12a',
  'J-12b',
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
  'J-25',
];

export function parseRoadmapText(text) {
  const ids = [];
  const seen = new Set();
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

function journeyFromFile(file) {
  if (!file) return undefined;
  const m = basename(file).match(/\b(j-?(\d+[a-z]?))\b/i);
  return m ? `J-${m[2]}` : undefined;
}

export function extractProofs(report, { reportDir }) {
  const proofs = [];
  const errors = [];
  for (const suite of report.suites ?? []) {
    for (const { spec, test } of walkTests(suite)) {
      const result = (test.results ?? []).find((r) =>
        (r.attachments ?? []).some((a) => a.name === 'proof-video'),
      );
      if (!result) continue;
      const att = result.attachments.find((a) => a.name === 'proof-video');
      if (result.status !== 'passed') continue;

      const title = spec.title ?? test.title ?? '(untitled)';
      const caption = annotation(test, spec, 'proof-caption');
      const acTag = annotation(test, spec, 'proof-ac-tag');
      const journey =
        annotation(test, spec, 'proof-journey') ||
        journeyFromFile(spec.file ?? suite.file) ||
        'unknown';
      const videoPath = att.path ? resolve(reportDir, att.path) : undefined;

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

export function renderedShotKeys(shots, journey) {
  const keys = new Set();
  for (const s of shots ?? []) {
    if (journey && s.journey !== journey) continue;
    if (s.imgPath && !existsSync(s.imgPath)) continue;
    if (!s.side || !s.view) continue;
    keys.add(`${s.side}:${s.view}`);
  }
  return keys;
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

function renderScreenshots(shots) {
  if (!shots || shots.length === 0) return '';
  const byView = new Map();
  for (const s of shots) {
    if (!byView.has(s.view)) byView.set(s.view, []);
    byView.get(s.view).push(s);
  }
  const rows = [];
  for (const [view, list] of byView) {
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

function renderJourneyBody(proofs, shots) {
  if ((!proofs || proofs.length === 0) && (!shots || shots.length === 0)) {
    return `        <p>No green proof yet — this journey has not shipped a captioned pass-video.</p>`;
  }
  if (!proofs || proofs.length === 0) {
    return renderScreenshots(shots);
  }
  const videos = proofs
    .map((p) => {
      const tag = p.acTag ? `<span class="status ${tagClass(p.acTag)}">${esc(p.acTag)}</span>` : '';
      const legacyLabel = p.legacy ? '<span class="legacy-label">legacy parity</span>' : '';
      const figClass = p.legacy ? 'proof legacy-proof' : 'proof';
      return `        <figure class="${figClass}">
          <video controls preload="metadata" src="${esc(p.videoSrc)}"></video>
          <figcaption>${legacyLabel}${tag}<span class="caption">${esc(p.caption)}</span></figcaption>
        </figure>`;
    })
    .join('\n');
  const screenshotsBlock = renderScreenshots(shots);
  return `        <div class="proofs">
${videos}
        </div>
${screenshotsBlock}`;
}


function readJsonSoft(absPath) {
  try {
    if (!existsSync(absPath)) return null;
    return JSON.parse(readFileSync(absPath, 'utf8'));
  } catch {
    return null;
  }
}

function readTextSoft(absPath) {
  try {
    if (!existsSync(absPath)) return null;
    return readFileSync(absPath, 'utf8');
  } catch {
    return null;
  }
}

export function parseFallowAudit(json) {
  if (!json || typeof json !== 'object') return null;
  const a = json.attribution ?? {};
  return {
    verdict: json.verdict ?? 'unknown',
    deadIntroduced: Number(a.dead_code_introduced ?? 0),
    complexityIntroduced: Number(a.complexity_introduced ?? 0),
    duplicationIntroduced: Number(a.duplication_introduced ?? 0),
  };
}

export function parseFallowHealth(json) {
  if (!json || typeof json !== 'object') return null;
  const hs = json.health_score ?? {};
  const vs = json.vital_signs ?? {};
  return {
    score: hs.score ?? null,
    grade: hs.grade ?? null,
    maintainability: vs.maintainability_avg ?? null,
    duplicationPct: vs.duplication_pct ?? null,
    deadFilePct: vs.dead_file_pct ?? null,
  };
}

export function parsePmd(xml) {
  if (!xml || typeof xml !== 'string') return null;
  const violations = xml.match(/<violation\b[^>]*\brule="([^"]+)"/g) ?? [];
  let complexity = 0;
  let deadCode = 0;
  for (const v of violations) {
    const rule = (v.match(/\brule="([^"]+)"/) ?? [])[1] ?? '';
    if (/Complexity|NPath|Ncss|ExcessiveParameterList|TooMany|GodClass/i.test(rule))
      complexity += 1;
    else if (/Unused|Empty|DeadCode|UselessOverriding/i.test(rule)) deadCode += 1;
  }
  return { total: violations.length, complexity, deadCode };
}

export function parseCpd(xml) {
  if (!xml || typeof xml !== 'string') return null;
  const dupes = xml.match(/<duplication\b[^>]*\btokens="(\d+)"/g) ?? [];
  const groups = dupes.length;
  let dupTokens = 0;
  for (const d of dupes) dupTokens += Number((d.match(/\btokens="(\d+)"/) ?? [])[1] ?? 0);
  let totalTokens = 0;
  for (const f of xml.match(/\btotalNumberOfTokens="(\d+)"/g) ?? [])
    totalTokens += Number((f.match(/"(\d+)"/) ?? [])[1] ?? 0);
  const dupPct = totalTokens > 0 ? (dupTokens / totalTokens) * 100 : null;
  return { groups, dupPct };
}

export function parseQodana(json) {
  if (!json || typeof json !== 'object') return null;
  const runs = Array.isArray(json.runs) ? json.runs : [];
  let total = 0;
  let newFindings = 0;
  let sawBaselineState = false;
  for (const run of runs) {
    const results = Array.isArray(run?.results) ? run.results : [];
    for (const r of results) {
      total += 1;
      if (typeof r?.baselineState === 'string') {
        sawBaselineState = true;
        if (r.baselineState === 'new') newFindings += 1;
      }
    }
  }
  return { total, newFindings: sawBaselineState ? newFindings : null };
}

export function loadMaintainability(outDir, { showDelta = false } = {}) {
  const dir = resolve(outDir, 'maintainability');
  const audit = parseFallowAudit(readJsonSoft(resolve(dir, 'fallow-audit.json')));
  const health = parseFallowHealth(readJsonSoft(resolve(dir, 'fallow-health.json')));
  const pmd = parsePmd(readTextSoft(resolve(dir, 'pmd-main.xml')));
  const cpd = parseCpd(readTextSoft(resolve(dir, 'cpd-check.xml')));
  const qodana = parseQodana(readJsonSoft(resolve(dir, 'qodana-report.sarif.json')));
  const present = Boolean(audit || health || pmd || cpd || qodana);
  return { audit, health, pmd, cpd, qodana, present, showDelta };
}

export function maintainabilityRollup({ audit, showDelta }) {
  if (!showDelta || !audit) return { level: 'neutral', label: 'snapshot only' };
  const introduced =
    (audit.deadIntroduced || 0) +
    (audit.complexityIntroduced || 0) +
    (audit.duplicationIntroduced || 0);
  if (audit.verdict === 'fail') return { level: 'red', label: `${introduced} introduced (fail)` };
  if (introduced > 0) return { level: 'amber', label: `${introduced} introduced` };
  return { level: 'green', label: 'no new findings' };
}

const numOrDash = (n, suffix = '') =>
  n === null || n === undefined || Number.isNaN(Number(n)) ? '—' : `${n}${suffix}`;
const pctOrDash = (n) =>
  n === null || n === undefined || Number.isNaN(Number(n)) ? '—' : `${Number(n).toFixed(1)}%`;

function renderMaintainabilityPanel(maint, { reportHref = 'maintainability/', journeyUnderWork }) {
  const roll = maintainabilityRollup(maint);
  const pillClass =
    roll.level === 'green'
      ? 'success'
      : roll.level === 'amber'
        ? 'pending'
        : roll.level === 'red'
          ? 'failure'
          : 'neutral';
  const { audit, health, pmd, cpd, qodana, present, showDelta } = maint;

  if (!present) {
    return `        <section class="maintainability">
          <h4>Maintainability <span class="status neutral">no data</span></h4>
          <p class="meta">No maintainability artifacts were emitted on this run (the report step is fail-soft).</p>
        </section>`;
  }

  const deltaRow =
    showDelta && audit
      ? `          <tr>
            <th>FE delta (this journey vs main)</th>
            <td>complexity <strong>${numOrDash(audit.complexityIntroduced)}</strong> · duplication <strong>${numOrDash(audit.duplicationIntroduced)}</strong> · dead-code <strong>${numOrDash(audit.deadIntroduced)}</strong> · verdict <strong>${esc(audit.verdict)}</strong></td>
          </tr>`
      : `          <tr>
            <th>FE delta (this journey)</th>
            <td class="muted">— historical per-journey delta not reconstructable (only the current branch's diff is). Snapshot below applies repo-wide.</td>
          </tr>`;

  const healthRow = health
    ? `          <tr>
            <th>FE snapshot (repo)</th>
            <td>score <strong>${numOrDash(health.score)}</strong> (${esc(health.grade ?? '—')}) · maintainability <strong>${numOrDash(health.maintainability)}</strong> · duplication <strong>${pctOrDash(health.duplicationPct)}</strong> · dead files <strong>${pctOrDash(health.deadFilePct)}</strong></td>
          </tr>`
    : `          <tr><th>FE snapshot (repo)</th><td class="muted">— no fallow health data</td></tr>`;

  const pmdRow = pmd
    ? `          <tr>
            <th>BE complexity/dead-code (PMD)</th>
            <td><strong>${numOrDash(pmd.total)}</strong> violations · complexity <strong>${numOrDash(pmd.complexity)}</strong> · dead-code <strong>${numOrDash(pmd.deadCode)}</strong></td>
          </tr>`
    : `          <tr><th>BE complexity/dead-code (PMD)</th><td class="muted">— no PMD report</td></tr>`;

  const cpdRow = cpd
    ? `          <tr>
            <th>BE duplication (CPD)</th>
            <td><strong>${pctOrDash(cpd.dupPct)}</strong> duplicated · <strong>${numOrDash(cpd.groups)}</strong> clone groups</td>
          </tr>`
    : `          <tr><th>BE duplication (CPD)</th><td class="muted">— no CPD report</td></tr>`;

  const qodanaRow = qodana
    ? `          <tr>
            <th>BE unused code (Qodana)</th>
            <td><strong>${numOrDash(qodana.total)}</strong> findings${qodana.newFindings === null ? '' : ` · <strong>${numOrDash(qodana.newFindings)}</strong> new vs baseline`} <span class="meta">(report-only)</span></td>
          </tr>`
    : `          <tr><th>BE unused code (Qodana)</th><td class="muted">— no Qodana report</td></tr>`;

  return `        <section class="maintainability">
          <h4>Maintainability <span class="status ${pillClass}">${esc(roll.label)}</span></h4>
          <table class="maint-table">
${deltaRow}
${healthRow}
${pmdRow}
${cpdRow}
${qodanaRow}
          </table>
          <p class="meta"><a href="${esc(reportHref)}">Full maintainability reports →</a> (fallow audit + health JSON, PMD + CPD XML)${journeyUnderWork ? ` · delta scoped to <strong>${esc(journeyUnderWork)}</strong>` : ''}</p>
        </section>`;
}

const GALLERY_CSS = `:root {
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
.status.neutral { background: var(--surface-2); color: var(--muted); }
.summary-jid { font-weight: 500; }
.summary-counts { color: var(--muted); font-size: .8rem; font-weight: 400; }
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
.maintainability { margin-top: 1.5rem; background: var(--surface); border: 1px solid var(--border); border-radius: 0; padding: 1rem 1.1rem; }
.maintainability h4 { margin: 0 0 .75rem; font-size: 1rem; font-weight: 500; display: flex; align-items: center; gap: .6rem; }
.maint-table { width: 100%; border-collapse: collapse; font-size: .88rem; }
.maint-table th { text-align: left; font-weight: 500; color: var(--muted); padding: .4rem .75rem .4rem 0; white-space: nowrap; vertical-align: top; width: 1%; }
.maint-table td { padding: .4rem 0; color: var(--text); border-top: 1px solid var(--border); }
.maint-table tr:first-child td, .maint-table tr:first-child th { border-top: 0; }
.maint-table td.muted { color: var(--muted); }
footer { margin-top: 3rem; color: var(--muted); font-size: .85em; }`;

export function renderPageHtml({ journey, proofs, shots, maint, generatedAt, branch }) {
  const nVideos = proofs ? proofs.length : 0;
  const nShots = shots ? shots.length : 0;
  const hasContent = nVideos > 0 || nShots > 0;

  const body = renderJourneyBody(proofs, shots);
  const panel = renderMaintainabilityPanel(maint, { journeyUnderWork: journey });

  let statusPill;
  if (!hasContent) statusPill = '<span class="status pending">pending</span>';
  else if (nVideos === 0) statusPill = '<span class="status success">parity screenshots</span>';
  else
    statusPill = `<span class="status success">${nVideos} proof${nVideos === 1 ? '' : 's'}</span>`;
  const counts = hasContent
    ? ` <span class="summary-counts">${nVideos} video${nVideos === 1 ? '' : 's'} · ${nShots} screenshot${nShots === 1 ? '' : 's'}</span>`
    : '';

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark">
<!-- Machine-readable build stamp: the deployed-bookmark link-check compares it
     against the page IT published, so a stale CDN copy cannot pass as this run's. -->
<meta name="proof-generated-at" content="${esc(generatedAt)}">
<title>AlpenFlight proof — ${esc(journey)}</title>
<style>
${GALLERY_CSS}
</style>
</head>
<body>
<div class="container">
  <header>
    <h1>${esc(journey)} — proof</h1>
    <p class="meta">
      <span class="summary-jid">${esc(journey)}</span> ${statusPill}${counts}
    </p>
    <p class="meta">
      The in-flight journey's green <code>real-idp</code> proof pass-video(s) + paired
      legacy &harr; AlpenFlight screenshots. Bookmark this page — its URL is stable;
      merged journeys' proof lives in their PRs. Generated on <code>${esc(branch)}</code> &middot; ${esc(generatedAt)}
    </p>
  </header>

  <section class="journey-page">
${body}
${panel}
  </section>

  <footer>
    Proof source: <code>real-idp</code> Playwright run (live Keycloak + Spring + Postgres).
    Maintainability is informational, scoped to this journey's delta where reconstructable.
  </footer>
</div>
</body>
</html>
`;
}

export function writeMaintainabilityIndex(outDir) {
  const dir = resolve(outDir, 'maintainability');
  if (!existsSync(dir)) return null;
  const artifacts = [
    ['fallow-audit.json', 'fallow audit (FE complexity/duplication/dead-code delta)'],
    ['fallow-health.json', 'fallow health (FE repo snapshot)'],
    ['pmd-main.xml', 'PMD (BE complexity + dead-code)'],
    ['cpd-check.xml', 'CPD (BE duplication)'],
    ['qodana-report.sarif.json', 'Qodana (BE whole-program unused-declaration SARIF)'],
  ].filter(([f]) => existsSync(resolve(dir, f)));
  if (!artifacts.length) return null;
  const items = artifacts
    .map(([f, label]) => `    <li><a href="./${esc(f)}">${esc(f)}</a> — ${esc(label)}</li>`)
    .join('\n');
  const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>AlpenFlight — maintainability reports</title>
</head>
<body>
  <h1>Maintainability reports</h1>
  <p>Raw artifacts behind the Maintainability panel.</p>
  <ul>
${items}
  </ul>
</body>
</html>
`;
  const file = resolve(dir, 'index.html');
  writeFileSync(file, html, 'utf8');
  return file;
}

export function generateGallery({
  reportPath,
  outDir,
  branch = process.env.GITHUB_REF_NAME ?? 'local',
  journeyUnderWork = journeyFromFile(branch) ?? 'unknown',
  legacyVideoDir,
  screenshotsDir,
  siteBase: acceptedButUnusedSiteBase = DEFAULT_SITE_BASE,
}) {
  void acceptedButUnusedSiteBase;
  const journey = journeyUnderWork;
  const reportDir = dirname(resolve(reportPath));
  const report = JSON.parse(readFileSync(reportPath, 'utf8'));
  const { proofs: manifestProofs, errors: manifestErrors } = extractProofs(report, { reportDir });
  const { proofs: legacyProofs, errors: legacyErrors } = extractLegacyVideos(legacyVideoDir);
  const { shots: allShots, errors: shotErrors } = extractScreenshots(screenshotsDir);
  const allProofs = [...manifestProofs, ...legacyProofs];
  const errors = [...manifestErrors, ...legacyErrors, ...shotErrors];

  if (errors.length) {
    throw new Error(`proof-gallery link-check failed (AC5):\n  - ${errors.join('\n  - ')}`);
  }

  const proofs = allProofs.filter((p) => p.journey === journey);
  const shots = allShots.filter((s) => s.journey === journey);

  proofs.sort((a, b) => (a.legacy === b.legacy ? 0 : a.legacy ? -1 : 1));

  mkdirSync(outDir, { recursive: true });

  if (proofs.length) {
    mkdirSync(resolve(outDir, 'videos'), { recursive: true });
    for (const p of proofs) {
      const name = p.legacy ? `legacy-${basename(p.videoPath)}` : basename(p.videoPath);
      copyFileSync(p.videoPath, resolve(outDir, 'videos', name));
      p.videoSrc = `videos/${name}`;
    }
  }

  if (shots.length) {
    mkdirSync(resolve(outDir, 'screenshots'), { recursive: true });
    for (const s of shots) {
      const name = basename(s.imgPath);
      copyFileSync(s.imgPath, resolve(outDir, 'screenshots', name));
      s.imgSrc = `screenshots/${name}`;
    }
  }

  const maint = loadMaintainability(outDir, { showDelta: true });
  const generatedAt = new Date().toISOString();
  const html = renderPageHtml({
    journey,
    proofs,
    shots,
    maint,
    generatedAt,
    branch,
  });

  const outFile = resolve(outDir, 'index.html');
  writeFileSync(outFile, html, 'utf8');

  writeMaintainabilityIndex(outDir);

  return { html, outFile, journey, generatedAt, proofs, shots };
}

export function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--report') out.reportPath = argv[++i];
    else if (a === '--out') out.outDir = argv[++i];
    else if (a === '--branch') out.branch = argv[++i];
    else if (a === '--legacy-video') out.legacyVideoDir = argv[++i];
    else if (a === '--screenshots') out.screenshotsDir = argv[++i];
    else if (a === '--journey-under-work') out.journeyUnderWork = argv[++i];
    else if (a === '--site-base') out.siteBase = argv[++i];
  }
  return out;
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  const args = parseArgs(process.argv.slice(2));
  const reportPath = args.reportPath ?? resolve(__dirname, 'fixtures', 'proof-manifest.json');
  const outDir = args.outDir ?? resolve(__dirname, '..', '..', 'public', 'alpenflight', 'proof');
  const legacyVideoDir = args.legacyVideoDir ?? resolve(__dirname, 'fixtures', 'legacy-video');
  const screenshotsDir = args.screenshotsDir ?? resolve(__dirname, 'fixtures', 'screenshots');

  const fixtureMaint = resolve(__dirname, 'fixtures', 'maintainability');
  const outMaint = resolve(outDir, 'maintainability');
  if (!existsSync(outMaint) && existsSync(fixtureMaint)) {
    mkdirSync(outMaint, { recursive: true });
    for (const f of [
      'fallow-audit.json',
      'fallow-health.json',
      'pmd-main.xml',
      'cpd-check.xml',
      'qodana-report.sarif.json',
    ]) {
      const src = resolve(fixtureMaint, f);
      if (existsSync(src)) copyFileSync(src, resolve(outMaint, f));
    }
  }
  try {
    const { outFile, journey, generatedAt, proofs, shots } = generateGallery({
      reportPath,
      outDir,
      branch: args.branch,
      legacyVideoDir,
      screenshotsDir,
      ...(args.journeyUnderWork ? { journeyUnderWork: args.journeyUnderWork } : {}),
      ...(args.siteBase ? { siteBase: args.siteBase } : {}),
    });
    console.log(`proof-gallery: wrote ${outFile}`);
    console.log(
      `  journey ${journey}: ${proofs.length} green proof video(s); ${shots.length} parity screenshot(s).`,
    );
    // ext: prefix sed-parsed by ci.yml + proof-fanout → GALLERY_EXPECT_GENERATED_AT
    console.log(`proof-gallery: generated-at ${generatedAt}`);
  } catch (err) {
    console.error(`proof-gallery: ${err.message}`);
    process.exit(1);
  }
}
