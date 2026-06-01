import {
  copyFileSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

import { expect, test } from '@playwright/test';

/**
 * J-24 gate spec — the journey's green bar.
 *
 * Runs under the `chromium` (mock-auth) project: NO backend, NO Keycloak, NO
 * real-idp. It runs the T-01 generator against the COMMITTED fixtures
 * (`e2e/proof-gallery/fixtures/` — one green journey J-0 with 3 captioned
 * videos, every other roadmap journey pending) into a temp out dir, loads the
 * emitted `proof/index.html` over `file://`, then asserts the contract:
 *
 *   (a) [happy]  every fixture proof video has a non-empty caption that reads
 *                as a sentence — NOT a slug / hash / .webm filename.
 *   (b) [happy]  every <video> src resolves — the referenced .webm the
 *                generator copied into the out dir exists on disk.
 *   (c) [edge]   a roadmap journey with no green proof (e.g. J-1) renders a
 *                "pending" marker and NO anchor / broken link.
 *
 * These are assertions on deterministic committed fixtures — run fully real,
 * no mocking.
 */

// `__dirname` is available because Playwright transpiles specs to CJS; the
// fixtures + generator both resolve relative to this spec file.
const PROOF_GALLERY = resolve(__dirname, '..', '..', 'proof-gallery');
const FIXTURES = resolve(PROOF_GALLERY, 'fixtures');
const REPORT = resolve(FIXTURES, 'proof-manifest.json');

// The T-01 generator ships as an ESM `.mjs`; this CJS-transpiled spec loads it
// via dynamic `import()` (documented surface: `generateGallery({ reportPath,
// outDir, orderPath? })`).
async function loadGenerator(): Promise<{
  generateGallery: (o: {
    reportPath: string;
    outDir: string;
    orderPath?: string;
    branch?: string;
  }) => { outFile: string; proofs: { journey: string }[]; roadmap: string[] };
}> {
  const url = pathToFileURL(resolve(PROOF_GALLERY, 'generate-gallery.mjs')).href;
  return import(url);
}

// A caption must read as a human assertion, not an opaque slug/hash/filename.
const SLUG_OR_HASH = /^page@|\.webm$|^[a-f0-9-]{16,}$/;

test.describe('J-24 proof-video gallery', () => {
  test('generated gallery — captions are sentences, videos resolve, pending journeys are not broken links', async ({
    page,
  }) => {
    const { generateGallery } = await loadGenerator();

    // --- run the generator against the committed fixtures into a temp dir ---
    const outDir = mkdtempSync(resolve(tmpdir(), 'proof-gallery-'));
    const { outFile, proofs, roadmap } = generateGallery({
      reportPath: REPORT,
      outDir,
      branch: 'integration/J-24',
    });

    // Fixture sanity: the green journey (J-0) emitted exactly its 3 proofs, and
    // at least one roadmap journey is pending (otherwise (c) tests nothing).
    expect(proofs.length, 'fixtures must emit the J-0 proof videos').toBe(3);
    const pending = roadmap.filter((j) => !proofs.some((p) => p.journey === j));
    expect(pending.length, 'at least one roadmap journey must be pending').toBeGreaterThan(0);

    // --- load the generated page over file:// ---
    await page.goto(pathToFileURL(outFile).href);
    await expect(page.locator('h1')).toHaveText('AlpenFlight proof gallery');

    // (a) [happy] — every rendered proof video carries a sentence caption.
    const figures = page.locator('figure.proof');
    await expect(figures).toHaveCount(proofs.length);

    const captions = await figures.locator('figcaption .caption').allInnerTexts();
    expect(captions).toHaveLength(proofs.length);
    for (const caption of captions) {
      const text = caption.trim();
      expect(text, 'caption must be non-empty').not.toBe('');
      expect(text, `caption must read as a sentence, not a slug/hash: "${text}"`).not.toMatch(
        SLUG_OR_HASH,
      );
      // A real assertion sentence has whitespace; a slug/filename would not.
      expect(text, `caption must be multi-word prose: "${text}"`).toMatch(/\s/);
    }

    // (b) [happy] — every <video> src resolves to a file the generator copied
    // into the out dir (no broken/dangling video reference).
    const videoSrcs = await page
      .locator('figure.proof video')
      .evaluateAll((els) => els.map((el) => el.getAttribute('src') ?? ''));
    expect(videoSrcs).toHaveLength(proofs.length);
    for (const src of videoSrcs) {
      expect(src, 'video must have a src').not.toBe('');
      expect(src, `video src must not be an absolute/file URL: "${src}"`).not.toMatch(
        /^(https?:|file:|\/)/,
      );
      const onDisk = resolve(outDir, src);
      expect(existsSync(onDisk), `referenced video must exist on disk: ${onDisk}`).toBe(true);
    }

    // (c) [edge] — a pending roadmap journey renders a "pending" marker and is
    // NOT an anchor / broken link.
    const pendingJourney = pending[0];
    const pendingCard = page
      .locator('.journey.pending-journey')
      .filter({ hasText: pendingJourney });
    await expect(pendingCard).toHaveCount(1);
    await expect(pendingCard.locator('.status.pending')).toHaveText('pending');
    // The pending card carries no link to click (no 404 / broken href).
    await expect(pendingCard.locator('a')).toHaveCount(0);
    await expect(pendingCard.locator('video')).toHaveCount(0);

    await page.screenshot({
      path: 'screenshots/proof-gallery/01-gallery-loaded.png',
      fullPage: true,
    });

    // Focused shot of a green journey's captioned proofs (the happy artifact).
    const greenCard = page.locator('.journey:not(.pending-journey)').first();
    await greenCard.scrollIntoViewIfNeeded();
    await page.screenshot({
      path: 'screenshots/proof-gallery/02-green-journey-proofs.png',
      fullPage: true,
    });

    // Focused shot of a pending journey row (the edge artifact).
    await pendingCard.scrollIntoViewIfNeeded();
    await page.screenshot({
      path: 'screenshots/proof-gallery/03-pending-journey.png',
      fullPage: true,
    });
  });

  // (d) [key-error] AC5 — the link-check. The generator MUST reject a published
  // proof video that has (i) a missing/empty caption, or (ii) a caption (an
  // attachment) referencing a `.webm` not present in the proof output. This
  // exercises the generator's REAL code path (no mocking): we copy the committed
  // fixture manifest + its `videos/`, tamper one entry in a temp dir, and assert
  // `generateGallery` throws the AC5 error. This locks the [key-error] AC at the
  // spec level — previously it was only enforced in the generator's own code.
  test('[key-error] AC5 — generator throws on missing caption / missing .webm', async () => {
    const { generateGallery } = await loadGenerator();

    // Narrow shape of the bits of the Playwright JSON report the tampers touch
    // (avoids `any` — CLAUDE.md §10). Only the fields the generator reads.
    interface ManifestTest {
      annotations?: { type: string; description?: string }[];
      results: { attachments: { name: string; path?: string }[] }[];
    }
    interface Manifest {
      suites: { specs: { tests: ManifestTest[] }[] }[];
    }
    const firstTest = (report: Manifest): ManifestTest => {
      const t = report.suites[0]?.specs[0]?.tests[0];
      if (!t) throw new Error('fixture manifest is missing its first proof test');
      return t;
    };

    // Stage a tamperable copy of the fixtures: a manifest + its videos/ dir,
    // co-located so the generator resolves attachment paths against it (it
    // resolves `att.path` relative to the report file's dir).
    const stage = (mutate: (report: Manifest) => void): string => {
      const dir = mkdtempSync(resolve(tmpdir(), 'proof-gallery-ac5-'));
      mkdirSync(resolve(dir, 'videos'), { recursive: true });
      for (const name of [
        'j0-tenant-isolation.webm',
        'j0-cross-tenant-404.webm',
        'j0-icao-per-club.webm',
      ]) {
        copyFileSync(resolve(FIXTURES, 'videos', name), resolve(dir, 'videos', name));
      }
      const report = JSON.parse(readFileSync(REPORT, 'utf8')) as Manifest;
      mutate(report);
      const tampered = resolve(dir, 'proof-manifest.json');
      writeFileSync(tampered, JSON.stringify(report), 'utf8');
      return tampered;
    };

    // (i) missing/empty caption — null out the proof-caption annotation.
    const noCaption = stage((report) => {
      const t = firstTest(report);
      t.annotations = (t.annotations ?? []).filter((a) => a.type !== 'proof-caption');
    });
    expect(() =>
      generateGallery({ reportPath: noCaption, outDir: mkdtempSync(resolve(tmpdir(), 'pg-out-')) }),
    ).toThrow(/link-check failed \(AC5\)[\s\S]*no caption/);

    // (ii) caption references a .webm not present in the proof output — repoint
    // the attachment at a file that does not exist.
    const missingWebm = stage((report) => {
      const att = firstTest(report).results[0]?.attachments.find((a) => a.name === 'proof-video');
      if (att) att.path = 'videos/does-not-exist.webm';
    });
    expect(() =>
      generateGallery({
        reportPath: missingWebm,
        outDir: mkdtempSync(resolve(tmpdir(), 'pg-out-')),
      }),
    ).toThrow(/link-check failed \(AC5\)[\s\S]*not present in the proof output/);
  });
});
