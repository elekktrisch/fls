import { type Route } from '@playwright/test';
import { expect, test, allowConsoleErrors } from '../_helpers/console-guard';


interface MockUserListItem {
  id: string;
  username: string;
  friendlyName: string;
  notificationEmail: string;
  personId?: string;
  roles: string[];
  enabled: boolean;
  invitePending: boolean;
}

interface MockUserResponse extends MockUserListItem {
  clubId: string;
  phoneNumber?: string;
  remarks?: string;
  languageId: string;
}

interface MockPersonLookupMatch {
  id: string;
  firstname: string;
  lastname: string;
  birthday?: string;
  email?: string;
  alreadyInThisClub: boolean;
}

const CLUB_A_ID = '019e30c3-2c00-7001-8000-000000000001';
const SELF_USER_ID = 'usr-019e30c3-2c00-7100-8000-000000000001';
const SEED_USER_ID = 'usr-019e30c3-2c00-7100-8000-000000000002';
const PENDING_USER_ID = 'usr-019e30c3-2c00-7100-8000-000000000003';
const SYSADMIN_USER_ID = 'usr-019e30c3-2c00-7100-8000-000000000004';

const LANG_DE = '019e2e15-2c00-77d0-8000-0000000007d0';

const seedUsers: MockUserResponse[] = [
  {
    id: SELF_USER_ID,
    clubId: CLUB_A_ID,
    username: 'mock-sysadmin',
    friendlyName: 'Mock Sysadmin',
    notificationEmail: 'mock@local',
    languageId: LANG_DE,
    roles: ['CLUB_ADMINISTRATOR', 'PILOT'],
    enabled: true,
    invitePending: false,
  },
  {
    id: SEED_USER_ID,
    clubId: CLUB_A_ID,
    username: 'h.meier',
    friendlyName: 'Hans Meier',
    notificationEmail: 'hans.meier@example.test',
    languageId: LANG_DE,
    roles: ['PILOT'],
    enabled: true,
    invitePending: false,
  },
  {
    id: PENDING_USER_ID,
    clubId: CLUB_A_ID,
    username: 'p.invitee',
    friendlyName: 'Petra Invitee',
    notificationEmail: 'petra@example.test',
    languageId: LANG_DE,
    roles: ['PILOT'],
    enabled: true,
    invitePending: true,
  },
  {
    id: SYSADMIN_USER_ID,
    clubId: CLUB_A_ID,
    username: 's.admin',
    friendlyName: 'Sandra Admin',
    notificationEmail: 'sandra@example.test',
    languageId: LANG_DE,
    roles: ['SYSTEM_ADMINISTRATOR', 'PILOT'],
    enabled: true,
    invitePending: false,
  },
];

const seedLookupPerson: MockPersonLookupMatch = {
  id: 'pn-019e30c3-2c00-7001-8000-000000000a01',
  firstname: 'Anna',
  lastname: 'Bühler',
  birthday: '1985-04-12',
  email: 'anna.buehler@example.test',
  alreadyInThisClub: false,
};

function toListItem(u: MockUserResponse): MockUserListItem {
  const item: MockUserListItem = {
    id: u.id,
    username: u.username,
    friendlyName: u.friendlyName,
    notificationEmail: u.notificationEmail,
    roles: [...u.roles],
    enabled: u.enabled,
    invitePending: u.invitePending,
  };
  if (u.personId) item.personId = u.personId;
  return item;
}

async function stubReferenceData(page: import('@playwright/test').Page): Promise<void> {
  for (const path of [
    'countries',
    'club-states',
    'location-types',
    'aircraft-types',
    'aircraft-states',
    'counter-unit-types',
  ]) {
    await page.route(`**/api/v1/${path}**`, (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
    );
  }
  await page.route('**/api/v1/clubs**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
  await page.route('**/api/v1/locations**', (route) => {
    const u = new URL(route.request().url());
    if (u.pathname === '/api/v1/locations') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    }
    return route.fallback();
  });
  await page.route('**/api/v1/aircraft**', (route) => {
    const u = new URL(route.request().url());
    if (u.pathname === '/api/v1/aircraft' || u.pathname === '/api/v1/aircraft/picker') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    }
    return route.fallback();
  });
  await page.route('**/api/v1/club/member-states**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
  await page.route('**/api/v1/persons**', (route) => {
    const u = new URL(route.request().url());
    const method = route.request().method();
    if (method === 'POST' && u.pathname === '/api/v1/persons/lookup') {
      const body = route.request().postDataJSON() as {
        email?: string;
        firstname?: string;
        lastname?: string;
        birthday?: string;
      };
      const matched =
        (body.email && body.email === seedLookupPerson.email) ||
        (body.firstname === seedLookupPerson.firstname &&
          body.lastname === seedLookupPerson.lastname &&
          body.birthday === seedLookupPerson.birthday);
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ matches: matched ? [seedLookupPerson] : [] }),
      });
    }
    if (u.pathname === '/api/v1/persons') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    }
    return route.fallback();
  });
}

function setupUsersBackend(users: MockUserResponse[]) {
  let nextId = 1000;
  return async (route: Route) => {
    const req = route.request();
    const url = new URL(req.url());
    const method = req.method();
    const path = url.pathname;
    const idMatch = path.match(/^\/api\/v1\/users\/(usr-[^/]+)$/);
    const resendMatch = path.match(/^\/api\/v1\/users\/(usr-[^/]+)\/resend-invite$/);

    if (method === 'GET' && path === '/api/v1/users') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(users.map(toListItem)),
      });
      return;
    }
    if (method === 'GET' && idMatch) {
      const found = users.find((u) => u.id === idMatch[1]);
      await route.fulfill({
        status: found ? 200 : 404,
        contentType: 'application/json',
        body: JSON.stringify(found ?? {}),
      });
      return;
    }
    if (method === 'POST' && path === '/api/v1/users') {
      const body = req.postDataJSON() as {
        username: string;
        friendlyName: string;
        notificationEmail: string;
        languageId: string;
        roles: string[];
        personId?: string;
        phoneNumber?: string;
        remarks?: string;
      };
      if (users.some((u) => u.username === body.username)) {
        await route.fulfill({
          status: 409,
          contentType: 'application/problem+json',
          body: JSON.stringify({
            type: 'https://alpenflight.ch/problems/user-conflict',
            title: 'Conflict',
            detail: `username "${body.username}" already in use`,
          }),
        });
        return;
      }
      const id = `usr-019e30c3-2c00-7100-8000-${String(nextId++).padStart(12, '0')}`;
      const created: MockUserResponse = {
        id,
        clubId: CLUB_A_ID,
        username: body.username,
        friendlyName: body.friendlyName,
        notificationEmail: body.notificationEmail,
        languageId: body.languageId,
        roles: [...body.roles],
        enabled: true,
        invitePending: true,
      };
      if (body.personId) created.personId = body.personId;
      if (body.phoneNumber) created.phoneNumber = body.phoneNumber;
      if (body.remarks) created.remarks = body.remarks;
      users.push(created);
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        headers: { Location: `/api/v1/users/${id}` },
        body: JSON.stringify(created),
      });
      return;
    }
    if (method === 'PUT' && idMatch) {
      const idx = users.findIndex((u) => u.id === idMatch[1]);
      const existing = idx === -1 ? undefined : users[idx];
      if (idx === -1 || !existing) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
        return;
      }
      const body = req.postDataJSON() as {
        friendlyName: string;
        notificationEmail: string;
        languageId: string;
        roles: string[];
        personId?: string;
        phoneNumber?: string;
        remarks?: string;
      };
      const updated: MockUserResponse = {
        ...existing,
        friendlyName: body.friendlyName,
        notificationEmail: body.notificationEmail,
        languageId: body.languageId,
        roles: [...body.roles],
      };
      if (body.personId !== undefined) updated.personId = body.personId;
      if (body.phoneNumber !== undefined) updated.phoneNumber = body.phoneNumber;
      if (body.remarks !== undefined) updated.remarks = body.remarks;
      users[idx] = updated;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(updated),
      });
      return;
    }
    if (method === 'DELETE' && idMatch) {
      const id = idMatch[1];
      if (id === SELF_USER_ID) {
        await route.fulfill({
          status: 409,
          contentType: 'application/problem+json',
          body: JSON.stringify({
            type: 'https://alpenflight.ch/problems/user-conflict',
            title: 'Conflict',
            detail: 'A user cannot deactivate themselves.',
          }),
        });
        return;
      }
      const idx = users.findIndex((u) => u.id === id);
      if (idx === -1) {
        await route.fulfill({ status: 404, body: '' });
        return;
      }
      users.splice(idx, 1);
      await route.fulfill({ status: 204, body: '' });
      return;
    }
    if (method === 'POST' && resendMatch) {
      const found = users.find((u) => u.id === resendMatch[1]);
      await route.fulfill({ status: found ? 204 : 404, body: '' });
      return;
    }
    await route.fallback();
  };
}

test('users: lists seeded rows at /users with chips + invite-pending badge', async ({ page }) => {
  const users: MockUserResponse[] = seedUsers.map((u) => ({ ...u, roles: [...u.roles] }));
  await stubReferenceData(page);
  await page.route('**/api/v1/users**', setupUsersBackend(users));

  await page.goto('/users');

  await expect(page.locator('h1')).toHaveText('Users');
  await expect(page.getByTestId('users-table')).toBeVisible();
  await expect(page.getByTestId(`user-row-${SEED_USER_ID}`)).toContainText('Hans Meier');
  await expect(page.getByTestId(`user-row-${SEED_USER_ID}`)).toContainText('h.meier');
  await expect(page.getByTestId(`user-role-${SEED_USER_ID}-PILOT`)).toContainText('Pilot');
  await expect(page.getByTestId(`user-invite-pending-${PENDING_USER_ID}`)).toBeVisible();

  await page.screenshot({ path: 'screenshots/users/01-list-populated.png', fullPage: true });
});

test('users: invite round-trip → row visible in list', async ({ page }) => {
  const users: MockUserResponse[] = seedUsers.map((u) => ({ ...u, roles: [...u.roles] }));
  await stubReferenceData(page);
  await page.route('**/api/v1/users**', setupUsersBackend(users));

  await page.goto('/users');
  await page.getByTestId('users-new-button').click();
  await expect(page).toHaveURL('/users/new');

  await page.getByTestId('username-input').locator('input').fill('m.gandhi');
  await page.getByTestId('friendlyName-input').locator('input').fill('Marie Gandhi');
  await page.getByTestId('notificationEmail-input').locator('input').fill('marie@example.test');
  await page.getByTestId('role-PILOT').check();

  await page.screenshot({ path: 'screenshots/users/02-invite-form.png', fullPage: true });

  await page.getByTestId('user-save-button').click();
  await expect(page).toHaveURL('/users');
  await expect(page.getByText('Marie Gandhi')).toBeVisible();

  await page.screenshot({ path: 'screenshots/users/03-list-after-invite.png', fullPage: true });
});

test('users: person picker uses /persons/lookup exact-match', async ({ page }) => {
  const users: MockUserResponse[] = seedUsers.map((u) => ({ ...u, roles: [...u.roles] }));
  await stubReferenceData(page);
  await page.route('**/api/v1/users**', setupUsersBackend(users));

  await page.goto('/users/new');

  await page.getByTestId('person-picker-email').locator('input').fill(seedLookupPerson.email!);
  const lookupPromise = page.waitForResponse(
    (resp) => resp.url().endsWith('/api/v1/persons/lookup') && resp.request().method() === 'POST',
  );
  await page.getByTestId('person-picker-search').click();
  await lookupPromise;

  await page.getByTestId(`person-picker-match-${seedLookupPerson.id}`).click();
  await expect(page.getByTestId('person-picker-pinned')).toContainText('Bühler, Anna');

  await page.getByTestId('username-input').locator('input').fill('a.bueh');
  await page.getByTestId('friendlyName-input').locator('input').fill('Anna Bühler');
  await page.getByTestId('notificationEmail-input').locator('input').fill('a@example.test');
  await page.getByTestId('role-PILOT').check();

  const postPromise = page.waitForRequest(
    (req) =>
      req.url().endsWith('/api/v1/users') &&
      req.method() === 'POST' &&
      (req.postDataJSON() as { personId?: string }).personId === seedLookupPerson.id,
  );
  await page.getByTestId('user-save-button').click();
  await postPromise;
});

test('users: role round-trip — edit page diff PUTs + reload reflects', async ({ page }) => {
  const users: MockUserResponse[] = seedUsers.map((u) => ({ ...u, roles: [...u.roles] }));
  await stubReferenceData(page);
  await page.route('**/api/v1/users**', setupUsersBackend(users));

  await page.goto('/users');
  await page.getByTestId(`user-row-${SEED_USER_ID}`).click();
  await expect(page).toHaveURL(`/users/${SEED_USER_ID}/edit`);

  await page.getByTestId('role-CLUB_ADMINISTRATOR').check();
  await page.screenshot({ path: 'screenshots/users/04-edit-roles.png', fullPage: true });
  await page.getByTestId('user-save-button').click();

  await expect(page).toHaveURL('/users');
  await page.reload();
  await expect(page.getByTestId(`user-role-${SEED_USER_ID}-CLUB_ADMINISTRATOR`)).toBeVisible();
});

test('users: edit preserves SYSTEM_ADMINISTRATOR (out-of-band role) on save', async ({ page }) => {
  const users: MockUserResponse[] = seedUsers.map((u) => ({ ...u, roles: [...u.roles] }));
  await stubReferenceData(page);
  await page.route('**/api/v1/users**', setupUsersBackend(users));

  await page.goto(`/users/${SYSADMIN_USER_ID}/edit`);
  await expect(page.getByTestId('user-out-of-band-roles-banner')).toContainText(
    'System administrator',
  );

  await page.getByTestId('friendlyName-input').locator('input').fill('Sandra Admin (Updated)');

  const putPromise = page.waitForRequest(
    (req) => req.url().endsWith(`/api/v1/users/${SYSADMIN_USER_ID}`) && req.method() === 'PUT',
  );
  await page.getByTestId('user-save-button').click();
  const putReq = await putPromise;
  const body = putReq.postDataJSON() as { roles: string[] };
  expect(body.roles).toContain('SYSTEM_ADMINISTRATOR');
  expect(body.roles).toContain('PILOT');
});

test('users: deactivate removes the row', async ({ page }) => {
  const users: MockUserResponse[] = seedUsers.map((u) => ({ ...u, roles: [...u.roles] }));
  await stubReferenceData(page);
  await page.route('**/api/v1/users**', setupUsersBackend(users));

  await page.goto(`/users/${SEED_USER_ID}/edit`);

  await page.getByTestId('user-deactivate-button').click();
  await page.screenshot({ path: 'screenshots/users/05-deactivate-confirm.png', fullPage: true });
  await page.getByTestId('af-dialog-confirm').click();

  await expect(page).toHaveURL('/users');
  await expect(page.getByTestId(`user-row-${SEED_USER_ID}`)).toHaveCount(0);
});

test('users: self-deactivate refused inline (409)', async ({ page }, testInfo) => {
  allowConsoleErrors(testInfo, /\b409\b/);
  const users: MockUserResponse[] = seedUsers.map((u) => ({ ...u, roles: [...u.roles] }));
  await stubReferenceData(page);
  await page.route('**/api/v1/users**', setupUsersBackend(users));

  await page.goto(`/users/${SELF_USER_ID}/edit`);

  await page.getByTestId('user-deactivate-button').click();
  await page.getByTestId('af-dialog-confirm').click();

  await expect(page.getByTestId('af-dialog-message')).toContainText(
    'A user cannot deactivate themselves.',
  );
  await expect(page).toHaveURL(`/users/${SELF_USER_ID}/edit`);
});

test('users: invite refuses on duplicate username (409)', async ({ page }, testInfo) => {
  allowConsoleErrors(testInfo, /\b409\b/);
  const users: MockUserResponse[] = seedUsers.map((u) => ({ ...u, roles: [...u.roles] }));
  await stubReferenceData(page);
  await page.route('**/api/v1/users**', setupUsersBackend(users));

  await page.goto('/users/new');

  await page.getByTestId('username-input').locator('input').fill('h.meier');
  await page.getByTestId('friendlyName-input').locator('input').fill('Conflict User');
  await page.getByTestId('notificationEmail-input').locator('input').fill('c@example.test');
  await page.getByTestId('role-PILOT').check();
  await page.getByTestId('user-save-button').click();

  await expect(page.getByTestId('user-form-error')).toContainText('already in use');
  await expect(page).toHaveURL('/users/new');
});

test('users: resend-invite is visible only on invitePending rows + POSTs on click', async ({
  page,
}) => {
  const users: MockUserResponse[] = seedUsers.map((u) => ({ ...u, roles: [...u.roles] }));
  await stubReferenceData(page);
  await page.route('**/api/v1/users**', setupUsersBackend(users));

  await page.goto('/users');

  await expect(page.getByTestId(`user-resend-invite-${PENDING_USER_ID}`)).toBeVisible();
  await expect(page.getByTestId(`user-resend-invite-${SEED_USER_ID}`)).toHaveCount(0);

  const postPromise = page.waitForRequest(
    (req) =>
      req.url().endsWith(`/api/v1/users/${PENDING_USER_ID}/resend-invite`) &&
      req.method() === 'POST',
  );
  await page.getByTestId(`user-resend-invite-${PENDING_USER_ID}`).click();
  await postPromise;
});
