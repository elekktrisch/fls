import { existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

import { describe, expect, it } from 'vitest';

const GENERATOR = resolve(__dirname, 'generate-gallery.mjs');

async function loadGenerator(): Promise<{
  parseRoadmapText: (text: string) => string[];
  generateGallery: (o: {
    reportPath: string;
    outDir: string;
    journeyUnderWork?: string;
    legacyVideoDir?: string;
    screenshotsDir?: string;
    branch?: string;
    siteBase?: string;
  }) => {
    html: string;
    outFile: string;
    journey: string;
    generatedAt: string;
    proofs: { journey: string }[];
    shots: { journey: string }[];
  };
  DEFAULT_SITE_BASE: string;
  parsePmd: (xml: string | null) => { total: number; complexity: number; deadCode: number } | null;
  parseCpd: (xml: string | null) => { groups: number; dupPct: number | null } | null;
  parseQodana: (json: unknown) => { total: number; newFindings: number | null } | null;
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
    branch?: string;
    journeyUnderWork?: string;
    siteBase?: string;
  };
  extractScreenshots: (dir: string) => {
    shots: { journey: string; side: string; view: string; imgPath?: string }[];
    errors: string[];
  };
  renderedShotKeys: (
    shots: { journey?: string; side?: string; view?: string; imgPath?: string }[],
    journey?: string,
  ) => Set<string>;
}> {
  return import(pathToFileURL(GENERATOR).href);
}

const PNG_1X1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=',
  'base64',
);

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

function singleProofReport(dir: string, journey: string): { reportPath: string } {
  const videoDir = mkdtempSync(resolve(dir, 'vids-'));
  const videoFile = resolve(videoDir, `${journey.toLowerCase()}-proof.webm`);
  writeFileSync(videoFile, 'WEBM');
  const report = {
    suites: [
      {
        file: `tests/x/${journey}.spec.ts`,
        specs: [
          {
            title: `${journey} happy path`,
            file: `tests/x/${journey}.spec.ts`,
            annotations: [
              { type: 'proof-journey', description: journey },
              { type: 'proof-caption', description: `${journey} happy path holds` },
              { type: 'proof-ac-tag', description: 'happy' },
            ],
            tests: [
              {
                annotations: [],
                results: [
                  { status: 'passed', attachments: [{ name: 'proof-video', path: videoFile }] },
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
  return { reportPath };
}

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

describe('parseRoadmapText — ordering contract (kept for previews-index reuse)', () => {
  it('parses a ✅-prefixed bolded row to its bare J-NN id', async () => {
    const { parseRoadmapText } = await loadGenerator();
    expect(parseRoadmapText('| ✅ **J-0** | x | y |')).toEqual(['J-0']);
  });

  it('keeps shipped journeys in roadmap-table order, drops nothing, ignores later cells', async () => {
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
    expect(parseRoadmapText('| ✅ **J-0** | Locations | masterdata/J-99/ |')).toEqual(['J-0']);
  });
});

describe('generateGallery — ONE page, in-flight journey only', () => {
  it('emits a single index.html for the journey-under-work — no per-journey subdir, no all-journeys index', async () => {
    const { generateGallery } = await loadGenerator();
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-one-'));
    const { reportPath } = singleProofReport(dir, 'J-11');

    const { outFile, journey } = generateGallery({
      reportPath,
      outDir: resolve(dir, 'out'),
      journeyUnderWork: 'J-11',
    });

    expect(journey).toBe('J-11');
    expect(existsSync(outFile)).toBe(true);
    expect(outFile).toBe(resolve(dir, 'out', 'index.html'));
    expect(existsSync(resolve(dir, 'out', 'J-11', 'index.html'))).toBe(false);
    expect(existsSync(resolve(dir, 'out', 'J-0', 'index.html'))).toBe(false);

    const html = readFileSync(outFile, 'utf8');
    expect(html).toContain('>J-11 — proof</h1>');
    expect(html).not.toContain('<details');
    expect(html).not.toContain('Per-run proof galleries');
    expect(html).not.toContain('alpenflight/previews/');
    expect(html).not.toContain('all-journeys');
  });

  it('stamps a machine-readable proof-generated-at marker, so a stale CDN copy is distinguishable from this run’s page', async () => {
    const { generateGallery } = await loadGenerator();
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-marker-'));
    const { reportPath } = singleProofReport(dir, 'J-11');
    const before = Date.now();

    const { html, generatedAt } = generateGallery({
      reportPath,
      outDir: resolve(dir, 'out'),
      journeyUnderWork: 'J-11',
    });

    expect(html).toContain(`<meta name="proof-generated-at" content="${generatedAt}">`);
    expect(Date.parse(generatedAt)).toBeGreaterThanOrEqual(before - 1_000);
  });

  it('renders ONLY the journey-under-work; another journey in the report is excluded', async () => {
    const { generateGallery } = await loadGenerator();
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-scope-'));
    const videoDir = mkdtempSync(resolve(dir, 'vids-'));
    const j0Video = resolve(videoDir, 'j0.webm');
    const j1Video = resolve(videoDir, 'j1.webm');
    writeFileSync(j0Video, 'WEBM');
    writeFileSync(j1Video, 'WEBM');
    const mkSpec = (jid: string, video: string) => ({
      title: `${jid} proof`,
      file: `tests/x/${jid}.spec.ts`,
      annotations: [
        { type: 'proof-journey', description: jid },
        { type: 'proof-caption', description: `${jid} holds` },
        { type: 'proof-ac-tag', description: 'happy' },
      ],
      tests: [
        {
          annotations: [],
          results: [{ status: 'passed', attachments: [{ name: 'proof-video', path: video }] }],
        },
      ],
    });
    const reportPath = resolve(dir, 'report.json');
    writeFileSync(
      reportPath,
      JSON.stringify({ suites: [{ specs: [mkSpec('J-0', j0Video), mkSpec('J-1', j1Video)] }] }),
      'utf8',
    );

    const { html, proofs } = generateGallery({
      reportPath,
      outDir: resolve(dir, 'out'),
      journeyUnderWork: 'J-1',
    });

    expect(proofs.map((p) => p.journey)).toEqual(['J-1']);
    expect(html).toContain('videos/j1.webm');
    expect(html).not.toContain('videos/j0.webm');
    expect(html).not.toContain('>J-0 — proof</h1>');
  });

  it('copies the journey video into videos/ and references it with a self-contained relative src', async () => {
    const { generateGallery } = await loadGenerator();
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-vid-'));
    const { reportPath } = singleProofReport(dir, 'J-0');
    const outDir = resolve(dir, 'out');

    const { html } = generateGallery({ reportPath, outDir, journeyUnderWork: 'J-0' });

    expect(html).toContain('<video controls');
    expect(html).toContain('src="videos/j-0-proof.webm"');
    expect(existsSync(resolve(outDir, 'videos', 'j-0-proof.webm'))).toBe(true);
  });

  it('renders paired legacy↔AlpenFlight screenshots (legacy left) for the journey', async () => {
    const { generateGallery } = await loadGenerator();
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-shots-'));
    const shotsDir = makeShotDir(dir, [
      { journey: 'J-1', side: 'legacy', view: 'list', file: 'legacy-list.png', caption: 'L list' },
      {
        journey: 'J-1',
        side: 'alpenflight',
        view: 'list',
        file: 'af-list.png',
        caption: 'AF list',
      },
      { journey: 'J-1', side: 'legacy', view: 'form', file: 'legacy-form.png', caption: 'L form' },
      {
        journey: 'J-1',
        side: 'alpenflight',
        view: 'form',
        file: 'af-form.png',
        caption: 'AF form',
      },
    ]);

    const { html, shots } = generateGallery({
      reportPath: emptyReport(dir),
      outDir: resolve(dir, 'out'),
      journeyUnderWork: 'J-1',
      screenshotsDir: shotsDir,
    });

    expect(shots).toHaveLength(4);
    expect((html.match(/<img /g) ?? []).length).toBe(4);
    expect(html).toContain('parity-screenshots');
    for (const view of ['list', 'form']) {
      const l = html.indexOf(`screenshots/legacy-${view}.png`);
      const a = html.indexOf(`screenshots/af-${view}.png`);
      expect(l, `legacy ${view} present`).toBeGreaterThan(-1);
      expect(a, `alpenflight ${view} present`).toBeGreaterThan(-1);
      expect(l, `legacy ${view} left of alpenflight`).toBeLessThan(a);
      expect(html).toContain(`class="shot-view-label">${view}</div>`);
    }
  });

  it('renders only the journey-under-work shots; another journey’s shots are excluded', async () => {
    const { generateGallery } = await loadGenerator();
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-shotscope-'));
    const shotsDir = makeShotDir(dir, [
      { journey: 'J-1', side: 'legacy', view: 'list', file: 'j1-l.png', caption: 'J1 list' },
      { journey: 'J-2', side: 'legacy', view: 'list', file: 'j2-l.png', caption: 'J2 list' },
    ]);

    const { html, shots } = generateGallery({
      reportPath: emptyReport(dir),
      outDir: resolve(dir, 'out'),
      journeyUnderWork: 'J-1',
      screenshotsDir: shotsDir,
    });

    expect(shots.map((s) => s.journey)).toEqual(['J-1']);
    expect(html).toContain('screenshots/j1-l.png');
    expect(html).not.toContain('screenshots/j2-l.png');
  });

  it('renders a pending placeholder (not a broken page) when the journey has no captures', async () => {
    const { generateGallery } = await loadGenerator();
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-pending-'));

    const { html, proofs, shots } = generateGallery({
      reportPath: emptyReport(dir),
      outDir: resolve(dir, 'out'),
      journeyUnderWork: 'J-11',
    });

    expect(proofs).toHaveLength(0);
    expect(shots).toHaveLength(0);
    expect(html).toContain('>J-11 — proof</h1>');
    expect(html).toContain('No green proof yet');
    expect(html).toContain('<span class="status pending">pending</span>');
  });

  it('a declared PNG missing on disk fails the AC5 link-check', async () => {
    const { generateGallery } = await loadGenerator();
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-ac5-'));
    const shotsDir = makeShotDir(
      dir,
      [
        { journey: 'J-1', side: 'legacy', view: 'list', file: 'legacy-list.png', caption: 'L' },
        { journey: 'J-1', side: 'alpenflight', view: 'list', file: 'af-list.png', caption: 'AF' },
      ],
      { writeFiles: ['af-list.png'] },
    );

    expect(() =>
      generateGallery({
        reportPath: emptyReport(dir),
        outDir: resolve(dir, 'out'),
        journeyUnderWork: 'J-1',
        screenshotsDir: shotsDir,
      }),
    ).toThrow(/legacy-list\.png/);
  });

  it('a declared screenshot with no caption fails the AC5 link-check', async () => {
    const { generateGallery } = await loadGenerator();
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-ac5cap-'));
    const shotsDir = makeShotDir(dir, [
      { journey: 'J-1', side: 'legacy', view: 'list', file: 'legacy-list.png', caption: '' },
    ]);

    expect(() =>
      generateGallery({
        reportPath: emptyReport(dir),
        outDir: resolve(dir, 'out'),
        journeyUnderWork: 'J-1',
        screenshotsDir: shotsDir,
      }),
    ).toThrow(/no caption/);
  });
});

describe('generateGallery — staged == rendered single source', () => {
  const J7_PAIR = [
    {
      journey: 'J-7',
      side: 'legacy',
      view: 'picker',
      file: 'legacy-picker.png',
      caption: 'L picker',
    },
    {
      journey: 'J-7',
      side: 'alpenflight',
      view: 'picker',
      file: 'af-picker.png',
      caption: 'AF picker',
    },
    {
      journey: 'J-7',
      side: 'legacy',
      view: 'result',
      file: 'legacy-result.png',
      caption: 'L result',
    },
    {
      journey: 'J-7',
      side: 'alpenflight',
      view: 'result',
      file: 'af-result.png',
      caption: 'AF result',
    },
  ];

  function renderedKeysFromHtml(html: string): Set<string> {
    const keys = new Set<string>();
    for (const m of html.matchAll(/<img\b[^>]*\balt="(legacy|alpenflight) ([^"]+)"[^>]*>/gi)) {
      keys.add(`${m[1]!.toLowerCase()}:${m[2]!}`);
    }
    return keys;
  }

  it('renderedShotKeys == the keys the generated HTML actually renders', async () => {
    const { generateGallery, extractScreenshots, renderedShotKeys } = await loadGenerator();
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-ssot-'));
    const shotsDir = makeShotDir(dir, J7_PAIR);

    const { shots } = extractScreenshots(shotsDir);
    const guardKeys = renderedShotKeys(shots, 'J-7');

    const { html } = generateGallery({
      reportPath: emptyReport(dir),
      outDir: resolve(dir, 'out'),
      journeyUnderWork: 'J-7',
      screenshotsDir: shotsDir,
    });
    const pageKeys = renderedKeysFromHtml(html);

    expect([...guardKeys].sort()).toEqual([...pageKeys].sort());
    expect([...guardKeys].sort()).toEqual([
      'alpenflight:picker',
      'alpenflight:result',
      'legacy:picker',
      'legacy:result',
    ]);
  });

  it('a ONE-SIDED stage (legacy dropped) shows up as a missing key in BOTH reads', async () => {
    const { generateGallery, extractScreenshots, renderedShotKeys } = await loadGenerator();
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-ssot-'));
    const oneSided = J7_PAIR.filter((s) => s.side === 'alpenflight');
    const shotsDir = makeShotDir(dir, oneSided);

    const { shots } = extractScreenshots(shotsDir);
    const guardKeys = renderedShotKeys(shots, 'J-7');
    const { html } = generateGallery({
      reportPath: emptyReport(dir),
      outDir: resolve(dir, 'out'),
      journeyUnderWork: 'J-7',
      screenshotsDir: shotsDir,
    });
    const pageKeys = renderedKeysFromHtml(html);

    expect(guardKeys.has('legacy:picker')).toBe(false);
    expect(pageKeys.has('legacy:picker')).toBe(false);
    expect([...guardKeys].sort()).toEqual([...pageKeys].sort());
  });

  it('drops a declared-but-absent PNG from the rendered set (mirrors the AC5 throw)', async () => {
    const { extractScreenshots, renderedShotKeys } = await loadGenerator();
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-ssot-'));
    const shotsDir = makeShotDir(
      dir,
      J7_PAIR.filter((s) => s.side === 'legacy'),
      {
        writeFiles: ['legacy-picker.png'],
      },
    );
    const { shots } = extractScreenshots(shotsDir);
    const keys = renderedShotKeys(shots, 'J-7');
    expect([...keys].sort()).toEqual(['legacy:picker']);
  });
});


const SAMPLE_AUDIT = JSON.stringify({
  verdict: 'fail',
  attribution: { dead_code_introduced: 0, complexity_introduced: 5, duplication_introduced: 16 },
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
const SAMPLE_QODANA = JSON.stringify({
  version: '2.1.0',
  runs: [
    {
      tool: { driver: { name: 'QDJVMC' } },
      results: [
        { ruleId: 'unused', level: 'warning', baselineState: 'new' },
        { ruleId: 'unused', level: 'warning', baselineState: 'unchanged' },
        { ruleId: 'unused', level: 'warning', baselineState: 'unchanged' },
      ],
    },
  ],
});
const SAMPLE_QODANA_NO_BASELINE = JSON.stringify({
  version: '2.1.0',
  runs: [{ results: [{ ruleId: 'unused' }, { ruleId: 'unused' }] }],
});

function writeMaint(
  outDir: string,
  files: Partial<
    Record<
      | 'fallow-audit.json'
      | 'fallow-health.json'
      | 'pmd-main.xml'
      | 'cpd-check.xml'
      | 'qodana-report.sarif.json',
      string
    >
  >,
): void {
  const dir = resolve(outDir, 'maintainability');
  mkdirSync(dir, { recursive: true });
  for (const [name, body] of Object.entries(files)) writeFileSync(resolve(dir, name), body!);
}

describe('parse helpers — fail-soft artifact parsing', () => {
  it('parsePmd counts total + complexity + dead-code by rule', async () => {
    const { parsePmd } = await loadGenerator();
    expect(parsePmd(SAMPLE_PMD)).toEqual({ total: 3, complexity: 2, deadCode: 1 });
  });

  it('parseCpd computes a duplication % + group count', async () => {
    const { parseCpd } = await loadGenerator();
    const cpd = parseCpd(SAMPLE_CPD);
    expect(cpd?.groups).toBe(2);
    expect(cpd?.dupPct).toBeCloseTo(3.0, 5);
  });

  it('parseQodana counts total findings + new-vs-baseline (the ratchet signal)', async () => {
    const { parseQodana } = await loadGenerator();
    expect(parseQodana(JSON.parse(SAMPLE_QODANA))).toEqual({ total: 3, newFindings: 1 });
    expect(parseQodana(JSON.parse(SAMPLE_QODANA_NO_BASELINE))).toEqual({
      total: 2,
      newFindings: null,
    });
    expect(parseQodana({ version: '2.1.0', runs: [] })).toEqual({ total: 0, newFindings: null });
    expect(parseQodana(null)).toBeNull();
  });

  it('every parser returns null on absent input (never throws)', async () => {
    const { parsePmd, parseCpd, parseFallowAudit, parseQodana } = await loadGenerator();
    expect(parsePmd(null)).toBeNull();
    expect(parseCpd(null)).toBeNull();
    expect(parseQodana(null)).toBeNull();
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
    expect(maintainabilityRollup({ audit: fail, showDelta: false }).level).toBe('neutral');
    expect(maintainabilityRollup({ audit: null, showDelta: true }).level).toBe('neutral');
  });
});

describe('generateGallery — Maintainability panel on the page', () => {
  function buildWithMaint(
    generateGallery: Awaited<ReturnType<typeof loadGenerator>>['generateGallery'],
    opts: { writeMaint?: boolean } = {},
  ): { outDir: string; html: string } {
    const dir = mkdtempSync(resolve(tmpdir(), 'gallery-maint-'));
    const { reportPath } = singleProofReport(dir, 'J-0');
    const outDir = resolve(dir, 'out');
    if (opts.writeMaint) {
      mkdirSync(outDir, { recursive: true });
      writeMaint(outDir, {
        'fallow-audit.json': SAMPLE_AUDIT,
        'fallow-health.json': SAMPLE_HEALTH,
        'pmd-main.xml': SAMPLE_PMD,
        'cpd-check.xml': SAMPLE_CPD,
        'qodana-report.sarif.json': SAMPLE_QODANA,
      });
    }
    const { html } = generateGallery({ reportPath, outDir, journeyUnderWork: 'J-0' });
    return { outDir, html };
  }

  it('renders the panel with the FE delta + snapshot + BE rows for the journey-under-work', async () => {
    const { generateGallery } = await loadGenerator();
    const { html } = buildWithMaint(generateGallery, { writeMaint: true });

    expect(html).toContain('class="maintainability"');
    expect(html).toContain('<span class="status failure">');
    expect(html).toContain('FE delta (this journey vs main)');
    expect(html).toContain('score <strong>71.1</strong> (B)');
    expect(html).toContain('<strong>3</strong> violations');
    expect(html).toContain('3.0%');
    expect(html).toContain('BE unused code (Qodana)');
    expect(html).toContain('<strong>1</strong> new vs baseline');
    expect(html).toContain('(report-only)');
  });

  it('emits maintainability/index.html so the reports dir URL serves 200', async () => {
    const { generateGallery } = await loadGenerator();
    const { outDir, html } = buildWithMaint(generateGallery, { writeMaint: true });

    const idx = resolve(outDir, 'maintainability', 'index.html');
    expect(existsSync(idx)).toBe(true);
    const idxHtml = readFileSync(idx, 'utf8');
    expect(idxHtml).toContain('href="./fallow-audit.json"');
    expect(idxHtml).toContain('href="./pmd-main.xml"');
    expect(html).toContain('href="maintainability/"');
  });

  it('is fail-soft — absent artifacts render "no data", never throws, no reports link', async () => {
    const { generateGallery } = await loadGenerator();
    const { outDir, html } = buildWithMaint(generateGallery);
    expect(html).toContain('class="maintainability"');
    expect(html).toContain('no data');
    expect(html).not.toContain('href="maintainability/"');
    expect(existsSync(resolve(outDir, 'maintainability', 'index.html'))).toBe(false);
  });
});

describe('parseArgs — CLI flags', () => {
  it('--journey-under-work parses to journeyUnderWork (the flag the page scopes on)', async () => {
    const { parseArgs } = await loadGenerator();
    const args = parseArgs(['--report', 'r.json', '--out', 'o', '--journey-under-work', 'J-11']);
    expect(args.journeyUnderWork).toBe('J-11');
    expect(args.reportPath).toBe('r.json');
    expect(args.outDir).toBe('o');
  });
});
