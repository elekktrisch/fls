import { existsSync, mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

import { describe, expect, it } from 'vitest';

/**
 * J-5 T-13b — persistent JOURNEY-directory index spec.
 *
 * Locks the re-arch contract the operator asked for:
 *   - the index lists ALL roadmap journeys, in roadmap order (not active branches);
 *   - each journey WITH a per-journey page links that page;
 *   - pending journeys (no page anywhere) render WITHOUT a dead link;
 *   - the listing survives with NO active branch preview present (PR-closed case) —
 *     it sources J-0…J-N from the persistent canonical + legacy-parity archive pages;
 *   - the all-in-one per-proof-type galleries are no longer the primary destinations
 *     (the per-journey pages are); a single full-archive link remains in the footer.
 *
 * Pure JS tooling: no DB, no browser. Loads the ESM generator via dynamic import().
 */
const GENERATOR = resolve(__dirname, 'generate-previews-index.mjs');

async function loadGenerator(): Promise<{
  generatePreviewsIndex: (o: {
    ghPagesRoot: string;
    branch?: string;
    git?: boolean;
    orderPath?: string;
  }) => {
    outFile: string;
    html: string;
    journeys: { jid: string; found: boolean; href?: string; source?: string }[];
  };
  scanJourneys: (
    ghPagesRoot: string,
    o?: { branch?: string; git?: boolean; orderPath?: string },
  ) => { jid: string; found: boolean; href?: string; source?: string }[];
  resolveRoadmap: (orderPath?: string) => string[];
}> {
  return import(pathToFileURL(GENERATOR).href);
}

/** A roadmap _ORDER.md with a handful of shipped + pending journeys, in order. */
const ORDER_MD = `# roadmap

| J | Title | Epic |
|---|---|---|
| ✅ **J-0** | Locations | E-06 |
| ✅ **J-0b** | Fan-out | E-02 |
| J-1 | Aircraft | E-06 |
| J-2 | Flights | E-07 |
| J-5 | Reservations | E-08 |
| J-6 | Planning | E-08 |
`;

/** Write a per-journey page at <root>/<relBase>/<jid>/index.html. */
function writeJourneyPage(root: string, relBase: string, jid: string) {
  const dir = resolve(root, relBase, jid);
  mkdirSync(dir, { recursive: true });
  writeFileSync(resolve(dir, 'index.html'), `<!doctype html><title>${jid}</title>`, 'utf8');
}

function makeRoot(): { root: string; orderPath: string } {
  const root = mkdtempSync(resolve(tmpdir(), 'previews-idx-'));
  const orderPath = resolve(root, '_ORDER.md');
  writeFileSync(orderPath, ORDER_MD, 'utf8');
  return { root, orderPath };
}

describe('resolveRoadmap — authoritative ordered journey list', () => {
  it('parses roadmap journey ids in table order from _ORDER.md', async () => {
    const { resolveRoadmap } = await loadGenerator();
    const { orderPath } = makeRoot();
    expect(resolveRoadmap(orderPath)).toEqual(['J-0', 'J-0b', 'J-1', 'J-2', 'J-5', 'J-6']);
  });
});

describe('generatePreviewsIndex — persistent JOURNEY directory (T-13b)', () => {
  it('lists ALL roadmap journeys in order, even with no active branch preview', async () => {
    const { generatePreviewsIndex, scanJourneys } = await loadGenerator();
    const { root, orderPath } = makeRoot();
    // PR-CLOSED world: NO proof-preview/<branch>/ exists. J-0..J-2 live in the
    // persistent canonical + legacy-parity archive; J-5/J-6 have no page.
    writeJourneyPage(root, 'alpenflight/proof', 'J-0'); // canonical
    writeJourneyPage(root, 'alpenflight/proof/legacy-parity', 'J-1'); // archive
    writeJourneyPage(root, 'alpenflight/proof', 'J-2'); // canonical

    const journeys = scanJourneys(root, { orderPath }); // no branch at all
    // Listing is the FULL roadmap, in order — never just the active branch.
    expect(journeys.map((j) => j.jid)).toEqual(['J-0', 'J-0b', 'J-1', 'J-2', 'J-5', 'J-6']);

    const { outFile, html } = generatePreviewsIndex({ ghPagesRoot: root, orderPath });
    expect(existsSync(outFile)).toBe(true);
    // Every roadmap journey id appears in the rendered HTML.
    for (const jid of ['J-0', 'J-0b', 'J-1', 'J-2', 'J-5', 'J-6']) {
      expect(html).toContain(`<strong>${jid}</strong>`);
    }
    // J-0/J-1/J-2 pages exist → present-and-ordered before the pending ones.
    const i0 = html.indexOf('J-0</strong>');
    const i2 = html.indexOf('J-2</strong>');
    const i6 = html.indexOf('J-6</strong>');
    expect(i0).toBeGreaterThan(-1);
    expect(i0).toBeLessThan(i2);
    expect(i2).toBeLessThan(i6);
  });

  it('links each with-proof journey to its per-journey page (canonical + archive sources)', async () => {
    const { scanJourneys } = await loadGenerator();
    const { root, orderPath } = makeRoot();
    writeJourneyPage(root, 'alpenflight/proof', 'J-0');
    writeJourneyPage(root, 'alpenflight/proof/legacy-parity', 'J-1');

    const by = Object.fromEntries(scanJourneys(root, { orderPath }).map((j) => [j.jid, j]));
    // Canonical page → ../proof/J-0/
    expect(by['J-0'].found).toBe(true);
    expect(by['J-0'].href).toBe('../proof/J-0/');
    expect(by['J-0'].source).toBe('canonical');
    // Archive page → ../proof/legacy-parity/J-1/
    expect(by['J-1'].found).toBe(true);
    expect(by['J-1'].href).toBe('../proof/legacy-parity/J-1/');
    expect(by['J-1'].source).toBe('archive');
  });

  it('renders pending journeys WITHOUT a dead link', async () => {
    const { generatePreviewsIndex, scanJourneys } = await loadGenerator();
    const { root, orderPath } = makeRoot();
    writeJourneyPage(root, 'alpenflight/proof', 'J-0'); // only J-0 has a page

    const pending = scanJourneys(root, { orderPath }).filter((j) => !j.found);
    expect(pending.map((j) => j.jid)).toEqual(['J-0b', 'J-1', 'J-2', 'J-5', 'J-6']);
    for (const p of pending) expect(p.href).toBeUndefined();

    const { html } = generatePreviewsIndex({ ghPagesRoot: root, orderPath });
    // Pending rows carry a "pending" badge and NO <a href> for that journey.
    expect(html).toContain('pending');
    // The only anchor hrefs present are the live J-0 page + the footer archive.
    const hrefs = [...html.matchAll(/href="([^"]+)"/g)].map((m) => m[1]);
    expect(hrefs).toContain('../proof/J-0/');
    expect(hrefs).toContain('../proof/'); // footer full-archive link
    // No href ever points at a per-proof-type all-in-one gallery as a primary dest.
    expect(hrefs.some((h) => /proof-preview/.test(h))).toBe(false);
  });

  it('prefers the active branch preview page over canonical when the PR is open', async () => {
    const { scanJourneys } = await loadGenerator();
    const { root, orderPath } = makeRoot();
    const branch = 'integration-J-5';
    // J-5 page exists in BOTH the fresh branch preview and canonical → branch wins.
    writeJourneyPage(root, `alpenflight/proof-preview/${branch}`, 'J-5');
    writeJourneyPage(root, 'alpenflight/proof', 'J-5');

    const j5 = scanJourneys(root, { branch, orderPath }).find((j) => j.jid === 'J-5')!;
    expect(j5.found).toBe(true);
    expect(j5.source).toBe('branch');
    expect(j5.href).toBe(`../proof-preview/${branch}/J-5/`);
  });

  it('keeps a single full-archive link in the footer (per-proof-type galleries are no longer primary)', async () => {
    const { generatePreviewsIndex } = await loadGenerator();
    const { root, orderPath } = makeRoot();
    writeJourneyPage(root, 'alpenflight/proof', 'J-0');
    const { html } = generatePreviewsIndex({ ghPagesRoot: root, orderPath });
    // Exactly one full-archive link.
    const archiveLinks = [...html.matchAll(/href="\.\.\/proof\/"/g)];
    expect(archiveLinks.length).toBe(1);
    // The retired all-in-one wording is no longer a clickable primary listing.
    expect(html).not.toContain('Paired legacy ↔ AlpenFlight (all journeys)');
  });
});
