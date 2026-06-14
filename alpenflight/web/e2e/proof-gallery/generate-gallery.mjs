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
 * Site-root-absolute base for the gh-pages deployment. gh-pages serves this repo
 * at `https://elekktrisch.github.io/fls/`, so the base path is `/fls/`. Used for
 * the ONE cross-deploy link a per-journey page must reach regardless of its own
 * deploy depth: the persistent previews index at `<base>alpenflight/previews/`.
 *
 * Why absolute (not relative): a per-journey page is emitted into TWO layouts —
 * the canonical `alpenflight/proof/J-<n>/` and the branch-preview
 * `alpenflight/proof-preview/<branch>/J-<n>/` (one dir deeper). A relative
 * `../../previews/` resolves correctly in neither/only one of them. A
 * site-root-absolute path is depth-independent and works in both. Override via
 * `generateGallery({ siteBase })` / `--site-base` if the repo's gh-pages base
 * ever changes (must keep the trailing slash).
 */
export const DEFAULT_SITE_BASE = '/fls/';

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
  'J-26', // hardening sprint — ships before J-8 (order ≠ id), mirrors _ORDER.md
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

/**
 * J-26 T-26 — the SINGLE SOURCE OF TRUTH for "what the gallery renders".
 *
 * `renderScreenshots` renders exactly one `shot-<side>` figure per `extractScreenshots`
 * shot (grouped by view). This helper projects that SAME shot list to the set of
 * `<side>:<view>` keys the generator WILL render for a given journey — so the
 * pre-deploy SHOTS-PRESENT guard can assert against IDENTICALLY the keys the page
 * carries, not an independently-globbed PNG set. Before this, the guard counted
 * `screenshots.json` entries with a PNG on disk while the generator rendered the
 * `extractScreenshots` shots; the two reads could disagree (a one-sided render
 * passed the guard while the page showed the other side as "pending" — the J-7
 * staged-≠-rendered drift). Same function, two callers ⇒ present == rendered.
 *
 * A shot with no on-disk PNG is NOT a rendered key here — but the generator's AC5
 * link-check throws on a declared-yet-missing PNG before any HTML is written, so a
 * green gallery's rendered set never contains a missing-PNG shot anyway. Filtering
 * on `imgPath` existence keeps this helper honest when called on a raw (pre-AC5)
 * shot list (the guard's path).
 *
 * @param {Array<{journey?: string, side?: string, view?: string, imgPath?: string}>} shots
 *   the `extractScreenshots(...).shots` array (or a subset).
 * @param {string} [journey] when set, only that journey's keys are returned.
 * @returns {Set<string>} the `<side>:<view>` keys the generator renders.
 */
export function renderedShotKeys(shots, journey) {
  const keys = new Set();
  for (const s of shots ?? []) {
    if (journey && s.journey !== journey) continue;
    // Only a PNG actually on disk is a rendered <img> (mirrors the generator: a
    // missing PNG fails AC5 before render, so it never reaches the page).
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

/**
 * Resolve a per-run-gallery manifest href (relative to `/alpenflight/proof/`) to a
 * site-root-absolute URL so it resolves in BOTH the canonical and branch-preview
 * deploy layouts. An already-absolute (`/…`) or external (`scheme:`/`//`) href is
 * passed through unchanged.
 */
export function navGalleryHref(href, siteBase = DEFAULT_SITE_BASE) {
  if (/^(?:[a-z]+:|\/\/|\/)/i.test(href)) return href;
  return `${siteBase}alpenflight/proof/${href}`;
}

function renderNavBlock(navGalleries, siteBase = DEFAULT_SITE_BASE) {
  if (!navGalleries.length) return '';
  // The per-run galleries (`per-run-galleries.json`) are deployed ONLY under the
  // CANONICAL `alpenflight/proof/` (the fanout's `j-0c-fanout/` etc. land there,
  // never under a branch preview). Their manifest `href`s are relative to
  // `/alpenflight/proof/`, so on the canonical all-journeys page a bare `href`
  // resolves; but the SAME page is also deployed to the branch-preview path
  // (`alpenflight/proof-preview/<branch>/legacy-parity/`), where the relative
  // `j-0c-fanout/` 404s (T-35). Resolve each manifest href SITE-ROOT-ABSOLUTE
  // against the canonical proof root so it works in BOTH deploy layouts.
  const links = navGalleries
    .map(
      (g) =>
        `      &rarr; <a href="${esc(navGalleryHref(g.href, siteBase))}"><strong>${esc(g.label)}</strong></a>` +
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

/**
 * One-line summary caption for a journey's `<summary>`. Prefers the journey's
 * first proof caption (trimmed to one line), else the first screenshot caption,
 * else a pending note. Kept short so the collapsed accordion stays scannable.
 */
function summaryCaption(proofs, shots) {
  const first = (proofs && proofs[0]?.caption) || (shots && shots[0]?.caption) || '';
  const oneLine = String(first).replace(/\s+/g, ' ').trim();
  if (!oneLine) return 'No green proof yet — not shipped a captioned pass-video.';
  return oneLine.length > 110 ? `${oneLine.slice(0, 109)}…` : oneLine;
}

/** "2 videos · 7 screenshots" style count for the `<summary>` line. */
function summaryCounts(nVideos, nShots) {
  const parts = [];
  parts.push(`${nVideos} video${nVideos === 1 ? '' : 's'}`);
  parts.push(`${nShots} screenshot${nShots === 1 ? '' : 's'}`);
  return parts.join(' · ');
}

/**
 * Render the inner content (videos + screenshots) of a journey — everything
 * that lives INSIDE its accordion `<details>`. The `<details>`/`<summary>`
 * wrapper is added by `renderHtml`; this stays a presentation-only split so the
 * video/screenshot rendering + the AC4/AC5 link-checks are untouched.
 */
function renderJourneyBody(jid, proofs, shots) {
  // Pending — no green video and no declared screenshot.
  if ((!proofs || proofs.length === 0) && (!shots || shots.length === 0)) {
    return `        <p>No green proof yet — this journey has not shipped a captioned pass-video.</p>`;
  }
  // Screenshots but no green video — render the screenshot block only.
  if (!proofs || proofs.length === 0) {
    return renderScreenshots(shots);
  }
  const videos = proofs
    .map((p) => {
      const tag = p.acTag ? `<span class="status ${tagClass(p.acTag)}">${esc(p.acTag)}</span>` : '';
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
  return `        <div class="proofs">
${videos}
        </div>
${screenshotsBlock}`;
}

/* ─────────────────────────── Maintainability panel ───────────────────────────
 * T-13a — a compact maintainability summary per per-journey page, read from
 * T-12's 4 artifacts under `<outDir>/maintainability/`:
 *   - fallow-audit.json   FE journey DELTA (changed-files-vs-main envelope;
 *                         `attribution.{dead_code,complexity,duplication}_introduced`
 *                         = what THIS branch's diff added, + a `verdict`)
 *   - fallow-health.json  FE repo SNAPSHOT (`health_score.{score,grade}`,
 *                         `vital_signs.{maintainability_avg,duplication_pct,dead_file_pct}`)
 *   - pmd-main.xml        BE complexity/dead-code violation count (one <violation> each)
 *   - cpd-check.xml       BE duplication (sum of <duplication tokens> over total tokens)
 *   - qodana-report.sarif.json  BE whole-program unused-declaration scan (J-8 T-15);
 *                         SARIF results count + new-vs-baseline (report-only ratchet)
 *
 * FAIL-SOFT: every artifact may be ABSENT on a given run (the T-12 producer is
 * `continue-on-error`). A missing/malformed file becomes `null` and renders
 * "— / no data"; this never throws (the panel is informational, not a gate).
 *
 * SCOPE HONESTY: the fallow audit is the CURRENT branch's diff-vs-main, so the
 * journey-DELTA shown is only reconstructable for the journey under work. We show
 * that delta on the current journey's page and the repo SNAPSHOT on every page;
 * the per-journey historical delta for already-shipped J-0..J-4 isn't
 * reconstructable, so those pages show the snapshot + a note (no false delta).
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
 *   { verdict, dead_code_introduced, complexity_introduced, duplication_introduced }
 * or null if the file is absent/unparseable. Tolerates a partial shape (missing
 * `attribution` → counts default 0).
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
 * We report the clone-group count + a duplication % = duplicated-tokens /
 * total-tokens (a coarse but honest proxy — CPD counts each clone group's tokens
 * once; the file totals sum the corpus). Returns { groups, dupPct } or null.
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
 * Parse the BE Qodana SARIF report (the whole-program unused-declaration scan,
 * J-8 T-15). Qodana emits SARIF 2.1.0: findings live in `runs[].results[]`. When
 * a `--baseline` is applied each result carries a `baselineState` ∈ {new,
 * unchanged, absent} — `new` is the ratchet signal (a finding NOT in the
 * committed baseline = NEW unused code this branch introduced). Without a
 * baseline every result is just counted as `total`. Returns
 *   { total, newFindings }  (newFindings = NEW vs the baseline, or null when no
 *   baselineState is present — i.e. the baseline wasn't applied this run)
 * or null if the file is absent/unparseable. JSON-only, no SARIF dep — the
 * report is informational (fail-soft), same posture as the PMD/CPD/fallow rows.
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
 * `showDelta` flags whether THIS page is the journey-under-work (whose fallow
 * audit delta is the current branch's diff — see SCOPE HONESTY above).
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
 * Compute the green/amber/red roll-up for the panel. Driven by the FE audit
 * DELTA (what this branch introduced): green if it introduced no new
 * complexity/dupes/dead-code, amber if it introduced any, red if the audit's own
 * verdict is `fail`. With no delta available (audit absent OR this isn't the
 * journey-under-work) → neutral "snapshot only". Returns { level, label }.
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
 * Render the Maintainability panel HTML for one per-journey page. Always renders
 * (even with zero artifacts — then it's an honest "no data this run"). `reportHref`
 * links to the full reports dir. Scoped to the journey whose page it's on.
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

  // FE delta row — only meaningful on the journey-under-work page.
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

  // BE Qodana (J-8 T-15) — whole-program unused-declaration scan. `newFindings`
  // is the ratchet signal (NEW unused vs the committed baseline); `null` means
  // no baseline was applied this run (then we only show the total). The report
  // is REPORT-ONLY — a non-zero count never gates; this row is informational.
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
 * only on the open-state accent bar). Used by BOTH the single all-journeys page
 * (`renderHtml`) and each per-journey page (`renderJourneyPageHtml`) so they read
 * as one system. Maintainability-panel rules are appended at the bottom.
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
/* Accordion — one native <details> per journey. ADR 0024: flat (1px slate
   border, NO shadow), sharp corners (radius 0), restrained motion (the native
   toggle only — no slide). The brand color lands only on the open-state accent
   bar, never on chrome backgrounds. */
.journey {
  background: var(--surface); border: 1px solid var(--border);
  border-radius: 0; padding: 0; margin: 0 0 -1px;
}
.journey + .journey { margin-top: 0; }
.journey > summary {
  list-style: none; cursor: pointer; user-select: none;
  display: flex; flex-direction: column; gap: .25rem;
  padding: .9rem 1.1rem .9rem 2.1rem; position: relative;
  background: var(--surface);
  border-left: 2px solid transparent; transition: opacity 120ms ease-out;
}
.journey > summary::-webkit-details-marker { display: none; }
/* CSS triangle marker — rotates on open (native toggle, no JS, no slide). */
.journey > summary::before {
  content: ''; position: absolute; left: 1rem; top: 1.25rem;
  width: 0; height: 0;
  border-left: 5px solid var(--muted);
  border-top: 4px solid transparent; border-bottom: 4px solid transparent;
  transform: rotate(0deg); transform-origin: 25% 50%;
}
.journey[open] > summary::before { transform: rotate(90deg); }
.journey[open] > summary { border-left-color: var(--primary); }
.journey > summary:hover { background: var(--surface-2); }
.journey > summary:focus-visible { outline: 2px solid var(--primary); outline-offset: -2px; }
.summary-head { display: flex; align-items: center; gap: .6rem; font-size: 1.05rem; }
.summary-jid { font-weight: 500; }
.summary-counts { color: var(--muted); font-size: .8rem; font-weight: 400; }
.summary-caption { color: var(--muted); font-size: .85rem; }
/* The body (videos / screenshots / pending note) sits inside the <details>. */
.journey > .proofs,
.journey > .parity-screenshots,
.journey > p { margin-left: 1.1rem; margin-right: 1.1rem; }
.journey[open] { padding-bottom: 1.1rem; }
.pending-journey { opacity: .85; }
.pending-journey > summary { cursor: default; }
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
/* Maintainability panel — flat, table-of-numbers; pill carries the roll-up. */
.maintainability { margin-top: 1.5rem; background: var(--surface); border: 1px solid var(--border); border-radius: 0; padding: 1rem 1.1rem; }
.maintainability h4 { margin: 0 0 .75rem; font-size: 1rem; font-weight: 500; display: flex; align-items: center; gap: .6rem; }
.maint-table { width: 100%; border-collapse: collapse; font-size: .88rem; }
.maint-table th { text-align: left; font-weight: 500; color: var(--muted); padding: .4rem .75rem .4rem 0; white-space: nowrap; vertical-align: top; width: 1%; }
.maint-table td { padding: .4rem 0; color: var(--text); border-top: 1px solid var(--border); }
.maint-table tr:first-child td, .maint-table tr:first-child th { border-top: 0; }
.maint-table td.muted { color: var(--muted); }
.back-link { font-size: .85rem; }
footer { margin-top: 3rem; color: var(--muted); font-size: .85em; }`;

/**
 * Render one self-contained per-journey page (T-13a). Same CSS/flat look as the
 * single all-journeys page, but scoped to ONE journey: all its videos + paired
 * legacy↔AlpenFlight screenshots (the pairing logic is reused verbatim via
 * `renderJourneyBody`) + the Maintainability panel. Written to
 * `<outDir>/J-<n>/index.html` — see OUTPUT PATH SCHEME in `generateGallery`.
 *
 * The journey's video/screenshot `src`s are rewritten to `../videos/…` /
 * `../screenshots/…` (one level up) because the shared media dirs live at the
 * gallery `--out` root, NOT inside the per-journey subdir — so a per-journey page
 * reuses the same copied assets without re-copying them.
 */
export function renderJourneyPageHtml({
  jid,
  proofs,
  shots,
  maint,
  generatedAt,
  branch,
  journeyUnderWork,
  siteBase = DEFAULT_SITE_BASE,
}) {
  const nVideos = proofs ? proofs.length : 0;
  const nShots = shots ? shots.length : 0;
  const hasContent = nVideos > 0 || nShots > 0;

  // Per-journey pages live one dir below the media root → prefix asset srcs.
  const upProofs = (proofs ?? []).map((p) => ({ ...p, videoSrc: `../${p.videoSrc}` }));
  const upShots = (shots ?? []).map((s) => ({ ...s, imgSrc: `../${s.imgSrc}` }));

  const body = hasContent
    ? renderJourneyBody(jid, upProofs, upShots)
    : `        <p>No green proof yet — this journey has not shipped a captioned pass-video.</p>`;

  const panel = renderMaintainabilityPanel(maint, {
    reportHref: '../maintainability/',
    journeyUnderWork,
  });

  let statusPill;
  if (!hasContent) statusPill = '<span class="status pending">pending</span>';
  else if (nVideos === 0) statusPill = '<span class="status success">parity screenshots</span>';
  else
    statusPill = `<span class="status success">${nVideos} proof${nVideos === 1 ? '' : 's'}</span>`;
  const counts = hasContent
    ? ` <span class="summary-counts">${esc(summaryCounts(nVideos, nShots))}</span>`
    : '';

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>AlpenFlight proof — ${esc(jid)}</title>
<style>
${GALLERY_CSS}
</style>
</head>
<body>
<div class="container">
  <header>
    <h1>${esc(jid)} — proof</h1>
    <p class="meta">
      <span class="summary-jid">${esc(jid)}</span> ${statusPill}${counts}
    </p>
    <p class="meta">
      Green <code>real-idp</code> proof pass-videos + paired legacy &harr; AlpenFlight screenshots
      for this journey. Generated on <code>${esc(branch)}</code> &middot; ${esc(generatedAt)}
    </p>
    <p class="meta back-link" style="margin-top:.5rem;">
      <a href="${esc(siteBase)}alpenflight/previews/">&larr; all journeys (previews index)</a> &middot;
      <a href="../">all-journeys gallery</a>
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

function renderHtml({
  byJourney,
  shotsByJourney,
  roadmap,
  generatedAt,
  branch,
  navGalleries = [],
  siteBase = DEFAULT_SITE_BASE,
}) {
  // Default-open policy: open the NEWEST journey that has content (the last
  // roadmap-ordered journey with a green video or a declared screenshot), and
  // collapse every older one. A CI-run gallery opens straight onto the journey
  // just shipped; pending journeys are never auto-opened.
  let newestWithContentIdx = -1;
  roadmap.forEach((jid, i) => {
    const proofs = byJourney.get(jid);
    const shots = shotsByJourney?.get(jid);
    if ((proofs && proofs.length) || (shots && shots.length)) newestWithContentIdx = i;
  });

  const sections = roadmap
    .map((jid, i) => {
      const proofs = byJourney.get(jid);
      const shots = shotsByJourney?.get(jid);
      const nVideos = proofs ? proofs.length : 0;
      const nShots = shots ? shots.length : 0;
      const hasContent = nVideos > 0 || nShots > 0;

      // Status pill mirrors the pre-accordion labels.
      let statusPill;
      if (!hasContent) statusPill = '<span class="status pending">pending</span>';
      else if (nVideos === 0) statusPill = '<span class="status success">parity screenshots</span>';
      else
        statusPill = `<span class="status success">${nVideos} proof${nVideos === 1 ? '' : 's'}</span>`;

      const journeyClass = hasContent ? 'journey' : 'journey pending-journey';
      const open = i === newestWithContentIdx ? ' open' : '';
      const counts = hasContent
        ? `<span class="summary-counts">${esc(summaryCounts(nVideos, nShots))}</span>`
        : '';
      const caption = `<span class="summary-caption">${esc(summaryCaption(proofs, shots))}</span>`;
      const body = renderJourneyBody(jid, proofs, shots);

      return `      <details class="${journeyClass}"${open}>
        <summary>
          <span class="summary-head"><span class="summary-jid">${esc(jid)}</span> ${statusPill}${counts}</span>
          ${caption}
        </summary>
${body}
      </details>`;
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
${GALLERY_CSS}
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
      <a href="${esc(siteBase)}alpenflight/previews/">&larr; all journeys (previews index)</a>
    </p>${renderNavBlock(navGalleries, siteBase)}
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
 * @param {boolean} [o.perJourney=true] Also emit one self-contained page PER
 *   journey-with-content (the T-13a re-arch). OUTPUT PATH SCHEME: each lands at
 *   `<outDir>/J-<n>/index.html` (e.g. `…/proof/J-5/index.html`); its videos +
 *   screenshots reference the SHARED `<outDir>/videos/…` + `<outDir>/screenshots/…`
 *   via `../`. The all-journeys `<outDir>/index.html` is still written (callers /
 *   the legacy index depend on it) — per-journey pages are ADDITIVE. T-13b's
 *   index links each journey to `J-<n>/`.
 * @param {string} [o.journeyUnderWork] The journey whose fallow-audit DELTA is the
 *   current branch's diff (e.g. `J-5`) — only THAT per-journey page shows the FE
 *   delta; every other page shows the repo snapshot only (the historical
 *   per-journey delta isn't reconstructable). Defaults from the branch label.
 * @returns {{ html: string, outFile: string, proofs: Array, shots: Array, roadmap: string[], journeyPages: Array<{journey: string, outFile: string}> }}
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
  perJourney = true,
  journeyUnderWork = journeyFromFile(branch),
  siteBase = DEFAULT_SITE_BASE,
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
    siteBase,
  });

  mkdirSync(outDir, { recursive: true });
  const outFile = resolve(outDir, 'index.html');
  writeFileSync(outFile, html, 'utf8');

  // ── Per-journey pages (T-13a) ─────────────────────────────────────────────
  // One self-contained page per journey-WITH-content, at <outDir>/J-<n>/index.html.
  // Pending journeys (no video, no screenshot) get NO page — T-13b's index links
  // only the journeys that have a page (and shows the rest as pending rows).
  const journeyPages = [];
  if (perJourney) {
    const generatedAt = new Date().toISOString();
    for (const jid of roadmap) {
      const jProofs = byJourney.get(jid);
      const jShots = shotsByJourney.get(jid);
      const hasContent = (jProofs && jProofs.length) || (jShots && jShots.length);
      // Emit a per-journey page for any journey WITH content, AND — per the
      // operator's standing T-01b rule — a "pending" placeholder page for the
      // journey-under-work even with ZERO captures, so its glanceable proof
      // window exists from the journey's first task and ACCUMULATES captures as
      // later specs go green. `renderJourneyPageHtml` already renders the
      // content-less case as a pending page (no broken/empty link). Other
      // content-less journeys get NO page (the index shows them as plain pending
      // rows — never a dead link).
      if (!hasContent && jid !== journeyUnderWork) continue;
      // The maintainability panel: read the (possibly absent) artifacts once per
      // page. Only the journey-under-work shows its FE audit DELTA; others show
      // the repo snapshot (showDelta=false).
      const maint = loadMaintainability(outDir, { showDelta: jid === journeyUnderWork });
      const pageHtml = renderJourneyPageHtml({
        jid,
        proofs: jProofs,
        shots: jShots,
        maint,
        generatedAt,
        branch,
        journeyUnderWork: jid === journeyUnderWork ? jid : undefined,
        siteBase,
      });
      const jDir = resolve(outDir, jid);
      mkdirSync(jDir, { recursive: true });
      const jFile = resolve(jDir, 'index.html');
      writeFileSync(jFile, pageHtml, 'utf8');
      journeyPages.push({ journey: jid, outFile: jFile });
    }
    // The per-journey panel's "Full maintainability reports →" link targets the
    // `maintainability/` DIRECTORY (`../maintainability/`). gh-pages does not
    // serve a bare directory listing, so a dir URL with no index.html 404s even
    // when its JSON/XML artifacts deploy fine. Emit a tiny index.html listing
    // whichever of the 4 artifacts exist so that dir URL serves 200. Only when
    // artifacts are present — when none were emitted the panel renders "no data"
    // and shows NO reports link, so a dead index would be pointless.
    writeMaintainabilityIndex(outDir);
  }

  return { html, outFile, proofs, shots, roadmap, journeyPages };
}

/**
 * Emit `<outDir>/maintainability/index.html` listing whichever of the 4
 * maintainability artifacts are present, so the per-journey panel's
 * `../maintainability/` directory link serves 200 on gh-pages (which won't
 * render a directory listing). No-op when the dir is absent or carries none of
 * the artifacts (then the panel shows "no data" and emits no reports link).
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
  <p>Raw artifacts behind the per-journey Maintainability panel.</p>
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

export function parseArgs(argv) {
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
    else if (a === '--no-per-journey') out.perJourney = false;
    else if (a === '--journey-under-work') out.journeyUnderWork = argv[++i];
    else if (a === '--site-base') out.siteBase = argv[++i];
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
  // Local-eyeball convenience for `pnpm proof:gallery`: if the out-root carries
  // no maintainability dir yet (CI stages the REAL T-12 artifacts there before
  // running the generator), seed it from the committed sample fixtures so the
  // panel renders with data locally. Never overwrites a real staged dir.
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
    const { outFile, proofs, shots, roadmap, journeyPages } = generateGallery({
      reportPath,
      outDir,
      orderPath,
      branch: args.branch,
      legacyVideoDir,
      screenshotsDir,
      renderNav: args.renderNav !== false,
      perJourney: args.perJourney !== false,
      ...(args.journeyUnderWork ? { journeyUnderWork: args.journeyUnderWork } : {}),
      ...(args.siteBase ? { siteBase: args.siteBase } : {}),
    });
    const pending = roadmap.filter((j) => !proofs.some((p) => p.journey === j));
    console.log(`proof-gallery: wrote ${outFile}`);
    console.log(
      `  ${proofs.length} green proof video(s); ${shots.length} parity screenshot(s); ${pending.length} pending journey(s).`,
    );
    if (journeyPages.length) {
      console.log(`  ${journeyPages.length} per-journey page(s):`);
      for (const jp of journeyPages) console.log(`    ${jp.journey} → ${jp.outFile}`);
    }
  } catch (err) {
    console.error(`proof-gallery: ${err.message}`);
    process.exit(1);
  }
}
