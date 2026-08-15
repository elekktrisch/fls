import { test, expect, gotoRoute, screenshot } from '../../fixtures';

const CONTACT_INPUT = '#ContactName';
const ADDRESS_INPUT = '#Address';
const SAVE_BUTTON = 'form[name="clubForm"] button[type="submit"]';

test.describe('#28 club-crud (edit own club)', () => {
  test('edit own club ContactName + Address persists across reload', async ({
    loggedInPage,
  }) => {
    await gotoRoute(loggedInPage, '/masterdata/clubs');
    await expect(loggedInPage.locator('tbody [data-testid="row"]').first()).toBeVisible();
    await screenshot(loggedInPage, 'clubs-crud-list');

    const clubId = await loggedInPage.evaluate(() => {
      const raw = sessionStorage.getItem('ngStorage-user');
      if (!raw) return null;
      const parsed = JSON.parse(raw);
      return parsed?.myClub?.ClubId ?? null;
    });
    expect(clubId, 'expected myClub.ClubId in ngStorage-user').toBeTruthy();

    await gotoRoute(loggedInPage, `/masterdata/clubs/${clubId}`);
    const contactInput = loggedInPage.locator(CONTACT_INPUT);
    const addressInput = loggedInPage.locator(ADDRESS_INPUT);
    await expect(contactInput).toBeVisible();
    await expect(addressInput).toBeVisible();

    const originalContact = (await contactInput.inputValue()) ?? '';
    const originalAddress = (await addressInput.inputValue()) ?? '';

    const stamp = Date.now();
    const newContact = `E2E Contact ${stamp}`;
    const newAddress = `E2E Address ${stamp}`;
    await contactInput.fill(newContact);
    await addressInput.fill(newAddress);

    const saveButton = loggedInPage.locator(SAVE_BUTTON);
    await expect(saveButton).toBeEnabled();
    await saveButton.click();

    await loggedInPage.waitForURL(/#\/masterdata\/clubs(?:\?.*)?$/, { timeout: 15_000 });
    await loggedInPage.waitForLoadState('domcontentloaded');

    await gotoRoute(loggedInPage, `/masterdata/clubs/${clubId}`);
    await expect(loggedInPage.locator(CONTACT_INPUT)).toHaveValue(newContact);
    await expect(loggedInPage.locator(ADDRESS_INPUT)).toHaveValue(newAddress);
    await screenshot(loggedInPage, 'clubs-crud-after-save');

    await loggedInPage.locator(CONTACT_INPUT).fill(originalContact);
    await loggedInPage.locator(ADDRESS_INPUT).fill(originalAddress);
    await loggedInPage.locator(SAVE_BUTTON).click();
    await loggedInPage.waitForURL(/#\/masterdata\/clubs(?:\?.*)?$/, { timeout: 15_000 });
  });

  test.skip(
    'create new club (SystemAdministrator-only, out of reach for the ClubAdministrator fixture)',
    (): void => undefined,
  );

  test.skip(
    'delete club (SystemAdministrator-only, out of reach for the ClubAdministrator fixture)',
    (): void => undefined,
  );

});
