import { expect, type Locator, type Page } from '@playwright/test';

export function afSelectTrigger(page: Page, testId: string): Locator {
  return page.getByTestId(testId).locator('nz-select');
}

export async function selectAfOption(
  page: Page,
  selectTestId: string,
  optionValue: string,
  searchTermThatRendersTheVirtualisedOption?: string,
): Promise<void> {
  const trigger = afSelectTrigger(page, selectTestId);
  await expect(trigger).toBeVisible();
  await trigger.click();

  if (searchTermThatRendersTheVirtualisedOption !== undefined) {
    const searchInput = page
      .getByTestId(selectTestId)
      .locator('input.ant-select-selection-search-input');
    await expect(searchInput).toBeVisible();
    await searchInput.fill(searchTermThatRendersTheVirtualisedOption);
  }

  const optionInFreshlyOpenedPortal = page.getByTestId(`af-select-option-${optionValue}`);
  await expect(optionInFreshlyOpenedPortal).toBeVisible();
  await optionInFreshlyOpenedPortal.click();

  await expect(optionInFreshlyOpenedPortal).toHaveCount(0);
}
