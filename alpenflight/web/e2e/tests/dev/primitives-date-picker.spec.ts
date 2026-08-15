import { test, expect } from '../_helpers/console-guard';

test.describe('af-date-picker range — zoneless deadlock guard (S-062e)', () => {
  test('/dev/primitives mounts the range picker promptly without hanging', async ({ page }) => {
    await page.goto('/dev/primitives');
    await expect(page.getByTestId('showcase-date-picker-range')).toBeVisible();

    const start = Date.now();
    await page.goto('/dev/primitives');
    await expect(page.getByTestId('showcase-date-picker-range')).toBeVisible();
    const picker = page.getByTestId('showcase-range-picker');
    await expect(picker.locator('input').first()).toBeVisible();
    expect(Date.now() - start).toBeLessThan(8000);

    await expect.poll(async () => page.evaluate(() => 1 + 1)).toBe(2);
  });

  test('opens, picks a from+to range, and emits a [from,to] value', async ({ page }) => {
    await page.goto('/dev/primitives');
    const section = page.getByTestId('showcase-date-picker-range');
    const value = page.getByTestId('showcase-range-value');

    await expect(value).toHaveText('null');

    await section.locator('input').first().click();
    const overlay = page.locator('.cdk-overlay-container .ant-picker-panel-container');
    await expect(overlay).toBeVisible();

    const days = overlay.locator(
      '.ant-picker-cell-in-view:not(.ant-picker-cell-disabled) .ant-picker-cell-inner',
    );
    await days.nth(5).click();
    await days.nth(20).click();

    await expect(value).not.toHaveText('null');
    await expect(value).toContainText('[');
    const json = await value.textContent();
    const parsed = JSON.parse(json ?? 'null') as unknown;
    expect(Array.isArray(parsed)).toBe(true);
    expect((parsed as unknown[]).length).toBe(2);

    await page.screenshot({ path: 'screenshots/dev/01-range-picked.png', fullPage: true });
  });
});
