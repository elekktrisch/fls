import { expect, type Locator, type Page } from '@playwright/test';


export function afSelectTrigger(page: Page, testId: string): Locator {
  return page.getByTestId(testId).locator('nz-select');
}

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
    const searchInput = page
      .getByTestId(selectTestId)
      .locator('input.ant-select-selection-search-input');
    await expect(searchInput).toBeVisible();
    await searchInput.fill(search);
  }

  const option = page.getByTestId(`af-select-option-${optionValue}`);
  await expect(option).toBeVisible();
  await option.click();

  await expect(option).toHaveCount(0);
}
