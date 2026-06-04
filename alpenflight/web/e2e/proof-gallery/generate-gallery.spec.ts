import { mkdtempSync, writeFileSync } from 'node:fs';
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
  }) => { html: string; roadmap: string[]; proofs: { journey: string }[]; shots: unknown[] };
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
  function buildGallery(generateGallery: any) {
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
