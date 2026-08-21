import { expect, type Locator, type Page } from '@playwright/test';

export function afDatePickerInputs(page: Page, testId: string): Locator {
  return page.getByTestId(testId).locator('input');
}

export function afDatePickerPanel(page: Page): Locator {
  return page.locator('.cdk-overlay-container .ant-picker-panel-container');
}

export async function typeDateOnlyAfterTheOverlayPanelExists(
  page: Page,
  dateInput: Locator,
  displayDate: string,
): Promise<void> {
  await expect(dateInput).toBeVisible();
  await dateInput.click();
  await expect(
    afDatePickerPanel(page),
    'nz-date-picker reads its overlay panel on every ngModelChange, so a keystroke that lands before the panel renders throws a TypeError',
  ).toBeVisible();
  await dateInput.fill(displayDate);
  await dateInput.press('Enter');
}

export async function typeDateIntoAfDatePicker(
  page: Page,
  testId: string,
  displayDate: string,
): Promise<void> {
  await typeDateOnlyAfterTheOverlayPanelExists(
    page,
    afDatePickerInputs(page, testId).first(),
    displayDate,
  );
}
