#!/usr/bin/env node
/**
 * Proof-video gallery generator — ONE stable-bookmark page, in-flight journey only.
 *
 * Reads a Playwright JSON-reporter report (the "manifest" — see README.md for the
 * exact shape), pairs each passing proof test's `proof-video` .webm with its
 * `proof-caption`/`proof-ac-tag`/`proof-journey` annotations, and emits ONE
 * `index.html` rendering the journey-under-work's proof only: its paired legacy↔
 * AlpenFlight screenshots (when present), its pass-video(s), and — read from the
 * same report — its migration round-trip proof. Merged journeys' proof lives in
 * their PRs; this page never renders history or an all-journeys index.
 *
 * Why one page: the operator bookmarks a single URL and sees only what is
 * in-flight. The persistent multi-journey directory + per-journey history pages +
 * the per-context sub-path split were the bookmark pain this collapses.
 *
 * AC5 link-check (the [key-error] path — runs fully real, never mocked): the
 * generator throws (CLI: exits non-zero) if a published video/screenshot has no
 * caption, or a caption references a .webm/.png not present in the proof output.
 *
 * Dual use:
 *   - CLI:    node generate-gallery.mjs --journey-under-work J-N [--report <json>] [--out <dir>] [--legacy-video <dir>] [--screenshots <dir>]
 *             (also: `pnpm proof:gallery`).
 *   - import: `import { generateGallery } from './generate-gallery.mjs'`.
 */
import { readFileSync, writeFileSync, mkdirSync, existsSync, copyFileSync } from 'node:fs';
import { dirname, resolve, basename } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));

/**
 * Site-root-absolute base for the gh-pages deployment. gh-pages serves this repo
 * at `https://elekktrisch.github.io/fls/`, so the base path is `/fls/`. Override
 * via `generateGallery({ siteBase })` / `--site-base` if the repo's gh-pages base
 * ever changes (must keep the trailing slash).
 */
export const DEFAULT_SITE_BASE = '/fls/';

/**
 * Static roadmap fallback — the journey IDs the roadmap parser yields when
 * `_ORDER.md` is not reachable. Source of truth is
 * docs/modernization/stories/_ORDER.md. Retained so the journey-id ordering
 * helpers run standalone in a CI artifact dir without the repo.
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
  'J-26',
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
  'J-25',
];

/**
 * Parse the journey ids out of the `| … | J-N | …` roadmap table rows of an
 * _ORDER.md body, in table order. Exported (text-in, pure) so the ordering
 * contract is unit-testable without a temp file. The leading table cell may carry
 * decoration before the id — a shipped journey is marked `✅ ` (`| ✅ **J-0** |`,
 * `| ✅ J-24 |`) and ids are optionally bold (`**J-0**`); the regex skips that so a
 * shipped journey parses in its roadmap position.
 */
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
      if (result.status !== 'passed') continue; // only publish green proofs

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

/**
 * Extract declared LEGACY parity videos from a `legacy-video.json` sidecar in
 * `legacyVideoDir`. The Playwright report only carries AlpenFlight `real-idp`
 * proofs; a legacy (e.g. flsweb) parity video has no manifest path, so it is
 * declared in a sidecar keyed to a journey:
 *
 *   { "videos": [ { "journey": "J-0c", "file": "x.webm",
 *                   "acTag": "happy", "caption": "Legacy flsweb: …" } ] }
 *
 * `file` resolves relative to the sidecar dir. Returns the same proof shape as
 * `extractProofs`, flagged `legacy: true`, plus the AC5 link-check `errors`. A
 * missing dir / sidecar is a no-op (no legacy video this run).
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
 * Extract declared PARITY SCREENSHOTS from a `screenshots.json` sidecar in
 * `screenshotsDir`. Mirrors `extractLegacyVideos`: still PNGs (legacy + AlpenFlight,
 * list + form) have no Playwright-manifest path, so they are DECLARED in a sidecar
 * keyed to a journey + side + view so the generator can PAIR them:
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
 * link-check `errors`. A missing dir / sidecar is a no-op (no screenshots this run).
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

/**
 * The set of `<side>:<view>` keys the generator WILL render for a given journey —
 * the SINGLE SOURCE OF TRUTH shared with the pre-deploy SHOTS-PRESENT guard so its
 * "present" set is definitionally the page's "rendered" set (present == rendered).
 * `renderScreenshots` renders exactly one `shot-<side>` figure per shot; this
 * projects that SAME shot list to its keys. A shot with no on-disk PNG is not a
 * rendered key (mirrors the AC5 throw on a declared-yet-missing PNG before render).
 *
 * @param {Array<{journey?: string, side?: string, view?: string, imgPath?: string}>} shots
 * @param {string} [journey] when set, only that journey's keys are returned.
 * @returns {Set<string>} the `<side>:<view>` keys the generator renders.
 */
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

/**
 * Render the PARITY-SCREENSHOTS block: one row per `view` (e.g. list, form),
 * legacy `<img>` LEFT + AlpenFlight `<img>` RIGHT, so the operator eyeballs the
 * field set side by side. Views render in first-seen declaration order; within a
 * view, legacy is forced left. A view with only one side still renders that side.
 * Empty `shots` → no block.
 */
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

/**
 * Render the journey's proof body — the pass-video(s) + the paired parity
 * screenshots. A legacy parity video is labelled as the legacy side so a reviewer
 * reads legacy → AlpenFlight side by side. Content-less (the journey-under-work
 * before any capture lands) renders a pending note, never a broken link.
 */
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

/* ─────────────────────────── Maintainability panel ───────────────────────────
 * A compact maintainability summary on the page, read from the CI-staged
 * artifacts under `<outDir>/maintainability/`:
 *   - fallow-audit.json   FE journey DELTA (this branch's diff-vs-main envelope;
 *                         `attribution.{dead_code,complexity,duplication}_introduced`
 *                         + a `verdict`)
 *   - fallow-health.json  FE repo SNAPSHOT (`health_score`, `vital_signs`)
 *   - pmd-main.xml        BE complexity/dead-code violation count
 *   - cpd-check.xml       BE duplication (duplicated tokens over total tokens)
 *   - qodana-report.sarif.json  BE whole-program unused-declaration scan
 *
 * FAIL-SOFT: every artifact may be ABSENT on a given run (the producer is
 * `continue-on-error`). A missing/malformed file becomes `null` and renders
 * "— / no data"; this never throws (the panel is informational, not a gate). The
 * fallow audit is the CURRENT branch's diff-vs-main, so the journey-DELTA is only
 * reconstructable for the journey under work; the page shows that delta plus the
 * repo snapshot.
 */

/** Read+parse a JSON artifact; any failure (absent/malformed) → null. */
function readJsonSoft(absPath) {
  try {
    if (!existsSync(absPath)) return null;
    return JSON.parse(readFileSync(absPath, 'utf8'));
  } catch {
    return null;
  }
}

/** Read a text artifact (XML); any failure → null. */
function readTextSoft(absPath) {
  try {
    if (!existsSync(absPath)) return null;
    return readFileSync(absPath, 'utf8');
  } catch {
    return null;
  }
}

/**
 * Parse the FE fallow AUDIT (journey delta). Returns
 *   { verdict, deadIntroduced, complexityIntroduced, duplicationIntroduced }
 * or null if absent/unparseable. Tolerates a partial shape (missing `attribution`
 * → counts default 0).
 */
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

/**
 * Parse the FE fallow HEALTH (repo snapshot). Returns
 *   { score, grade, maintainability, duplicationPct, deadFilePct }
 * or null. Tolerates a partial shape (missing nested objects → "—" downstream).
 */
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

/**
 * Parse the BE PMD report (text XML, no XML dep — count `<violation` and the
 * complexity/dead-code subsets by their `rule="…"` attribute). Returns
 *   { total, complexity, deadCode } or null if absent.
 */
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

/**
 * Parse the BE CPD report (text XML). `<duplication tokens="N">` blocks are the
 * clones; `<file totalNumberOfTokens="N">` lines are the per-file token totals.
 * Reports the clone-group count + a duplication % = duplicated-tokens /
 * total-tokens. Returns { groups, dupPct } or null.
 */
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

/**
 * Parse the BE Qodana SARIF report (the whole-program unused-declaration scan).
 * Findings live in `runs[].results[]`. When a `--baseline` is applied each result
 * carries a `baselineState` ∈ {new, unchanged, absent} — `new` is the ratchet
 * signal. Without a baseline every result is just counted as `total`. Returns
 *   { total, newFindings }  (newFindings = NEW vs baseline, or null when no
 *   baselineState is present — i.e. the baseline wasn't applied this run)
 * or null if absent/unparseable. The report is informational (fail-soft).
 */
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

/**
 * Load + parse all maintainability artifacts from `<outDir>/maintainability/`.
 * Returns a structured summary where any absent artifact is `null`. Never throws.
 * `showDelta` flags whether THIS page shows the journey-under-work's fallow audit
 * delta (the current branch's diff).
 */
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

/**
 * Compute the green/amber/red roll-up for the panel. Driven by the FE audit DELTA
 * (what this branch introduced): green if it introduced no new complexity/dupes/
 * dead-code, amber if it introduced any, red if the audit's own verdict is `fail`.
 * With no delta available (audit absent OR not showing the delta) → neutral
 * "snapshot only". Returns { level, label }.
 */
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

/**
 * Render the Maintainability panel HTML. Always renders (even with zero artifacts
 * — then it's an honest "no data this run"). `reportHref` links to the reports
 * dir; the panel's delta is scoped to the journey under work.
 */
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

/**
 * Shared CSS (ADR 0024 flat look — slate neutrals, sharp corners, brand color
 * only on the open-state accent bar). Maintainability-panel rules are appended at
 * the bottom.
 */
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

/**
 * Render the single proof page for ONE journey — its pass-video(s) + paired
 * legacy↔AlpenFlight screenshots + the Maintainability panel. The asset `src`s are
 * relative to the page (`videos/…` / `screenshots/…` / `maintainability/…`), which
 * the out-root carries — so the published page is self-contained at any deploy depth.
 */
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

/**
 * Emit `<outDir>/maintainability/index.html` listing whichever of the artifacts
 * are present, so the panel's `maintainability/` directory link serves 200 on
 * gh-pages (which won't render a directory listing). No-op when the dir is absent
 * or carries none of the artifacts (then the panel shows "no data" and no link).
 */
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

/**
 * Generate the single-journey proof gallery page.
 * @param {object} o
 * @param {string} o.reportPath          Path to the Playwright JSON report (the manifest).
 * @param {string} o.outDir              Directory to write index.html (+ copied videos/screenshots) into.
 * @param {string} o.journeyUnderWork    The ONLY journey rendered (e.g. `J-11`). Required for a
 *   meaningful page; falls back to the branch label's derived journey, else `unknown`.
 * @param {string} [o.branch]            Branch label for the header.
 * @param {string} [o.legacyVideoDir]    Dir holding a `legacy-video.json` sidecar + its `.webm`(s).
 * @param {string} [o.screenshotsDir]    Dir holding a `screenshots.json` sidecar + its `.png`(s).
 * @param {string} [o.siteBase]          gh-pages base (default `/fls/`).
 * @returns {{ html: string, outFile: string, journey: string, proofs: Array, shots: Array }}
 * @throws on any AC5 link-check violation (no caption / missing .webm / missing .png).
 */
export function generateGallery({
  reportPath,
  outDir,
  branch = process.env.GITHUB_REF_NAME ?? 'local',
  journeyUnderWork = journeyFromFile(branch) ?? 'unknown',
  legacyVideoDir,
  screenshotsDir,
  siteBase = DEFAULT_SITE_BASE,
}) {
  void siteBase; // accepted for forward-compat; the single page uses only relative srcs
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

  // Render the in-flight journey ONLY; everything else stays in its own PR.
  const proofs = allProofs.filter((p) => p.journey === journey);
  const shots = allShots.filter((s) => s.journey === journey);

  // Within the journey, render the legacy parity video FIRST so the reviewer reads
  // legacy → AlpenFlight left-to-right (the side-by-side parity framing).
  proofs.sort((a, b) => (a.legacy === b.legacy ? 0 : a.legacy ? -1 : 1));

  mkdirSync(outDir, { recursive: true });

  // Copy each video into outDir/videos/ + rewrite src so the published page is
  // self-contained. Legacy copies are namespaced so a legacy .webm can never
  // collide with an AlpenFlight one of the same basename.
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
  const html = renderPageHtml({
    journey,
    proofs,
    shots,
    maint,
    generatedAt: new Date().toISOString(),
    branch,
  });

  const outFile = resolve(outDir, 'index.html');
  writeFileSync(outFile, html, 'utf8');

  // The panel's "Full reports →" link targets the maintainability/ DIRECTORY;
  // gh-pages 404s a bare dir, so emit a tiny index.html when artifacts are present.
  writeMaintainabilityIndex(outDir);

  return { html, outFile, journey, proofs, shots };
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

// CLI entrypoint (only when run directly, not when imported).
if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  const args = parseArgs(process.argv.slice(2));
  // Defaults wired for `pnpm proof:gallery`: build the committed fixtures into a
  // local out dir so a generator change can be eyeballed without CI.
  const reportPath = args.reportPath ?? resolve(__dirname, 'fixtures', 'proof-manifest.json');
  const outDir = args.outDir ?? resolve(__dirname, '..', '..', 'public', 'alpenflight', 'proof');
  const legacyVideoDir = args.legacyVideoDir ?? resolve(__dirname, 'fixtures', 'legacy-video');
  const screenshotsDir = args.screenshotsDir ?? resolve(__dirname, 'fixtures', 'screenshots');

  // Local-eyeball convenience: if the out-root carries no maintainability dir yet
  // (CI stages the REAL artifacts there before running the generator), seed it
  // from the committed sample fixtures so the panel renders with data locally.
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
    const { outFile, journey, proofs, shots } = generateGallery({
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
  } catch (err) {
    console.error(`proof-gallery: ${err.message}`);
    process.exit(1);
  }
}
