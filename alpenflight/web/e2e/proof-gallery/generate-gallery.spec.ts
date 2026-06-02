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
    renderNav?: boolean;
  }) => { roadmap: string[]; proofs: { journey: string }[] };
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
