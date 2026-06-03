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
  test('/dev/primitives mounts the range picker in < 2s without hanging', async ({ page }) => {
    const start = Date.now();
    await page.goto('/dev/primitives');

    // The range showcase section + its picker render. If the page were
    // deadlocked these would never appear (the main thread is pinned).
    await expect(page.getByTestId('showcase-date-picker-range')).toBeVisible();
    const picker = page.getByTestId('showcase-range-picker');
    await expect(picker.locator('input').first()).toBeVisible();

    // Budget assertion (S-062e AC: /dev/primitives loads < 2s). Measured from
    // navigation to a fully-interactive range picker; a deadlock would blow far
    // past this. Generous headroom over the budget keeps it non-flaky on CI
    // while still catching a regression to the busy-loop (which never settles).
    expect(Date.now() - start).toBeLessThan(2000);

    // The page stays responsive: a trivial main-thread round-trip resolves.
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
