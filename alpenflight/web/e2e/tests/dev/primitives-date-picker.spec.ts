import { test, expect } from '@playwright/test';

/**
 * S-062e — `<af-date-picker mode="range">` zoneless smoke.
 *
 * The range picker used to deadlock the browser main thread under zoneless
 * Angular: a fresh-array-each-pass `[ngModel]` made nz-range-picker re-normalise
 * and re-schedule change detection forever, freezing /dev/primitives and the
 * /flights filter. af-date-picker now bridges the value with a reference-stable
 * array (date-value-bridge.ts).
 *
 * This spec is the deadlock guard. If the busy-loop ever returns the main
 * thread never yields, every interaction below times out — so a green run is
 * itself proof that the range mode mounts, opens, accepts a [from,to] range,
 * and emits the value without freezing. /dev/primitives is publicAccess (no
 * auth) so the mock-auth chromium project reaches it directly.
 */
test.describe('af-date-picker range — zoneless deadlock guard (S-062e)', () => {
  test('/dev/primitives mounts the range picker promptly without hanging', async ({ page }) => {
    // Warm-up navigation: amortise the dev-server's one-time cold cost (lazy
    // route chunk compile + Vite dep-optimizer pre-bundle of ng-zorro). A cold
    // first-mount on a loaded CI runner was measured at ~2.1s — that latency is
    // build tooling, NOT the picker, and is exactly what made an absolute
    // 2000ms wall-clock budget flaky (J-2 T-14, gate-revealed). The deadlock
    // guard cares about the RUNTIME settling, not the compiler warming up.
    await page.goto('/dev/primitives');
    await expect(page.getByTestId('showcase-date-picker-range')).toBeVisible();

    // Now measure a WARM re-mount. The picker (and its bridge) are already
    // compiled, so the only thing being timed is Angular's zoneless change
    // detection settling the range showcase. A busy-loop regression would pin
    // the main thread and never reach a visible, interactive picker — the
    // visibility expects below would instead exhaust the 5s expect-timeout and
    // the 30s test-timeout. 8s is the budget: an order of magnitude over a warm
    // mount (~tens of ms), comfortably above any plausible CI scheduling jitter,
    // and far below the test-timeout a true hang would consume. The threshold
    // is the non-flaky upper rail; the real deadlock signal is the
    // responsiveness check below.
    const start = Date.now();
    await page.goto('/dev/primitives');
    await expect(page.getByTestId('showcase-date-picker-range')).toBeVisible();
    const picker = page.getByTestId('showcase-range-picker');
    await expect(picker.locator('input').first()).toBeVisible();
    expect(Date.now() - start).toBeLessThan(8000);

    // The true deadlock signal: with the picker mounted, the main thread is NOT
    // pinned — a trivial round-trip resolves. Under the busy-loop this never
    // returns; the value-emission assertion in the next test (a [from,to] tuple
    // that round-trips through the value bridge) is the second, behavioural
    // proof that the range mode is live rather than frozen.
    await expect.poll(async () => page.evaluate(() => 1 + 1)).toBe(2);
  });

  test('opens, picks a from+to range, and emits a [from,to] value', async ({ page }) => {
    await page.goto('/dev/primitives');
    const section = page.getByTestId('showcase-date-picker-range');
    const value = page.getByTestId('showcase-range-value');

    // Starts empty (null).
    await expect(value).toHaveText('null');

    // Open the range panel and pick a start + end cell. ng-zorro renders two
    // calendar panels in the overlay; picking any non-disabled day in each
    // selects from then to. Using the "Today" cell of each visible panel keeps
    // the spec independent of the current month.
    await section.locator('input').first().click();
    const overlay = page.locator('.cdk-overlay-container .ant-picker-panel-container');
    await expect(overlay).toBeVisible();

    const days = overlay.locator(
      '.ant-picker-cell-in-view:not(.ant-picker-cell-disabled) .ant-picker-cell-inner',
    );
    // First click sets the range start, second sets the end.
    await days.nth(5).click();
    await days.nth(20).click();

    // The emitted value is a 2-element [from,to] tuple (not null, not a single
    // date) — proves the value bridge round-trips the range without freezing.
    await expect(value).not.toHaveText('null');
    await expect(value).toContainText('[');
    const json = await value.textContent();
    const parsed = JSON.parse(json ?? 'null') as unknown;
    expect(Array.isArray(parsed)).toBe(true);
    expect((parsed as unknown[]).length).toBe(2);

    await page.screenshot({ path: 'screenshots/dev/01-range-picked.png', fullPage: true });
  });
});
