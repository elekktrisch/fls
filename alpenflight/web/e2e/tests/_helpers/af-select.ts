import { expect, type Locator, type Page } from '@playwright/test';

/**
 * `<af-select>` (nz-select-backed) interaction helper.
 *
 * S-062f — the flights-list air-state filter dropdown was flaky without
 * retries. Root cause: nz-select renders its option list into a CDK-overlay
 * portal. After a `page.goBack()` (navigating away to the edit route and back),
 * the previous portal can linger in a detaching/animating state, so a bare
 * `select.click()` → `option.click()` either:
 *   - clicks while the old portal is still animating out → the new portal opens
 *     a frame later and the option click lands on nothing, OR
 *   - finds a stale duplicate option node from the detaching portal.
 *
 * The fix is to drive the dropdown deterministically:
 *   1. wait for the trigger to be stable + click it,
 *   2. wait for the freshly-opened option to be VISIBLE (not just attached) —
 *      this gates on the new portal having finished opening,
 *   3. click the option, then
 *   4. assert the dropdown closed (the overlay detached) so a follow-on
 *      interaction never races the still-open portal.
 *
 * No `waitForTimeout` anywhere — every step waits on app/DOM state.
 */

/** Locate the nz-select trigger inside an `<af-select>` by its data-testid. */
export function afSelectTrigger(page: Page, testId: string): Locator {
  return page.getByTestId(testId).locator('nz-select');
}

/**
 * Open the `<af-select>` identified by `selectTestId` and pick the option whose
 * value is `optionValue` (the option carries `data-testid="af-select-option-<value>"`).
 * Robust against the goBack()→stale-portal race (S-062f).
 *
 * `search` (J-5 T-27): `<af-select>` enables `nzShowSearch` by default, and
 * nz-select VIRTUALISES a long option list — it only renders the options inside
 * the visible scroll viewport, so a target option far down a many-row picker
 * (e.g. the reservation aircraft picker on a tenant with 16+ aircraft) is never
 * attached and `toBeVisible()` times out even though the option exists. When the
 * caller passes a `search` term (the option LABEL or a unique fragment of it),
 * type it into the nz-select filter input first so ng-zorro narrows the rendered
 * options to the match before we click by value. No `waitForTimeout` — the
 * subsequent `toBeVisible()` gates on the filtered option rendering.
 */
export async function selectAfOption(
  page: Page,
  selectTestId: string,
  optionValue: string,
  search?: string,
): Promise<void> {
  const trigger = afSelectTrigger(page, selectTestId);
  await expect(trigger).toBeVisible();
  await trigger.click();

  if (search !== undefined) {
    // The open nz-select renders a search box (`.ant-select-selection-search-input`)
    // inside the trigger; typing filters the (virtualised) option list so the
    // target option renders even on a long picker.
    const searchInput = page
      .getByTestId(selectTestId)
      .locator('input.ant-select-selection-search-input');
    await expect(searchInput).toBeVisible();
    await searchInput.fill(search);
  }

  const option = page.getByTestId(`af-select-option-${optionValue}`);
  // Gate on the freshly-opened (and, when searching, filtered) portal: the
  // option must be VISIBLE, which only holds once the overlay has finished
  // opening / filtering. This is the assertion that closes the goBack() flake.
  await expect(option).toBeVisible();
  await option.click();

  // The overlay detaches on select; wait for it so a follow-on dropdown
  // interaction never clicks through a still-animating portal.
  await expect(option).toHaveCount(0);
}
