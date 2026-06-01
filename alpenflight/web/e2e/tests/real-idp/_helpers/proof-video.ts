import type { Page, TestInfo } from '@playwright/test';

/**
 * J-24 proof manifest emission — bind a proof pass-video to the human assertion
 * it proves, at the assertion site.
 *
 * The `real-idp` project records `video: 'on'`, but these proof specs drive
 * their OWN `browser.newContext({ recordVideo: { dir: testInfo.outputDir } })`
 * (one isolated session per club), so the `.webm` is only flushed to disk AFTER
 * `await ctx.close()`. This helper therefore MUST be called from the test's
 * `finally`, AFTER the context is closed — otherwise `page.video()?.path()`
 * names a file that doesn't exist yet and the attach (and the gallery
 * link-check) would point at nothing.
 *
 * It does two things the J-24 gallery generator consumes (see
 * `e2e/proof-gallery/README.md` — the manifest IS the Playwright JSON report):
 *   - attaches the finalized `.webm` under the REQUIRED name `proof-video`
 *     (how the generator finds the video);
 *   - pushes the `proof-journey` / `proof-ac-tag` / `proof-caption` annotations
 *     (the journey grouping, the `[happy]/[edge]/[key-error]` chip, and the
 *     human sentence stating what the green run proves).
 *
 * Field names match the generator contract exactly — do not rename.
 *
 * @param page     the page whose context has ALREADY been closed (video flushed)
 * @param testInfo the running test's TestInfo (attachments + annotations sink)
 * @param meta.journey e.g. `J-0` — groups the proof under a journey
 * @param meta.caption a human sentence naming the assertion proved (not a slug)
 * @param meta.acTag   `happy` | `edge` | `key-error` — renders as the chip
 */
export async function proofVideo(
  page: Page,
  testInfo: TestInfo,
  meta: { journey: string; caption: string; acTag: 'happy' | 'edge' | 'key-error' },
): Promise<void> {
  // Resolve the finalized recording. `video()` is undefined when video
  // recording is off (e.g. a future non-`real-idp` reuse); skip silently
  // rather than fail the proof assertion over instrumentation.
  const videoPath = await page.video()?.path();

  if (videoPath) {
    await testInfo.attach('proof-video', { path: videoPath, contentType: 'video/webm' });
  }

  testInfo.annotations.push(
    { type: 'proof-journey', description: meta.journey },
    { type: 'proof-ac-tag', description: meta.acTag },
    { type: 'proof-caption', description: meta.caption },
  );
}
