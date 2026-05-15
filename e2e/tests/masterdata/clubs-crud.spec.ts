/**
 * #28 — club-crud
 *
 * Edit the current user's own club via `/masterdata/clubs`.
 *
 * Scope decision (verified against server source, not just docs):
 *   - `ClubsController.Insert` is `[Authorize(Roles = SystemAdministrator)]` and
 *     `ClubService.DeleteClub` throws `UnauthorizedAccessException` unless
 *     `IsCurrentUserInRoleSystemAdministrator`. `testclubadmin` is in role
 *     `ClubAdministrator` only (see seed `_test-fixture.sql` /
 *     `4 or 5 Insert Test Data.sql`), so create + delete are out of scope and
 *     skipped explicitly below with a pointer to the gating code.
 *   - `Update` has no role attribute; `GetClubDetails` returns
 *     `CanUpdateRecord = true` for a club-admin in their own club, and the
 *     save button is `ng-disabled="!club.CanUpdateRecord"`. So edit works for
 *     `testclubadmin` on their own club.
 *
 * Strategy:
 *   - Read the current user's club id from sessionStorage `ngStorage-user`
 *     (the fixture seeds it via `Clubs.getMyClub()` — see fixtures.ts:33).
 *   - Round-trip: edit ContactName + Address → save → reload → assert →
 *     restore original values so the worker-scoped freshDb state stays clean
 *     for subsequent tests in the worker.
 *
 * Why not click the row in `clubs-table`? Either works (the `<tr>` is the
 * click target — see `clubs-table.html` and SELECTORS.md "Row-click pattern"),
 * but going straight to `/masterdata/clubs/:id` avoids racing the
 * ng-table-driven list render and keeps the spec independent of which
 * clubs the page happens to surface for the current user.
 */
import { test, expect, gotoRoute, screenshot } from '../../fixtures';

const CONTACT_INPUT = '#ContactName';
const ADDRESS_INPUT = '#Address';
// Scope to the club edit form — the route also embeds a `<fls-history>`
// dropdown that contains its own `button[type="submit"]`, so the bare
// selector hits two elements.
const SAVE_BUTTON = 'form[name="clubForm"] button[type="submit"]';

test.describe('#28 club-crud (edit own club)', () => {
  test('edit own club ContactName + Address persists across reload', async ({
    loggedInPage,
  }) => {
    // 1. Visit the list so the route is registered and the ng-table renders.
    await gotoRoute(loggedInPage, '/masterdata/clubs');
    await expect(loggedInPage.locator('tbody [data-testid="row"]').first()).toBeVisible();
    await screenshot(loggedInPage, 'clubs-crud-list');

    // 2. Resolve the current user's club id from ngStorage-user.myClub.
    //    Fixtures.ts hydrates this via /api/v1/clubs/my during login.
    const clubId = await loggedInPage.evaluate(() => {
      const raw = sessionStorage.getItem('ngStorage-user');
      if (!raw) return null;
      const parsed = JSON.parse(raw);
      return parsed?.myClub?.ClubId ?? null;
    });
    expect(clubId, 'expected myClub.ClubId in ngStorage-user').toBeTruthy();

    // 3. Open the edit form for the current user's own club.
    await gotoRoute(loggedInPage, `/masterdata/clubs/${clubId}`);
    const contactInput = loggedInPage.locator(CONTACT_INPUT);
    const addressInput = loggedInPage.locator(ADDRESS_INPUT);
    await expect(contactInput).toBeVisible();
    await expect(addressInput).toBeVisible();

    const originalContact = (await contactInput.inputValue()) ?? '';
    const originalAddress = (await addressInput.inputValue()) ?? '';

    // 4. Apply a unique edit so a flaky leftover from a prior run can't false-pass.
    const stamp = Date.now();
    const newContact = `E2E Contact ${stamp}`;
    const newAddress = `E2E Address ${stamp}`;
    await contactInput.fill(newContact);
    await addressInput.fill(newAddress);

    // The save button is `ng-disabled="clubForm.$invalid || !club.CanUpdateRecord"`.
    // For testclubadmin in their own club, CanUpdateRecord is true.
    const saveButton = loggedInPage.locator(SAVE_BUTTON);
    await expect(saveButton).toBeEnabled();
    await saveButton.click();

    // `$scope.save` on success calls `cancel()` → `$location.path('/masterdata/clubs')`.
    await loggedInPage.waitForURL(/#\/masterdata\/clubs(?:\?.*)?$/, { timeout: 15_000 });
    await loggedInPage.waitForLoadState('domcontentloaded');

    // 5. Reload the edit form and assert the new values stuck.
    await gotoRoute(loggedInPage, `/masterdata/clubs/${clubId}`);
    await expect(loggedInPage.locator(CONTACT_INPUT)).toHaveValue(newContact);
    await expect(loggedInPage.locator(ADDRESS_INPUT)).toHaveValue(newAddress);
    await screenshot(loggedInPage, 'clubs-crud-after-save');

    // 6. Restore original values to leave the worker DB in its seeded state.
    //    freshDb is worker-scoped: tests after this one share the same DB,
    //    so we tidy up rather than rely on the next freshDb run.
    await loggedInPage.locator(CONTACT_INPUT).fill(originalContact);
    await loggedInPage.locator(ADDRESS_INPUT).fill(originalAddress);
    await loggedInPage.locator(SAVE_BUTTON).click();
    await loggedInPage.waitForURL(/#\/masterdata\/clubs(?:\?.*)?$/, { timeout: 15_000 });
  });

  // ---------------------------------------------------------------------------
  // Create + delete are SystemAdministrator-only on the server. testclubadmin
  // is ClubAdministrator scope, so we don't exercise them here; documenting
  // the gating points so the suite output makes the intent explicit.
  // ---------------------------------------------------------------------------
  test.skip('create new club (SystemAdministrator-only)', () => {
    // ClubsController.Insert: [Authorize(Roles = RoleApplicationKeyStrings.SystemAdministrator)]
    // Out of scope for testclubadmin; covered by a future SysAdmin-fixture spec.
  });

  test.skip('delete club (SystemAdministrator-only)', () => {
    // ClubService.DeleteClub: throws UnauthorizedAccessException unless
    // IsCurrentUserInRoleSystemAdministrator (ClubService.cs ~:255). The
    // pencil/trash icon in clubs-table.html is `ng-show="club.CanDeleteRecord"`
    // which is false for a club-admin viewing their own club row.
  });

  // TODO testid: the clubs-edit save button currently relies on
  // `button[type="submit"]` inside the <form>. If the form gets a second submit
  // button (e.g. "Save and stay"), tag the primary submit with
  // data-testid="form-save" and switch the selector here.
});
