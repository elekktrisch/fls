import { expect, test, type Route } from '@playwright/test';

/**
 * S-168 Users admin spec. Mock-backend (page.route) — live-Keycloak SPA
 * e2e is S-109/S-110 territory. The mock-auth principal carries both
 * SYSTEM_ADMINISTRATOR and CLUB_ADMINISTRATOR; the surface itself is
 * gated on CLUB_ADMINISTRATOR via `clubAdminGuard`, so direct
 * `page.goto('/users')` is the entry point in this spec (nav-bar
 * visibility for CLUB_ADMIN-only is exercised elsewhere).
 *
 * Coverage:
 *   - List + seeded-row visibility, role chips, invite-pending badge.
 *   - Invite round-trip: form → POST `/api/v1/users` → list row appears.
 *   - Role round-trip: edit page → diff PUT → reload reflects update.
 *   - Deactivate happy-path + 409 self-delete refusal inline.
 *   - Resend invite affordance on invitePending rows.
 */

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

const CLUB_A_ID = '019e30c3-2c00-7001-8000-000000000001';
const SELF_USER_ID = 'usr-019e30c3-2c00-7100-8000-000000000001';
const SEED_USER_ID = 'usr-019e30c3-2c00-7100-8000-000000000002';
const PENDING_USER_ID = 'usr-019e30c3-2c00-7100-8000-000000000003';

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
];

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
    if (u.pathname === '/api/v1/persons' || u.pathname === '/api/v1/persons/lookup') {
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
      // Mock the backend's self-delete + last-admin refusals.
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
  await expect(page.getByTestId(`user-row-${PENDING_USER_ID}`)).toContainText('Invite pending');
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

  await page.getByTestId('user-save-button').click();
  await expect(page).toHaveURL('/users');
  await expect(page.getByText('Marie Gandhi')).toBeVisible();
});

test('users: role round-trip — edit page diff PUTs + reload reflects', async ({ page }) => {
  const users: MockUserResponse[] = seedUsers.map((u) => ({ ...u, roles: [...u.roles] }));
  await stubReferenceData(page);
  await page.route('**/api/v1/users**', setupUsersBackend(users));

  await page.goto('/users');
  await page.getByTestId(`user-row-${SEED_USER_ID}`).getByRole('link').click();
  await expect(page).toHaveURL(`/users/${SEED_USER_ID}/edit`);

  // Grant CLUB_ADMINISTRATOR on top of existing PILOT.
  await page.getByTestId('role-CLUB_ADMINISTRATOR').check();
  await page.getByTestId('user-save-button').click();

  await expect(page).toHaveURL('/users');
  await expect(page.getByTestId(`user-row-${SEED_USER_ID}`)).toContainText('CLUB_ADMINISTRATOR');
});

test('users: deactivate removes the row', async ({ page }) => {
  const users: MockUserResponse[] = seedUsers.map((u) => ({ ...u, roles: [...u.roles] }));
  await stubReferenceData(page);
  await page.route('**/api/v1/users**', setupUsersBackend(users));

  await page.goto(`/users/${SEED_USER_ID}/edit`);

  await page.getByTestId('user-deactivate-button').click();
  await page.getByTestId('af-dialog-confirm').click();

  await expect(page).toHaveURL('/users');
  await expect(page.getByTestId(`user-row-${SEED_USER_ID}`)).not.toBeVisible();
});

test('users: self-deactivate refused inline (409)', async ({ page }) => {
  const users: MockUserResponse[] = seedUsers.map((u) => ({ ...u, roles: [...u.roles] }));
  await stubReferenceData(page);
  await page.route('**/api/v1/users**', setupUsersBackend(users));

  await page.goto(`/users/${SELF_USER_ID}/edit`);

  await page.getByTestId('user-deactivate-button').click();
  await page.getByTestId('af-dialog-confirm').click();

  // Dialog stays open with the backend's detail surfaced inline.
  await expect(page.getByTestId('af-dialog-message')).toContainText(
    'A user cannot deactivate themselves.',
  );
  await expect(page).toHaveURL(`/users/${SELF_USER_ID}/edit`);
});

test('users: resend-invite affordance is visible only on invitePending rows', async ({ page }) => {
  const users: MockUserResponse[] = seedUsers.map((u) => ({ ...u, roles: [...u.roles] }));
  await stubReferenceData(page);
  await page.route('**/api/v1/users**', setupUsersBackend(users));

  await page.goto('/users');

  await expect(
    page.getByTestId(`user-row-${PENDING_USER_ID}`).getByTestId('user-resend-invite-button'),
  ).toBeVisible();
  await expect(
    page.getByTestId(`user-row-${SEED_USER_ID}`).getByTestId('user-resend-invite-button'),
  ).toHaveCount(0);
});
