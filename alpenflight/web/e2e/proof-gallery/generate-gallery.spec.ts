import { existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

import { describe, expect, it } from 'vitest';

/**
 * Generator unit spec (T-08 boyscout rider) — locks the `_ORDER.md` roadmap
 * parser's ordering contract for SHIPPED (`✅`-prefixed) journeys.
 *
 * The bug it guards: `parseRoadmap`'s regex did not tolerate the `✅ ` prefix
 * `_ORDER.md` uses for shipped journeys (`| ✅ **J-0** |`, `| ✅ J-24 |`), so
 * those rows fell OUT of the parse. The `generateGallery` append-loop then
 * tacked any green-but-unparsed journey onto the BOTTOM of the gallery — the
 * operator's standing "J-0 not back" complaint.
 *
 * Pure JS tooling: no DB, no browser. We load the ESM generator via dynamic
 * `import()` and exercise the exported pure `parseRoadmapText`.
 */
const GENERATOR = resolve(__dirname, 'generate-gallery.mjs');

async function loadGenerator(): Promise<{
  parseRoadmapText: (text: string) => string[];
  generateGallery: (o: {
    reportPath: string;
    outDir: string;
    orderPath?: string;
    legacyVideoDir?: string;
    screenshotsDir?: string;
    renderNav?: boolean;
    perJourney?: boolean;
    journeyUnderWork?: string;
    branch?: string;
    siteBase?: string;
  }) => {
    html: string;
    roadmap: string[];
    proofs: { journey: string }[];
    shots: unknown[];
    journeyPages: { journey: string; outFile: string }[];
  };
  DEFAULT_SITE_BASE: string;
  navGalleryHref: (href: string, siteBase?: string) => string;
  parsePmd: (xml: string | null) => { total: number; complexity: number; deadCode: number } | null;
  parseCpd: (xml: string | null) => { groups: number; dupPct: number | null } | null;
  parseFallowAudit: (json: unknown) => {
    verdict: string;
    deadIntroduced: number;
    complexityIntroduced: number;
    duplicationIntroduced: number;
  } | null;
  maintainabilityRollup: (m: {
    audit: {
      verdict: string;
      deadIntroduced: number;
      complexityIntroduced: number;
      duplicationIntroduced: number;
    } | null;
    showDelta: boolean;
  }) => { level: string; label: string };
  parseArgs: (argv: string[]) => {
    reportPath?: string;
    outDir?: string;
    orderPath?: string;
    branch?: string;
    journeyUnderWork?: string;
    siteBase?: string;
    renderNav?: boolean;
    perJourney?: boolean;
  };
}> {
  return import(pathToFileURL(GENERATOR).href);
}

// A faithful slice of the real `_ORDER.md` table: both ✅-prefix forms appear —
// bolded (`✅ **J-0**`) and un-bolded (`✅ J-24`) — interleaved with plain rows.
const ORDER_TABLE = `# Journey roadmap

| J | Title | Epic |
|---|---|---|
| ✅ **J-0** | **Locations CRUD** | E-06 |
| ✅ **J-0b** | **Migration fan-out** | E-02 |
| **J-0c** | Fan-out parity | E-02 |
| J-1 | Aircraft register | E-06 |
| J-2 | Flight list | E-07 |
| ✅ J-24 | Proof-video gallery | E-13 |
| ✅ J-25 | Proof-gallery previews | E-13 |
`;

describe('parseRoadmapText — ✅-prefix ordering', () => {
  it('parses a ✅-prefixed bolded row to its bare J-NN id', async () => {
    const { parseRoadmapText } = await loadGenerator();
    expect(parseRoadmapText('| ✅ **J-0** | x | y |')).toEqual(['J-0']);
  });

  it('parses a ✅-prefixed un-bolded row to its bare J-NN id', async () => {
    const { parseRoadmapText } = await loadGenerator();
    expect(parseRoadmapText('| ✅ J-24 | x | y |')).toEqual(['J-24']);
  });

  it('keeps shipped journeys in roadmap-table order — J-0 first, not appended last', async () => {
    const { parseRoadmapText } = await loadGenerator();
    expect(parseRoadmapText(ORDER_TABLE)).toEqual([
      'J-0',
      'J-0b',
      'J-0c',
      'J-1',
      'J-2',
      'J-24',
      'J-25',
    ]);
  });

  it('drops nothing — every J-NN row (✅ or plain) is present exactly once', async () => {
    const { parseRoadmapText } = await loadGenerator();
    const ids = parseRoadmapText(ORDER_TABLE);
    for (const id of ['J-0', 'J-0b', 'J-0c', 'J-1', 'J-2', 'J-24', 'J-25']) {
      expect(
        ids.filter((x) => x === id),
        `${id} present once`,
      ).toHaveLength(1);
    }
  });

  it('does not false-match the Replaces-legacy cell (only the first cell)', async () => {
    const { parseRoadmapText } = await loadGenerator();
    // A J-NN-looking token in a LATER cell must not be picked up as a row id.
    expect(parseRoadmapText('| ✅ **J-0** | Locations | masterdata/J-99/ |')).toEqual(['J-0']);
  });
});

describe('generateGallery — shipped journeys render in roadmap order', () => {
  it('orders a green shipped journey by its roadmap position, not at the bottom', async () => {
    const { generateGallery } = await loadGenerator();
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-order-'));
    const orderPath = resolve(dir, '_ORDER.md');
    writeFileSync(orderPath, ORDER_TABLE, 'utf8');

    // A report whose only green proof is for the SHIPPED journey J-0. The bug
    // would drop J-0 from the parse and append it after J-25; the fix keeps it
    // first.
    const reportPath = resolve(dir, 'report.json');
    writeFileSync(
      reportPath,
      JSON.stringify({ suites: [] }), // no manifest proofs needed for the ordering assertion
      'utf8',
    );

    const { roadmap } = generateGallery({
      reportPath,
      outDir: resolve(dir, 'out'),
      orderPath,
      renderNav: false,
    });

    expect(roadmap[0]).toBe('J-0');
    expect(roadmap).toEqual(['J-0', 'J-0b', 'J-0c', 'J-1', 'J-2', 'J-24', 'J-25']);
  });
});

/**
 * T-20 guard — the legacy↔AlpenFlight parity SCREENSHOT block. Locks:
 *   (a) a declared screenshot renders an <img>;
 *   (b) legacy + alpenflight pair under the same journey/view;
 *   (c) a declared PNG missing on disk fails the AC5 link-check (same bar as
 *       a missing video).
 *
 * Pure JS tooling: a synthetic screenshots.json sidecar + 1×1 PNG bytes on disk.
 * No browser, no report proofs needed (an empty `{ suites: [] }` report is a
 * valid no-proof manifest).
 */
// Smallest valid PNG (1×1 transparent) — enough for existsSync + copyFileSync.
const PNG_1X1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=',
  'base64',
);

/** Stand up a sidecar dir with a screenshots.json + the named PNGs on disk. */
function makeShotDir(
  base: string,
  decls: { journey: string; side: string; view: string; file: string; caption: string }[],
  opts: { writeFiles?: string[] } = {},
): string {
  const dir = mkdtempSync(resolve(base, 'shots-'));
  const toWrite = opts.writeFiles ?? decls.map((d) => d.file);
  for (const f of toWrite) writeFileSync(resolve(dir, f), PNG_1X1);
  writeFileSync(resolve(dir, 'screenshots.json'), JSON.stringify({ screenshots: decls }), 'utf8');
  return dir;
}

function emptyReport(dir: string): string {
  const p = resolve(dir, 'report.json');
  writeFileSync(p, JSON.stringify({ suites: [] }), 'utf8');
  return p;
}

describe('generateGallery — parity screenshots (T-20)', () => {
  const J1_SHOTS = [
    {
      journey: 'J-1',
      side: 'legacy',
      view: 'list',
      file: 'legacy-aircraft-list.png',
      caption: 'Legacy flsweb: aircraft list',
    },
    {
      journey: 'J-1',
      side: 'alpenflight',
      view: 'list',
      file: 'alpenflight-aircraft-list.png',
      caption: 'AlpenFlight: aircraft list',
    },
    {
      journey: 'J-1',
      side: 'legacy',
      view: 'form',
      file: 'legacy-aircraft-form.png',
      caption: 'Legacy flsweb: aircraft form',
    },
    {
      journey: 'J-1',
      side: 'alpenflight',
      view: 'form',
      file: 'alpenflight-aircraft-form.png',
      caption: 'AlpenFlight: aircraft form',
    },
  ];

  it('(a) renders an <img> per declared screenshot', async () => {
    const { generateGallery } = await loadGenerator();
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-shots-'));
    const screenshotsDir = makeShotDir(dir, J1_SHOTS);

    const { html, shots } = generateGallery({
      reportPath: emptyReport(dir),
      outDir: resolve(dir, 'out'),
      screenshotsDir,
      renderNav: false,
    });

    expect(shots).toHaveLength(4);
    // One <img> per declared screenshot, src rewritten under screenshots/.
    for (const s of J1_SHOTS) {
      expect(html).toContain(`screenshots/${s.file}`);
    }
    expect((html.match(/<img /g) ?? []).length).toBe(4);
    expect(html).toContain('parity-screenshots');
  });

  it('(b) pairs legacy + alpenflight under the same journey/view (legacy left)', async () => {
    const { generateGallery } = await loadGenerator();
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-shots-'));
    const screenshotsDir = makeShotDir(dir, J1_SHOTS);

    const { html } = generateGallery({
      reportPath: emptyReport(dir),
      outDir: resolve(dir, 'out'),
      screenshotsDir,
      renderNav: false,
    });

    // Each view groups both sides into one shot-grid; within a grid legacy's
    // <img> precedes alpenflight's (legacy-left parity framing).
    for (const view of ['list', 'form']) {
      const legacyIdx = html.indexOf(`screenshots/legacy-aircraft-${view}.png`);
      const alpfIdx = html.indexOf(`screenshots/alpenflight-aircraft-${view}.png`);
      expect(legacyIdx, `legacy ${view} present`).toBeGreaterThan(-1);
      expect(alpfIdx, `alpenflight ${view} present`).toBeGreaterThan(-1);
      expect(legacyIdx, `legacy ${view} renders left of alpenflight`).toBeLessThan(alpfIdx);
    }
    // The view label renders for each pairing key.
    expect(html).toContain('>list</div>');
    expect(html).toContain('>form</div>');
  });

  it('(c) a declared PNG missing on disk fails the AC5 link-check', async () => {
    const { generateGallery } = await loadGenerator();
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-shots-'));
    // Declare all 4 but only write 3 — the legacy form PNG is absent.
    const screenshotsDir = makeShotDir(dir, J1_SHOTS, {
      writeFiles: [
        'legacy-aircraft-list.png',
        'alpenflight-aircraft-list.png',
        'alpenflight-aircraft-form.png',
      ],
    });

    expect(() =>
      generateGallery({
        reportPath: emptyReport(dir),
        outDir: resolve(dir, 'out'),
        screenshotsDir,
        renderNav: false,
      }),
    ).toThrow(/legacy-aircraft-form\.png/);
  });

  // J-2 T-43 — the gallery must render MORE THAN two views per journey as
  // multiple paired rows (J-2's parity story is list + form/wizard + motor, not
  // just J-1's list + form). Locks: (a) every declared view renders its own
  // shot-pair row with its view label; (b) within each view legacy stays left;
  // (c) a view declared with only ONE side (the AlpenFlight-only glider-step
  // example) still renders that single side. This proves `renderScreenshots`
  // generalizes beyond list+form without any hardcoded view list.
  const J2_SHOTS = [
    {
      journey: 'J-2',
      side: 'legacy',
      view: 'list',
      file: 'legacy-flight-list.png',
      caption: 'Legacy flsweb: glider+tow flight list',
    },
    {
      journey: 'J-2',
      side: 'alpenflight',
      view: 'list',
      file: 'alpenflight-flights-list.png',
      caption: 'AlpenFlight: unified /flights list (glider+tow+motor)',
    },
    {
      journey: 'J-2',
      side: 'legacy',
      view: 'form',
      file: 'legacy-flight-form.png',
      caption: 'Legacy flsweb: two-column glider+tow form',
    },
    {
      journey: 'J-2',
      side: 'alpenflight',
      view: 'form',
      file: 'alpenflight-flights-wizard-tow.png',
      caption: 'AlpenFlight: 3-step wizard at the Tow step',
    },
    {
      journey: 'J-2',
      side: 'legacy',
      view: 'motor',
      file: 'legacy-airmovements-list.png',
      caption: 'Legacy flsweb: separate /airmovements motor screen',
    },
    {
      journey: 'J-2',
      side: 'alpenflight',
      view: 'motor',
      file: 'alpenflight-motor-form.png',
      caption: 'AlpenFlight: unified motor create (tow suppressed)',
    },
    {
      journey: 'J-2',
      side: 'alpenflight',
      view: 'wizard (glider step)',
      file: 'alpenflight-flights-wizard-glider.png',
      caption: 'AlpenFlight: the wizard Glider step (single-side example)',
    },
  ];

  it('(d) renders >2 views per journey as multiple paired rows (J-2 parity)', async () => {
    const { generateGallery } = await loadGenerator();
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-j2-'));
    const screenshotsDir = makeShotDir(dir, J2_SHOTS);

    const { html, shots } = generateGallery({
      reportPath: emptyReport(dir),
      outDir: resolve(dir, 'out'),
      screenshotsDir,
      renderNav: false,
    });

    expect(shots).toHaveLength(7);
    // One <img> per declared screenshot (7), not capped at the J-1 list+form 4.
    expect((html.match(/<img /g) ?? []).length).toBe(7);
    // Each declared view renders its own labelled shot-pair row.
    for (const view of ['list', 'form', 'motor', 'wizard (glider step)']) {
      expect(html, `view "${view}" renders a labelled row`).toContain(
        `class="shot-view-label">${view}</div>`,
      );
    }
    // Within each paired view, legacy renders left of alpenflight.
    for (const [legacyFile, alpfFile] of [
      ['legacy-flight-list.png', 'alpenflight-flights-list.png'],
      ['legacy-flight-form.png', 'alpenflight-flights-wizard-tow.png'],
      ['legacy-airmovements-list.png', 'alpenflight-motor-form.png'],
    ]) {
      const l = html.indexOf(`screenshots/${legacyFile}`);
      const a = html.indexOf(`screenshots/${alpfFile}`);
      expect(l, `${legacyFile} present`).toBeGreaterThan(-1);
      expect(a, `${alpfFile} present`).toBeGreaterThan(-1);
      expect(l, `${legacyFile} left of ${alpfFile}`).toBeLessThan(a);
    }
    // The AlpenFlight-only view still renders its single side.
    expect(html).toContain('screenshots/alpenflight-flights-wizard-glider.png');
  });

  it('(c2) a declared screenshot with no caption fails the AC5 link-check', async () => {
    const { generateGallery } = await loadGenerator();
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-shots-'));
    const screenshotsDir = makeShotDir(dir, [
      {
        journey: 'J-1',
        side: 'legacy',
        view: 'list',
        file: 'legacy-aircraft-list.png',
        caption: '',
      },
    ]);

    expect(() =>
      generateGallery({
        reportPath: emptyReport(dir),
        outDir: resolve(dir, 'out'),
        screenshotsDir,
        renderNav: false,
      }),
    ).toThrow(/no caption/);
  });
});

/**
 * T-44 — the journey accordion. Each journey renders as one native
 * `<details>`/`<summary>` (no JS, keyboard-reachable), the NEWEST journey with
 * content is `<details open>` and older ones collapse, and the videos +
 * screenshots still render INSIDE the `<details>`. The accordion is a
 * presentation wrapper only — the parsing + AC4/AC5 link-checks are unchanged
 * (the missing-PNG / missing-caption guards above remain green).
 *
 * Order: a roadmap with J-0 (1 video) … J-2 (screenshots, last) so the newest
 * journey with content is J-2 — that one must be open, J-0 collapsed.
 */
const ACCORDION_ORDER = `# Journey roadmap

| J | Title | Epic |
|---|---|---|
| J-0 | Locations CRUD | E-06 |
| J-0c | Fan-out parity | E-02 |
| J-1 | Aircraft register | E-06 |
| J-2 | Flight list | E-07 |
`;

/** A report with exactly one green proof for journey J-0 (1 video). */
function singleProofReport(dir: string): { reportPath: string; videoDir: string } {
  const videoDir = mkdtempSync(resolve(dir, 'vids-'));
  const videoFile = resolve(videoDir, 'j0-tenant.webm');
  writeFileSync(videoFile, 'WEBM');
  const report = {
    suites: [
      {
        file: 'tests/locations/j0.spec.ts',
        specs: [
          {
            title: 'J-0 tenant isolation',
            file: 'tests/locations/j0.spec.ts',
            annotations: [
              { type: 'proof-journey', description: 'J-0' },
              { type: 'proof-caption', description: 'J-0 tenant isolation holds' },
              { type: 'proof-ac-tag', description: 'happy' },
            ],
            tests: [
              {
                annotations: [],
                results: [
                  {
                    status: 'passed',
                    attachments: [{ name: 'proof-video', path: videoFile }],
                  },
                ],
              },
            ],
          },
        ],
      },
    ],
  };
  const reportPath = resolve(dir, 'report.json');
  writeFileSync(reportPath, JSON.stringify(report), 'utf8');
  return { reportPath, videoDir };
}

describe('generateGallery — journey accordion (T-44)', () => {
  const J2_SHOTS = [
    {
      journey: 'J-2',
      side: 'legacy',
      view: 'list',
      file: 'legacy-flight-list.png',
      caption: 'Legacy flsweb: flight list',
    },
    {
      journey: 'J-2',
      side: 'alpenflight',
      view: 'list',
      file: 'alpenflight-flights-list.png',
      caption: 'AlpenFlight: unified /flights list',
    },
  ];

  /** Stand up a report (J-0 video) + J-2 screenshots against ACCORDION_ORDER. */
  function buildGallery(
    generateGallery: Awaited<ReturnType<typeof loadGenerator>>['generateGallery'],
  ) {
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-accordion-'));
    const orderPath = resolve(dir, '_ORDER.md');
    writeFileSync(orderPath, ACCORDION_ORDER, 'utf8');
    const { reportPath } = singleProofReport(dir);
    const screenshotsDir = makeShotDir(dir, J2_SHOTS);
    return generateGallery({
      reportPath,
      outDir: resolve(dir, 'out'),
      orderPath,
      screenshotsDir,
      renderNav: false,
    });
  }

  it('renders one <details> per journey, each with a <summary> carrying the journey id', async () => {
    const { generateGallery } = await loadGenerator();
    const { html, roadmap } = buildGallery(generateGallery);

    // One <details class="journey…"> per roadmap journey (4: J-0, J-0c, J-1, J-2).
    expect((html.match(/<details class="journey/g) ?? []).length).toBe(roadmap.length);
    expect((html.match(/<summary>/g) ?? []).length).toBe(roadmap.length);
    // Each journey id surfaces in its summary.
    for (const jid of ['J-0', 'J-0c', 'J-1', 'J-2']) {
      expect(html, `${jid} id in a summary`).toContain(`class="summary-jid">${jid}</span>`);
    }
    // No JS toggle — the accordion is the native element only.
    expect(html).not.toContain('<script');
  });

  it('opens the NEWEST journey with content and collapses an older one', async () => {
    const { generateGallery } = await loadGenerator();
    const { html } = buildGallery(generateGallery);

    // J-2 is the last roadmap journey with content → <details open>.
    const j2 = html.indexOf('<span class="summary-jid">J-2</span>');
    const openBeforeJ2 = html.lastIndexOf('<details class="journey" open>', j2);
    const anyDetailsBeforeJ2 = html.lastIndexOf('<details class="journey', j2);
    expect(openBeforeJ2, 'J-2 sits inside an <details open>').toBe(anyDetailsBeforeJ2);

    // J-0 has content too but is older → collapsed (no `open` on its <details>).
    const j0 = html.indexOf('<span class="summary-jid">J-0</span>');
    const detailsBeforeJ0 = html.slice(html.lastIndexOf('<details', j0), j0);
    expect(detailsBeforeJ0, 'J-0 collapsed').not.toContain(' open>');

    // Exactly one journey is open in the whole gallery.
    expect((html.match(/<details class="journey" open>/g) ?? []).length).toBe(1);
  });

  it('keeps videos and screenshots rendering INSIDE the <details>', async () => {
    const { generateGallery } = await loadGenerator();
    const { html } = buildGallery(generateGallery);

    // The J-0 video figure sits between J-0's <summary> and its </details>.
    const j0Summary = html.indexOf('<span class="summary-jid">J-0</span>');
    const j0Close = html.indexOf('</details>', j0Summary);
    const j0Block = html.slice(j0Summary, j0Close);
    expect(j0Block).toContain('<video controls');
    expect(j0Block).toContain('videos/j0-tenant.webm');

    // The J-2 screenshots sit inside J-2's <details>.
    const j2Summary = html.indexOf('<span class="summary-jid">J-2</span>');
    const j2Close = html.indexOf('</details>', j2Summary);
    const j2Block = html.slice(j2Summary, j2Close);
    expect(j2Block).toContain('parity-screenshots');
    expect(j2Block).toContain('screenshots/legacy-flight-list.png');
    expect(j2Block).toContain('screenshots/alpenflight-flights-list.png');
    // Summary count reflects the content.
    expect(j2Block).toContain('2 screenshots');
  });
});

/**
 * T-13a — per-journey gallery pages + the Maintainability panel.
 *
 * The operator re-arch: instead of (or in addition to) one all-journeys page,
 * the generator emits ONE self-contained page PER journey-with-content at
 * `<outDir>/J-<n>/index.html`, each rendering that journey's videos + paired
 * screenshots (shared media referenced via `../`) + a Maintainability panel read
 * from `<outDir>/maintainability/`'s 4 T-12 artifacts. The panel is fail-soft —
 * any of the 4 files may be ABSENT (the T-12 producer is `continue-on-error`).
 */

/** Read a written file from disk (the per-journey page emit is a side effect). */
function readOut(outDir: string, ...segs: string[]): string {
  return readFileSync(resolve(outDir, ...segs), 'utf8');
}

/** Write the 4 maintainability artifacts into `<outDir>/maintainability/`. */
function writeMaint(
  outDir: string,
  files: Partial<
    Record<'fallow-audit.json' | 'fallow-health.json' | 'pmd-main.xml' | 'cpd-check.xml', string>
  >,
): void {
  const dir = resolve(outDir, 'maintainability');
  mkdirSync(dir, { recursive: true });
  for (const [name, body] of Object.entries(files)) writeFileSync(resolve(dir, name), body!);
}

const SAMPLE_AUDIT = JSON.stringify({
  verdict: 'fail',
  attribution: {
    dead_code_introduced: 0,
    complexity_introduced: 5,
    duplication_introduced: 16,
  },
});
const SAMPLE_HEALTH = JSON.stringify({
  health_score: { score: 71.1, grade: 'B' },
  vital_signs: { maintainability_avg: 92, duplication_pct: 6.1, dead_file_pct: 1.1 },
});
const SAMPLE_PMD =
  '<pmd><file name="A.java">' +
  '<violation rule="CyclomaticComplexity">x</violation>' +
  '<violation rule="ExcessiveParameterList">y</violation>' +
  '<violation rule="UnusedPrivateField">z</violation>' +
  '</file></pmd>';
const SAMPLE_CPD =
  '<pmd-cpd>' +
  '<file path="A.java" totalNumberOfTokens="1000"/>' +
  '<file path="B.java" totalNumberOfTokens="1000"/>' +
  '<duplication lines="20" tokens="40"><file/></duplication>' +
  '<duplication lines="10" tokens="20"><file/></duplication>' +
  '</pmd-cpd>';

describe('parse helpers (T-13a) — fail-soft artifact parsing', () => {
  it('parsePmd counts total + complexity + dead-code by rule', async () => {
    const { parsePmd } = await loadGenerator();
    expect(parsePmd(SAMPLE_PMD)).toEqual({ total: 3, complexity: 2, deadCode: 1 });
  });

  it('parseCpd computes a duplication % (dup-tokens / total-tokens) + group count', async () => {
    const { parseCpd } = await loadGenerator();
    const cpd = parseCpd(SAMPLE_CPD);
    expect(cpd?.groups).toBe(2);
    // (40 + 20) / (1000 + 1000) = 3.0%
    expect(cpd?.dupPct).toBeCloseTo(3.0, 5);
  });

  it('every parser returns null on absent/empty input (never throws)', async () => {
    const { parsePmd, parseCpd, parseFallowAudit } = await loadGenerator();
    expect(parsePmd(null)).toBeNull();
    expect(parseCpd(null)).toBeNull();
    expect(parseFallowAudit(null)).toBeNull();
    expect(parseFallowAudit({})).toEqual({
      verdict: 'unknown',
      deadIntroduced: 0,
      complexityIntroduced: 0,
      duplicationIntroduced: 0,
    });
  });

  it('maintainabilityRollup is red on a fail verdict, green on no-delta, neutral without delta', async () => {
    const { maintainabilityRollup } = await loadGenerator();
    const fail = {
      verdict: 'fail',
      deadIntroduced: 0,
      complexityIntroduced: 5,
      duplicationIntroduced: 16,
    };
    const clean = {
      verdict: 'pass',
      deadIntroduced: 0,
      complexityIntroduced: 0,
      duplicationIntroduced: 0,
    };
    expect(maintainabilityRollup({ audit: fail, showDelta: true }).level).toBe('red');
    expect(maintainabilityRollup({ audit: clean, showDelta: true }).level).toBe('green');
    // showDelta=false (not the journey-under-work) → snapshot-only, regardless of audit.
    expect(maintainabilityRollup({ audit: fail, showDelta: false }).level).toBe('neutral');
    // No audit at all → neutral.
    expect(maintainabilityRollup({ audit: null, showDelta: true }).level).toBe('neutral');
  });
});

describe('generateGallery — per-journey pages (T-13a)', () => {
  /** A report with one green proof for J-0 + J-2 screenshots, against a roadmap. */
  function buildPerJourney(
    generateGallery: Awaited<ReturnType<typeof loadGenerator>>['generateGallery'],
    opts: { writeMaint?: boolean; journeyUnderWork?: string; branch?: string } = {},
  ) {
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-perj-'));
    const orderPath = resolve(dir, '_ORDER.md');
    writeFileSync(orderPath, ACCORDION_ORDER, 'utf8');
    const { reportPath } = singleProofReport(dir);
    const screenshotsDir = makeShotDir(dir, [
      {
        journey: 'J-2',
        side: 'legacy',
        view: 'list',
        file: 'legacy-flight-list.png',
        caption: 'Legacy: flight list',
      },
      {
        journey: 'J-2',
        side: 'alpenflight',
        view: 'list',
        file: 'alpenflight-flights-list.png',
        caption: 'AlpenFlight: flights list',
      },
    ]);
    const outDir = resolve(dir, 'out');
    if (opts.writeMaint) {
      mkdirSync(outDir, { recursive: true });
      writeMaint(outDir, {
        'fallow-audit.json': SAMPLE_AUDIT,
        'fallow-health.json': SAMPLE_HEALTH,
        'pmd-main.xml': SAMPLE_PMD,
        'cpd-check.xml': SAMPLE_CPD,
      });
    }
    const result = generateGallery({
      reportPath,
      outDir,
      orderPath,
      screenshotsDir,
      renderNav: false,
      journeyUnderWork: opts.journeyUnderWork,
      ...(opts.branch ? { branch: opts.branch } : {}),
    });
    return { outDir, result };
  }

  it('emits one page per journey-WITH-content (J-0 video, J-2 screenshots); pending journeys get NO page', async () => {
    const { generateGallery } = await loadGenerator();
    const { outDir, result } = buildPerJourney(generateGallery);

    expect(result.journeyPages.map((p) => p.journey).sort()).toEqual(['J-0', 'J-2']);
    // The all-journeys index.html is STILL written (additive, not replaced).
    expect(existsSync(resolve(outDir, 'index.html'))).toBe(true);
    // Each emitted page exists at <outDir>/J-<n>/index.html.
    expect(existsSync(resolve(outDir, 'J-0', 'index.html'))).toBe(true);
    expect(existsSync(resolve(outDir, 'J-2', 'index.html'))).toBe(true);
    // J-0c + J-1 are pending (no content) → no per-journey page emitted.
    expect(existsSync(resolve(outDir, 'J-0c', 'index.html'))).toBe(false);
    expect(existsSync(resolve(outDir, 'J-1', 'index.html'))).toBe(false);
  });

  // T-01b — operator standing rule: the journey-under-work's proof page must
  // exist from the START (the first task scaffolds it) so the operator's
  // glanceable proof window exists before any capture lands, then ACCUMULATES
  // captures as later tasks' specs go green. So a journey-under-work with ZERO
  // content still gets a per-journey "pending — awaiting captures" placeholder
  // page (and the previews index links it). Other pending journeys still get no
  // page (the index renders them as plain pending rows — no dead link).
  it('emits a PENDING placeholder page for a content-less journey-under-work (T-01b scaffold)', async () => {
    const { generateGallery } = await loadGenerator();
    // J-1 is pending in ACCORDION_ORDER (no video, no screenshot) — name it the
    // journey-under-work so the scaffold page is emitted even with no captures.
    const { outDir, result } = buildPerJourney(generateGallery, { journeyUnderWork: 'J-1' });

    // The journey-under-work gets a page despite zero content → it's listed.
    expect(result.journeyPages.map((p) => p.journey).sort()).toEqual(['J-0', 'J-1', 'J-2']);
    expect(existsSync(resolve(outDir, 'J-1', 'index.html'))).toBe(true);

    const j1 = readOut(outDir, 'J-1', 'index.html');
    // It renders the pending state (no video), not a broken/empty page.
    expect(j1).toContain('>J-1 — proof</h1>');
    expect(j1).toContain('No green proof yet');
    expect(j1).toContain('<span class="status pending">pending</span>');
    // The back-link to the persistent previews index is present (no dead link).
    expect(j1).toContain('alpenflight/previews/');

    // A DIFFERENT pending journey (J-0c) is NOT the journey-under-work → still
    // no page (it stays a plain pending row in the index — no dead link).
    expect(existsSync(resolve(outDir, 'J-0c', 'index.html'))).toBe(false);
  });

  it('per-journey page renders the journey content with shared media referenced via ../', async () => {
    const { generateGallery } = await loadGenerator();
    const { outDir } = buildPerJourney(generateGallery);

    const j0 = readOut(outDir, 'J-0', 'index.html');
    expect(j0).toContain('<video controls');
    // Shared media lives at the out-root; the per-journey page is one dir down.
    expect(j0).toContain('../videos/j0-tenant.webm');
    expect(j0).toContain('>J-0 — proof</h1>');

    const j2 = readOut(outDir, 'J-2', 'index.html');
    expect(j2).toContain('parity-screenshots');
    expect(j2).toContain('../screenshots/legacy-flight-list.png');
    expect(j2).toContain('../screenshots/alpenflight-flights-list.png');
  });

  it('renders the Maintainability panel from a sample dir — red roll-up + delta on the journey-under-work', async () => {
    const { generateGallery } = await loadGenerator();
    const { outDir } = buildPerJourney(generateGallery, {
      writeMaint: true,
      journeyUnderWork: 'J-0',
    });

    const j0 = readOut(outDir, 'J-0', 'index.html');
    expect(j0).toContain('class="maintainability"');
    // Fail verdict + 21 introduced → red pill.
    expect(j0).toContain('<span class="status failure">');
    expect(j0).toContain('FE delta (this journey vs main)');
    // Snapshot numbers from fallow-health.
    expect(j0).toContain('score <strong>71.1</strong> (B)');
    // BE PMD: 3 violations (2 complexity, 1 dead-code).
    expect(j0).toContain('<strong>3</strong> violations');
    // BE CPD: 3.0% duplicated.
    expect(j0).toContain('3.0%');

    // J-2 is NOT the journey-under-work → snapshot only, no false delta.
    const j2 = readOut(outDir, 'J-2', 'index.html');
    expect(j2).toContain('historical per-journey delta not reconstructable');
    expect(j2).toContain('<span class="status neutral">'); // snapshot-only pill
  });

  it('Maintainability panel is fail-soft — absent artifacts render "no data", never throws', async () => {
    const { generateGallery } = await loadGenerator();
    // No writeMaint → the maintainability dir does not exist.
    const { outDir } = buildPerJourney(generateGallery, { journeyUnderWork: 'J-0' });
    const j0 = readOut(outDir, 'J-0', 'index.html');
    expect(j0).toContain('class="maintainability"');
    expect(j0).toContain('no data');
  });

  it('tolerates a PARTIAL maintainability dir — only PMD present, the other 3 absent', async () => {
    const { generateGallery } = await loadGenerator();
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-partial-'));
    const orderPath = resolve(dir, '_ORDER.md');
    writeFileSync(orderPath, ACCORDION_ORDER, 'utf8');
    const { reportPath } = singleProofReport(dir);
    const outDir = resolve(dir, 'out');
    mkdirSync(outDir, { recursive: true });
    writeMaint(outDir, { 'pmd-main.xml': SAMPLE_PMD }); // only one of the 4

    expect(() =>
      generateGallery({ reportPath, outDir, orderPath, renderNav: false, journeyUnderWork: 'J-0' }),
    ).not.toThrow();

    const j0 = readOut(outDir, 'J-0', 'index.html');
    expect(j0).toContain('<strong>3</strong> violations'); // PMD parsed
    expect(j0).toContain('no fallow health data'); // health absent → graceful
  });

  // T-14 — the per-push CI gallery step must pass the journey id EXPLICITLY via
  // `--journey-under-work` (sourced from the `changes` job's derived journey),
  // because on a PR push GITHUB_REF_NAME is the merge ref (`<pr>/merge`), NOT
  // `integration/J-NNN` — so the generator's branch-name fallback can't derive
  // the journey, and the panel would degrade to "snapshot only" with no delta.
  // These lock the CLI-arg → delta contract the CI wiring relies on.
  it('--journey-under-work parses to journeyUnderWork (the CI flag the delta needs)', async () => {
    const { parseArgs } = await loadGenerator();
    const args = parseArgs(['--report', 'r.json', '--out', 'o', '--journey-under-work', 'J-6']);
    expect(args.journeyUnderWork).toBe('J-6');
  });

  it('an explicit journeyUnderWork drives the FE delta even when the branch cannot (merge-ref)', async () => {
    const { generateGallery } = await loadGenerator();
    // Simulate the PR-push reality: a merge-ref branch from which journeyFromFile
    // derives nothing, BUT the CI passes the derived journey id explicitly.
    const { outDir } = buildPerJourney(generateGallery, {
      writeMaint: true,
      journeyUnderWork: 'J-0',
      branch: '123/merge',
    });
    const j0 = readOut(outDir, 'J-0', 'index.html');
    // Explicit journey-under-work → the FE delta renders (red roll-up), NOT the
    // snapshot-only neutral fallback.
    expect(j0).toContain('FE delta (this journey vs main)');
    expect(j0).toContain('<span class="status failure">');
    expect(j0).not.toContain('<span class="status neutral">');
  });

  it('without an explicit journey-under-work the panel degrades to snapshot-only (no false delta)', async () => {
    const { generateGallery } = await loadGenerator();
    // No journeyUnderWork + a merge-ref branch → nothing derivable → the J-0 page
    // shows the repo snapshot, never a fabricated per-journey delta.
    const { outDir } = buildPerJourney(generateGallery, {
      writeMaint: true,
      branch: '123/merge',
    });
    const j0 = readOut(outDir, 'J-0', 'index.html');
    expect(j0).toContain('historical per-journey delta not reconstructable');
    expect(j0).toContain('<span class="status neutral">');
    expect(j0).not.toContain('FE delta (this journey vs main)');
  });

  it('--no-per-journey (perJourney:false) emits ONLY the all-journeys page', async () => {
    const { generateGallery } = await loadGenerator();
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-nopj-'));
    const orderPath = resolve(dir, '_ORDER.md');
    writeFileSync(orderPath, ACCORDION_ORDER, 'utf8');
    const { reportPath } = singleProofReport(dir);
    const outDir = resolve(dir, 'out');

    const result = generateGallery({
      reportPath,
      outDir,
      orderPath,
      renderNav: false,
      perJourney: false,
    } as Parameters<typeof generateGallery>[0]);

    expect(result.journeyPages).toEqual([]);
    expect(existsSync(resolve(outDir, 'index.html'))).toBe(true);
    expect(existsSync(resolve(outDir, 'J-0', 'index.html'))).toBe(false);
  });
});

/**
 * T-32 — the per-journey page is deployed into TWO layouts on gh-pages:
 *   - canonical:      <site>/alpenflight/proof/J-<n>/
 *   - branch-preview: <site>/alpenflight/proof-preview/<branch>/J-<n>/  (one deeper)
 * The operator found 2/3 cross-section links dead on the live branch-preview page:
 *   (1) the back-to-previews-index link was relative (`../../previews/`) → 404 at
 *       the deeper depth; it must be site-root-absolute so it works at ANY depth.
 *   (2) `../maintainability/` targeted a directory with no index.html → gh-pages
 *       404s a bare dir; the dir must carry an index.html so the link serves.
 */
describe('generateGallery — deployed-layout cross-section links (T-32)', () => {
  function buildAt(
    generateGallery: Awaited<ReturnType<typeof loadGenerator>>['generateGallery'],
    relLayout: string[],
    opts: { siteBase?: string } = {},
  ): { outDir: string } {
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-t32-'));
    const orderPath = resolve(dir, '_ORDER.md');
    writeFileSync(orderPath, ACCORDION_ORDER, 'utf8');
    const { reportPath } = singleProofReport(dir);
    // Mirror the real deploy depth: place outDir at the layout's J-parent.
    const outDir = resolve(dir, ...relLayout);
    mkdirSync(outDir, { recursive: true });
    writeMaint(outDir, {
      'fallow-audit.json': SAMPLE_AUDIT,
      'fallow-health.json': SAMPLE_HEALTH,
      'pmd-main.xml': SAMPLE_PMD,
      'cpd-check.xml': SAMPLE_CPD,
    });
    generateGallery({
      reportPath,
      outDir,
      orderPath,
      renderNav: false,
      journeyUnderWork: 'J-0',
      ...(opts.siteBase ? { siteBase: opts.siteBase } : {}),
    });
    return { outDir };
  }

  // Both deploy layouts, relative to the temp root. The branch-preview is one
  // dir deeper — the case that broke the relative back-link.
  const CANONICAL = ['alpenflight', 'proof'];
  const BRANCH_PREVIEW = ['alpenflight', 'proof-preview', 'integration-J-5'];

  it('back-index link is site-root-absolute (depth-independent) in BOTH layouts', async () => {
    const { generateGallery, DEFAULT_SITE_BASE } = await loadGenerator();
    expect(DEFAULT_SITE_BASE).toBe('/fls/');

    for (const layout of [CANONICAL, BRANCH_PREVIEW]) {
      const { outDir } = buildAt(generateGallery, layout);
      const j0 = readOut(outDir, 'J-0', 'index.html');
      // Absolute, gh-pages base — works from canonical AND branch-preview depth.
      expect(j0).toContain('href="/fls/alpenflight/previews/"');
      // The old depth-fragile relative link must be gone.
      expect(j0).not.toContain('href="../../previews/"');
      // The all-journeys + asset links stay relative-within-the-same-deploy.
      expect(j0).toContain('href="../"');
    }
  });

  it('honors a custom --site-base (configurable, not hardcoded magic)', async () => {
    const { generateGallery } = await loadGenerator();
    const { outDir } = buildAt(generateGallery, BRANCH_PREVIEW, { siteBase: '/elsewhere/' });
    const j0 = readOut(outDir, 'J-0', 'index.html');
    expect(j0).toContain('href="/elsewhere/alpenflight/previews/"');
  });

  it('emits maintainability/index.html so the reports dir URL serves 200 (BOTH layouts)', async () => {
    const { generateGallery } = await loadGenerator();
    for (const layout of [CANONICAL, BRANCH_PREVIEW]) {
      const { outDir } = buildAt(generateGallery, layout);
      const idx = resolve(outDir, 'maintainability', 'index.html');
      expect(existsSync(idx)).toBe(true);
      const html = readFileSync(idx, 'utf8');
      // Links the artifacts that exist (relative to the dir itself).
      expect(html).toContain('href="./fallow-audit.json"');
      expect(html).toContain('href="./pmd-main.xml"');
      // The panel link still targets the dir (now backed by index.html).
      const j0 = readOut(outDir, 'J-0', 'index.html');
      expect(j0).toContain('href="../maintainability/"');
    }
  });

  it('maintainability index lists only the artifacts present (partial dir)', async () => {
    const { generateGallery } = await loadGenerator();
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-t32-partial-'));
    const orderPath = resolve(dir, '_ORDER.md');
    writeFileSync(orderPath, ACCORDION_ORDER, 'utf8');
    const { reportPath } = singleProofReport(dir);
    const outDir = resolve(dir, 'out');
    mkdirSync(outDir, { recursive: true });
    writeMaint(outDir, { 'pmd-main.xml': SAMPLE_PMD }); // only one of the 4
    generateGallery({ reportPath, outDir, orderPath, renderNav: false, journeyUnderWork: 'J-0' });

    const html = readFileSync(resolve(outDir, 'maintainability', 'index.html'), 'utf8');
    expect(html).toContain('href="./pmd-main.xml"');
    expect(html).not.toContain('href="./fallow-audit.json"');
  });

  it('emits NO maintainability index when no artifacts exist (panel shows "no data", no dead link)', async () => {
    const { generateGallery } = await loadGenerator();
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-t32-none-'));
    const orderPath = resolve(dir, '_ORDER.md');
    writeFileSync(orderPath, ACCORDION_ORDER, 'utf8');
    const { reportPath } = singleProofReport(dir);
    const outDir = resolve(dir, 'out');
    generateGallery({ reportPath, outDir, orderPath, renderNav: false, journeyUnderWork: 'J-0' });

    expect(existsSync(resolve(outDir, 'maintainability', 'index.html'))).toBe(false);
    const j0 = readOut(outDir, 'J-0', 'index.html');
    expect(j0).toContain('no data');
    // Fail-soft: no reports link at all when there are no artifacts.
    expect(j0).not.toContain('href="../maintainability/"');
  });
});

/**
 * T-35 guard — the ALL-JOURNEYS page's cross-section links must resolve in the
 * DEPLOYED layouts (canonical `alpenflight/proof/` AND branch-preview
 * `alpenflight/proof-preview/<branch>/legacy-parity/`, one dir deeper). The live
 * branch-preview deploy had 3 dead links, ALL from the all-journeys page:
 *   - the back-link `../` → the bare `proof-preview/` parent (no index.html → 404);
 *   - the per-run nav `j-0c-fanout/` → only deployed under canonical, 404 under the
 *     preview as a relative href;
 *   - (the stale `../../previews/` per-journey back-link — already T-32-fixed to
 *     site-absolute; covered by the per-journey-page assertions).
 * The fix makes BOTH all-journeys cross-section links SITE-ROOT-ABSOLUTE (depth-
 * independent), exactly like the per-journey pages' back-link (T-32).
 */
describe('generateGallery — all-journeys cross-section links resolve in deployed layouts (T-35)', () => {
  it('the back-link is site-root-absolute to the previews index, NOT a relative bare dir', async () => {
    const { generateGallery, DEFAULT_SITE_BASE } = await loadGenerator();
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-t35-back-'));
    const orderPath = resolve(dir, '_ORDER.md');
    writeFileSync(orderPath, ACCORDION_ORDER, 'utf8');
    const { reportPath } = singleProofReport(dir);
    const outDir = resolve(dir, 'out');
    generateGallery({ reportPath, outDir, orderPath, renderNav: true });

    const html = readFileSync(resolve(outDir, 'index.html'), 'utf8');
    // The depth-independent back-link (works in both canonical & branch-preview).
    expect(html).toContain(`href="${DEFAULT_SITE_BASE}alpenflight/previews/"`);
    // The OLD bug: a relative `../` that 404s the bare proof-preview/ parent.
    expect(html).not.toContain('href="../">&larr; alpenflight dashboard');
  });

  it('per-run nav galleries link site-root-absolute to canonical proof (works under a branch preview)', async () => {
    const { generateGallery, DEFAULT_SITE_BASE } = await loadGenerator();
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-t35-nav-'));
    const orderPath = resolve(dir, '_ORDER.md');
    writeFileSync(orderPath, ACCORDION_ORDER, 'utf8');
    const { reportPath } = singleProofReport(dir);
    const outDir = resolve(dir, 'out');
    // renderNav: true → the committed per-run-galleries.json (j-0c-fanout/) renders.
    generateGallery({ reportPath, outDir, orderPath, renderNav: true });

    const html = readFileSync(resolve(outDir, 'index.html'), 'utf8');
    // The per-run gallery is deployed ONLY under canonical proof; a RELATIVE href
    // 404s under the branch preview. Assert it's pinned site-root-absolute.
    expect(html).toContain(`href="${DEFAULT_SITE_BASE}alpenflight/proof/j-0c-fanout/"`);
    // No bare relative `j-0c-fanout/` anchor survives (the T-35 bug).
    expect(html).not.toMatch(/<a[^>]+href="j-0c-fanout\/"/);
  });

  it('navGalleryHref: relative → site-absolute canonical; absolute/external pass through', async () => {
    const { navGalleryHref, DEFAULT_SITE_BASE } = await loadGenerator();
    // Relative (the manifest form, relative to /alpenflight/proof/) → site-absolute.
    expect(navGalleryHref('j-0c-fanout/')).toBe(
      `${DEFAULT_SITE_BASE}alpenflight/proof/j-0c-fanout/`,
    );
    expect(navGalleryHref('j-0c-fanout/', '/base/')).toBe('/base/alpenflight/proof/j-0c-fanout/');
    // Already site-absolute → unchanged.
    expect(navGalleryHref('/fls/alpenflight/proof/x/')).toBe('/fls/alpenflight/proof/x/');
    // External → unchanged.
    expect(navGalleryHref('https://example.test/g/')).toBe('https://example.test/g/');
  });
});

/**
 * T-17b — the PER-PUSH pairing contract. The in-flight dev gallery pairs the
 * COMMITTED legacy-reference fixtures (`e2e/legacy-reference/<feature>/<view>.png`
 * — made once + in git per T-17a, legacy frozen) against the FRESH clean-seed
 * AlpenFlight PNGs the proof spec writes per push, declared in a per-push
 * `screenshots.json`. This locks that, given those committed refs + fresh-AF
 * PNGs + the sidecar, the generated per-journey J-6 page renders BOTH the legacy
 * AND the AlpenFlight `<img>` for EACH view (list/form/setup) — and degrades
 * gracefully (renders the AF side alone) when a legacy ref is absent.
 *
 * The fixtures are the REAL committed legacy refs (not synthetic 1×1s) so this
 * spec also guards their continued presence: if a future change reaps them, the
 * `existsSync` precondition fails loudly here.
 */
describe('generateGallery — per-push committed-legacy ↔ fresh-AF pairing (T-17b)', () => {
  // The REAL committed legacy-reference fixtures (T-17a). Resolved from the spec
  // dir up to e2e/legacy-reference/planning/.
  const LEGACY_REF_DIR = resolve(__dirname, '..', 'legacy-reference', 'planning');
  const VIEWS = ['list', 'form', 'setup'] as const;

  // The per-push staging step (ci.yml T-17b) maps each view to:
  //   legacy file name  ← copied from the committed ref under a stable name
  //   AF file name      ← the fresh clean-seed capture the spec wrote
  // setup's AF file is `…-setup-form.png` (the wizard form), the one view whose
  // AF filename differs from its bare view key — mirror that mapping here.
  const PLANNING = [
    { view: 'list', legacy: 'legacy-planning-list.png', af: 'alpenflight-planning-list.png' },
    { view: 'form', legacy: 'legacy-planning-form.png', af: 'alpenflight-planning-form.png' },
    {
      view: 'setup',
      legacy: 'legacy-planning-setup.png',
      af: 'alpenflight-planning-setup-form.png',
    },
  ] as const;

  /**
   * Stage a per-push `--screenshots` dir exactly as the ci.yml T-17b step does:
   * copy each committed legacy ref + write a fresh-AF placeholder PNG, and
   * declare every staged side in screenshots.json. `skipLegacyViews` omits the
   * legacy ref for the listed views (to model a missing committed ref).
   */
  function stagePerPush(base: string, opts: { skipLegacyViews?: string[] } = {}): string {
    const dir = mkdtempSync(resolve(base, 'perpush-'));
    const decls: {
      journey: string;
      side: string;
      view: string;
      file: string;
      caption: string;
    }[] = [];
    for (const { view, legacy, af } of PLANNING) {
      if (!opts.skipLegacyViews?.includes(view)) {
        // Copy the REAL committed legacy ref under its stable staged name.
        const ref = resolve(LEGACY_REF_DIR, `${view}.png`);
        expect(existsSync(ref), `committed legacy ref ${view}.png present (T-17a)`).toBe(true);
        writeFileSync(resolve(dir, legacy), readFileSync(ref));
        decls.push({
          journey: 'J-6',
          side: 'legacy',
          view,
          file: legacy,
          caption: `Legacy flsweb: planning ${view}`,
        });
      }
      // The fresh-AF side — a placeholder PNG (the real spec writes the captured
      // screen; for the generator's copy/link-check a valid PNG is enough).
      writeFileSync(resolve(dir, af), PNG_1X1);
      decls.push({
        journey: 'J-6',
        side: 'alpenflight',
        view,
        file: af,
        caption: `AlpenFlight: planning ${view}`,
      });
    }
    writeFileSync(resolve(dir, 'screenshots.json'), JSON.stringify({ screenshots: decls }), 'utf8');
    return dir;
  }

  const J6_ORDER = `# Journey roadmap

| J | Title | Epic |
|---|---|---|
| J-0 | Locations CRUD | E-06 |
| J-6 | Planning days + setup | E-08 |
`;

  function buildJ6(
    generateGallery: Awaited<ReturnType<typeof loadGenerator>>['generateGallery'],
    opts: { skipLegacyViews?: string[] } = {},
  ): { outDir: string } {
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-t17b-'));
    const orderPath = resolve(dir, '_ORDER.md');
    writeFileSync(orderPath, J6_ORDER, 'utf8');
    const reportPath = resolve(dir, 'report.json');
    writeFileSync(reportPath, JSON.stringify({ suites: [] }), 'utf8');
    const screenshotsDir = stagePerPush(dir, opts);
    const outDir = resolve(dir, 'out');
    generateGallery({
      reportPath,
      outDir,
      orderPath,
      screenshotsDir,
      renderNav: false,
      journeyUnderWork: 'J-6',
    });
    return { outDir };
  }

  it('the per-push J-6 page pairs committed-legacy + fresh-AF for EACH of list/form/setup', async () => {
    const { generateGallery } = await loadGenerator();
    const { outDir } = buildJ6(generateGallery);

    const j6 = readOut(outDir, 'J-6', 'index.html');
    expect(j6).toContain('parity-screenshots');
    for (const { view, legacy, af } of PLANNING) {
      // BOTH sides render an <img> for this view…
      const l = j6.indexOf(`../screenshots/${legacy}`);
      const a = j6.indexOf(`../screenshots/${af}`);
      expect(l, `legacy ${view} <img> present`).toBeGreaterThan(-1);
      expect(a, `AlpenFlight ${view} <img> present`).toBeGreaterThan(-1);
      // …and the legacy side renders LEFT of the AlpenFlight side (parity framing).
      expect(l, `legacy ${view} left of AF`).toBeLessThan(a);
      // …under a labelled shot-pair row keyed by the matching view.
      expect(j6, `view "${view}" labelled row`).toContain(`class="shot-view-label">${view}</div>`);
    }
    // 3 views × 2 sides = 6 paired <img> in the parity block.
    expect((j6.match(/class="shot /g) ?? []).length).toBe(6);
  });

  it('degrades gracefully when a committed legacy ref is missing — renders the AF side alone', async () => {
    const { generateGallery } = await loadGenerator();
    // Model a missing committed legacy ref for the `form` view (e.g. not yet
    // captured for a future journey) — the page must still render, with the AF
    // form side alone and BOTH sides for list + setup.
    const { outDir } = buildJ6(generateGallery, { skipLegacyViews: ['form'] });

    const j6 = readOut(outDir, 'J-6', 'index.html');
    // form: AF side present, legacy side absent (graceful single-side render).
    expect(j6).toContain('../screenshots/alpenflight-planning-form.png');
    expect(j6).not.toContain('../screenshots/legacy-planning-form.png');
    // list + setup: BOTH sides still pair.
    expect(j6).toContain('../screenshots/legacy-planning-list.png');
    expect(j6).toContain('../screenshots/alpenflight-planning-list.png');
    expect(j6).toContain('../screenshots/legacy-planning-setup.png');
    expect(j6).toContain('../screenshots/alpenflight-planning-setup-form.png');
    // 5 staged sides (2 + 1 + 2) → 5 shot figures, no broken link, no throw.
    expect((j6.match(/class="shot /g) ?? []).length).toBe(5);
  });
});
