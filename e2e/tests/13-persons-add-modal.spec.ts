// e2e/tests/13-persons-add-modal.spec.ts
//
// Plan row #13: Modal-driven person creation via AddPersonController.
//
// Contract gaps / reality check vs. the PLAN.md prediction:
//   - The PLAN suggests the "Add Person" modal is invoked from
//     /masterdata/persons. In the actual AngularJS client it is NOT: the
//     persons list's "+" button (`fls-data-table` -> `onNewClick="newPerson"`)
//     navigates to `/masterdata/persons/new`, which renders a full-page
//     <fls-person-form> edit view (persons-edit.html), not a modal.
//   - The real `AddPersonController` modal (templated from
//     flsweb/src/masterdata/persons/modal/add-person.html) is opened from
//     `UsersEditController.newPerson()` via `$modal.open(...)` and from
//     `FlightsController` for crew lookups. So to exercise the actual modal
//     flow we open it from a User-edit page, drive the modal, then assert
//     the new person shows up at /masterdata/persons.
//   - ui.bootstrap.modal renders the modal at the <body> level (outside
//     <ng-view>), so we must NOT scope queries inside the route view.
//   - There are NO data-testids on the modal or its inputs today.
//     TODO testid: stable markers we would have leaned on if they existed —
//       * `add-person-modal` on the modal <form> root (currently
//         <form name="personForm"> inside add-person.html, line 1).
//       * `firstname-input`, `lastname-input`, `email-input` on the three
//         shared inputs in person-form-fields.html (currently identified
//         only by `id="Firstname"` / `id="Lastname"` / `id="Email"`).
//       * `modal-submit` on the OK button (currently <button type="submit">
//         translated "OK") and `modal-cancel` on the Cancel button.
//     For now we lean on semantic selectors (getByRole('dialog'), id-based
//     locators) and on the page's row-click pattern for the assertion.

import { expect, gotoRoute, screenshot, test } from '../fixtures';
import type { Page } from '@playwright/test';

async function openFirstUserEditPage(page: Page): Promise<void> {
  await gotoRoute(page, '/masterdata/users');
  const firstRow = page.locator('tbody [data-testid="row"]').first();
  await firstRow.waitFor({ state: 'visible' });
  const urlBefore = page.url();
  await firstRow.click();
  await page.waitForFunction((prev) => location.href !== prev, urlBefore);
  await page.waitForLoadState('domcontentloaded');
  await page.waitForTimeout(500);
}

test('persons-add-modal: create person via $modal and assert in list', async ({
  loggedInPage,
  freshDb,
}) => {
  // 1. Reach a user-edit page so the "NEW" person modal trigger is available.
  //    The button lives in user-form-fields.html next to the person selectize:
  //      <button type="button" ng-click="newPerson()" translate="NEW">
  await openFirstUserEditPage(loggedInPage);

  // 2. Click the "NEW" button to open the AddPerson modal. Match by role +
  //    accessible name. Translations are loaded from the server (default 'de'
  //    -> "NEU", English -> "NEW"), so accept either.
  const newPersonButton = loggedInPage
    .getByRole('button', { name: /^\s*(NEW|NEU)\s*$/i })
    .first();
  await newPersonButton.waitFor({ state: 'visible' });
  await newPersonButton.click();

  // 3. The modal is rendered at <body> level by ui.bootstrap.modal — use the
  //    ARIA dialog role rather than scoping inside <ng-view>.
  const dialog = loggedInPage.getByRole('dialog');
  await expect(dialog).toBeVisible({ timeout: 10_000 });

  // 4. Fill required + key fields. The shared <fls-person-form> exposes
  //    Firstname / Lastname (both `required` on the input) and an Email input
  //    bound to person.PrivateEmail (the communication email).
  const stamp = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  const firstname = `E2EFirst${stamp}`;
  const lastname = `E2ELast${stamp}`;
  const email = `e2e-${stamp}@example.test`;

  await dialog.locator('#Firstname').fill(firstname);
  await dialog.locator('#Lastname').fill(lastname);
  await dialog.locator('#Email').fill(email);

  // 5. Submit the modal. add-person.html has a single <button type="submit">
  //    inside the modal form; it is enabled once required fields are valid.
  const okButton = dialog.locator('button[type="submit"]');
  await expect(okButton).toBeEnabled();
  await okButton.click();

  // 6. The modal closes and UsersEditController.newPerson() chains
  //    `new PersonPersister(person).$save()`. Wait for the dialog to detach.
  await expect(dialog).toBeHidden({ timeout: 10_000 });
  await loggedInPage.waitForLoadState('domcontentloaded');

  // 7. Verify the new person is queryable via the API (independent of UI
  //    filtering quirks). Pull the bearer token stashed by `loggedInPage`.
  const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';
  const token = await loggedInPage.evaluate(() => {
    const raw = sessionStorage.getItem('ngStorage-loginResult');
    return raw ? (JSON.parse(raw).access_token as string) : null;
  });
  expect(token, 'expected access_token in sessionStorage').toBeTruthy();

  // The persons list uses POST /api/v1/persons/page with filter+sorting.
  const apiRes = await loggedInPage.request.post(
    `${API_BASE}/api/v1/persons/page/0/50`,
    {
      headers: { Authorization: `Bearer ${token!}`, 'Content-Type': 'application/json' },
      data: { filter: { Lastname: lastname }, sorting: {} },
    },
  );
  expect(apiRes.ok(), `persons paged query -> ${apiRes.status()}`).toBeTruthy();
  const apiBody = await apiRes.json();
  const apiMatches = (apiBody?.Items ?? []).filter(
    (p: { Firstname?: string; Lastname?: string }) =>
      p.Lastname === lastname && p.Firstname === firstname,
  );
  expect(apiMatches.length, 'expected exactly one persisted person via API').toBe(1);

  // 8. Assert the new row also appears in the UI list. Navigate to the
  //    persons list and filter by lastname through the ng-table filter input.
  //    The first column header carries `filter="{ Firstname: 'text'}"` etc.,
  //    which ng-table renders as <input name="Lastname"> in the filter row.
  await gotoRoute(loggedInPage, '/masterdata/persons');
  const lastnameFilter = loggedInPage.locator('input[name="Lastname"]').first();
  await lastnameFilter.waitFor({ state: 'visible' });
  await lastnameFilter.fill(lastname);
  // ng-table debounces; wait for the busy spinner to settle after refetch.
  await loggedInPage.waitForTimeout(800);
  await loggedInPage.waitForFunction(() => {
    const spinners = Array.from(
      document.querySelectorAll('[data-testid="busy-indicator"]'),
    ) as HTMLElement[];
    return spinners.every((el) => {
      const r = el.getBoundingClientRect();
      return r.width === 0 && r.height === 0;
    });
  }, undefined, { timeout: 15_000 });

  const matchingRow = loggedInPage
    .locator('tbody [data-testid="row"]')
    .filter({ hasText: lastname });
  await expect(matchingRow, 'new person row visible in /masterdata/persons').toHaveCount(1, {
    timeout: 10_000,
  });
  await expect(matchingRow).toContainText(firstname);
  await screenshot(loggedInPage, '13-persons-add-modal-01');
});
