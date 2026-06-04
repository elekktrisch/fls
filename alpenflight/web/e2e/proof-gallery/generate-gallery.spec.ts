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
