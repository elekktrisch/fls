import { expect, test, type Route } from '@playwright/test';

/**
 * S-051 Persons add-flow parity port. Maps the legacy
 * `e2e/tests/masterdata/persons-add-modal.spec.ts` semantics onto the new
 * stack — observable behavior only, NOT legacy URL shape or response
 * envelope (ADR 0022).
 *
 * Legacy oracle: open modal from User-edit page; fill firstname / lastname
 * / email; submit; assert the new person appears in /masterdata/persons.
 *
 * New-stack mapping:
 *   - "Open add-person modal from User-edit" → "Navigate to /persons/new"
 *     (route owns the form; the modal flavor is a follow-up consumed by
 *     User-create + Flight-crew picker per the refinement).
 *   - `POST /api/v1/persons/page/{skip}/{take}` filter envelope → `GET
 *     /api/v1/persons?…` (per ADR 0005).
 *   - `#Firstname` / `#Lastname` / `#Email` id selectors → `data-testid`
 *     handles on the form atoms (greenfield gets the testids the legacy
 *     lacks).
 *
 * Stack: booted under the `mock-auth` Angular configuration; principal is
 * a mocked SYSTEM_ADMINISTRATOR so the mutation affordances render. All
 * `/api/v1/*` calls are intercepted via `page.route` — no live backend.
 */

interface MockMemberState {
  id: string;
  name: string;
}

/**
 * Build a single-key object only when `value` is defined — so an optional
 * (`?:`) target property is omitted rather than set to `undefined`. Lets the
 * mock backend copy optional request fields through under the e2e tsconfig's
 * `exactOptionalPropertyTypes`, which rejects an explicit `key: undefined`.
 */
function optional<K extends string, V>(
  key: K,
  value: V | undefined,
): Record<K, V> | Record<string, never> {
  return value === undefined ? {} : ({ [key]: value } as Record<K, V>);
}

interface MockPersonClubResponse {
  id: string;
  clubId: string;
  memberNumber?: string;
  memberStateId?: string;
  memberStateName?: string;
  isMotorPilot: boolean;
  isTowPilot: boolean;
  isGliderInstructor: boolean;
  isGliderPilot: boolean;
  isGliderTrainee: boolean;
  isPassenger: boolean;
  isWinchOperator: boolean;
  isMotorInstructor: boolean;
  receiveFlightReports: boolean;
  receiveAircraftReservationNotifications: boolean;
  receivePlanningDayRoleReminder: boolean;
  isActive: boolean;
}

interface MockPerson {
  id: string;
  firstname: string;
  lastname: string;
  midname?: string;
  companyName?: string;
  emailPrivate?: string;
  emailBusiness?: string;
  mobilePhone?: string;
  city?: string;
  zip?: string;
  preferMailToBusinessMail: boolean;
  receiveOwnedAircraftStatisticReports: boolean;
  enableAddress: boolean;
  hasGliderPilotLicence: boolean;
  hasMotorPilotLicence: boolean;
  hasTowPilotLicence: boolean;
  hasGliderInstructorLicence: boolean;
  hasGliderTraineeLicence: boolean;
  hasGliderPaxLicence: boolean;
  hasTmgLicence: boolean;
  hasWinchOperatorLicence: boolean;
  hasMotorInstructorLicence: boolean;
  hasPartMLicence: boolean;
  hasGliderTowingStartPermission: boolean;
  hasGliderSelfStartPermission: boolean;
  hasGliderWinchStartPermission: boolean;
  memberships: MockPersonClubResponse[];
  inOtherClubsCount: number;
}

interface MockPersonListItem {
  id: string;
  firstname: string;
  lastname: string;
  email?: string;
  mobilePhone?: string;
  city?: string;
  zip?: string;
  memberNumber?: string;
  memberStateId?: string;
  memberStateName?: string;
  isActive: boolean;
  isMotorPilot: boolean;
  isTowPilot: boolean;
  isGliderInstructor: boolean;
  isGliderPilot: boolean;
  isGliderTrainee: boolean;
  isWinchOperator: boolean;
  isMotorInstructor: boolean;
}

const CLUB_A_ID = 'clb-019e30c3-2c00-7001-8000-000000000001';

const mockMemberStates: MockMemberState[] = [
  { id: '019e2e15-2c00-7c01-8000-000000000c01', name: 'Active' },
  { id: '019e2e15-2c00-7c02-8000-000000000c02', name: 'Honorary' },
];

const seedPerson: MockPerson = {
  id: 'pn-019e30c3-2c00-7001-8000-000000000a01',
  firstname: 'Anna',
  lastname: 'Bühler',
  emailPrivate: 'anna.buehler@example.test',
  mobilePhone: '+41 79 000 00 01',
  city: 'Zürich',
  zip: '8001',
  preferMailToBusinessMail: false,
  receiveOwnedAircraftStatisticReports: false,
  enableAddress: false,
  hasGliderPilotLicence: true,
  hasMotorPilotLicence: false,
  hasTowPilotLicence: false,
  hasGliderInstructorLicence: false,
  hasGliderTraineeLicence: false,
  hasGliderPaxLicence: false,
  hasTmgLicence: false,
  hasWinchOperatorLicence: false,
  hasMotorInstructorLicence: false,
  hasPartMLicence: false,
  hasGliderTowingStartPermission: false,
  hasGliderSelfStartPermission: false,
  hasGliderWinchStartPermission: false,
  memberships: [
    {
      id: '019e2e15-2c00-7d01-8000-000000000d01',
      clubId: CLUB_A_ID,
      memberNumber: 'M-001',
      memberStateId: '019e2e15-2c00-7c01-8000-000000000c01',
      memberStateName: 'Active',
      isMotorPilot: false,
      isTowPilot: false,
      isGliderInstructor: false,
      isGliderPilot: true,
      isGliderTrainee: false,
      isPassenger: false,
      isWinchOperator: false,
      isMotorInstructor: false,
      receiveFlightReports: false,
      receiveAircraftReservationNotifications: false,
      receivePlanningDayRoleReminder: false,
      isActive: true,
    },
  ],
  inOtherClubsCount: 0,
};

function toListItem(p: MockPerson): MockPersonListItem {
  const pc = p.memberships[0];
  const item: MockPersonListItem = {
    id: p.id,
    firstname: p.firstname,
    lastname: p.lastname,
    isActive: pc?.isActive ?? false,
    isMotorPilot: pc?.isMotorPilot ?? false,
    isTowPilot: pc?.isTowPilot ?? false,
    isGliderInstructor: pc?.isGliderInstructor ?? false,
    isGliderPilot: pc?.isGliderPilot ?? false,
    isGliderTrainee: pc?.isGliderTrainee ?? false,
    isWinchOperator: pc?.isWinchOperator ?? false,
    isMotorInstructor: pc?.isMotorInstructor ?? false,
  };
  if (p.emailPrivate) item.email = p.emailPrivate;
  if (p.mobilePhone) item.mobilePhone = p.mobilePhone;
  if (p.city) item.city = p.city;
  if (p.zip) item.zip = p.zip;
  if (pc?.memberNumber) item.memberNumber = pc.memberNumber;
  if (pc?.memberStateId) item.memberStateId = pc.memberStateId;
  if (pc?.memberStateName) item.memberStateName = pc.memberStateName;
  return item;
}

async function stubReferenceData(page: import('@playwright/test').Page): Promise<void> {
  // The bootstrap prefetch pulls these system-global lookups; we route to
  // empty arrays so the SessionStore prefetch resolves cleanly. Persons
  // doesn't consume them on the rendered surface, but the prefetch fires
  // on auth.
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
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockMemberStates),
    }),
  );
}

function setupPersonsBackend(persons: MockPerson[]) {
  let nextId = 1000;
  return async (route: Route) => {
    const req = route.request();
    const url = new URL(req.url());
    const method = req.method();
    const path = url.pathname;
    const idMatch = path.match(/^\/api\/v1\/persons\/(pn-[^/]+)$/);

    if (method === 'GET' && path === '/api/v1/persons') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(persons.map(toListItem)),
      });
      return;
    }
    if (method === 'GET' && idMatch) {
      const found = persons.find((p) => p.id === idMatch[1]);
      await route.fulfill({
        status: found ? 200 : 404,
        contentType: 'application/json',
        body: JSON.stringify(found ?? {}),
      });
      return;
    }
    if (method === 'POST' && path === '/api/v1/persons') {
      const body = req.postDataJSON() as {
        firstname: string;
        lastname: string;
        emailPrivate?: string;
        mobilePhone?: string;
        city?: string;
        preferMailToBusinessMail: boolean;
        receiveOwnedAircraftStatisticReports: boolean;
        enableAddress: boolean;
        initialClubMembership?: {
          memberNumber?: string;
          memberStateId?: string;
          isMotorPilot: boolean;
          isTowPilot: boolean;
          isGliderInstructor: boolean;
          isGliderPilot: boolean;
          isGliderTrainee: boolean;
          isPassenger: boolean;
          isWinchOperator: boolean;
          isMotorInstructor: boolean;
          receiveFlightReports: boolean;
          receiveAircraftReservationNotifications: boolean;
          receivePlanningDayRoleReminder: boolean;
          isActive: boolean;
        };
      };
      const id = `pn-019e30c3-2c00-7001-8000-${String(nextId++).padStart(12, '0')}`;
      const memberships: MockPersonClubResponse[] = body.initialClubMembership
        ? [
            {
              id: `019e30c3-2c00-7002-8000-${String(nextId).padStart(12, '0')}`,
              clubId: CLUB_A_ID,
              // exactOptionalPropertyTypes: optional fields are spread in only
              // when present (an explicit `: undefined` is rejected for `?:` props).
              ...optional('memberNumber', body.initialClubMembership.memberNumber),
              ...optional('memberStateId', body.initialClubMembership.memberStateId),
              ...optional(
                'memberStateName',
                mockMemberStates.find((m) => m.id === body.initialClubMembership?.memberStateId)
                  ?.name,
              ),
              isMotorPilot: body.initialClubMembership.isMotorPilot,
              isTowPilot: body.initialClubMembership.isTowPilot,
              isGliderInstructor: body.initialClubMembership.isGliderInstructor,
              isGliderPilot: body.initialClubMembership.isGliderPilot,
              isGliderTrainee: body.initialClubMembership.isGliderTrainee,
              isPassenger: body.initialClubMembership.isPassenger,
              isWinchOperator: body.initialClubMembership.isWinchOperator,
              isMotorInstructor: body.initialClubMembership.isMotorInstructor,
              receiveFlightReports: body.initialClubMembership.receiveFlightReports,
              receiveAircraftReservationNotifications:
                body.initialClubMembership.receiveAircraftReservationNotifications,
              receivePlanningDayRoleReminder:
                body.initialClubMembership.receivePlanningDayRoleReminder,
              isActive: body.initialClubMembership.isActive,
            },
          ]
        : [];
      const created: MockPerson = {
        id,
        firstname: body.firstname,
        lastname: body.lastname,
        ...optional('emailPrivate', body.emailPrivate),
        ...optional('mobilePhone', body.mobilePhone),
        ...optional('city', body.city),
        preferMailToBusinessMail: body.preferMailToBusinessMail,
        receiveOwnedAircraftStatisticReports: body.receiveOwnedAircraftStatisticReports,
        enableAddress: body.enableAddress,
        hasGliderPilotLicence: false,
        hasMotorPilotLicence: false,
        hasTowPilotLicence: false,
        hasGliderInstructorLicence: false,
        hasGliderTraineeLicence: false,
        hasGliderPaxLicence: false,
        hasTmgLicence: false,
        hasWinchOperatorLicence: false,
        hasMotorInstructorLicence: false,
        hasPartMLicence: false,
        hasGliderTowingStartPermission: false,
        hasGliderSelfStartPermission: false,
        hasGliderWinchStartPermission: false,
        memberships,
        inOtherClubsCount: 0,
      };
      persons.push(created);
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        headers: { Location: `/api/v1/persons/${created.id}` },
        body: JSON.stringify(created),
      });
      return;
    }
    if (method === 'PUT' && idMatch) {
      const idx = persons.findIndex((p) => p.id === idMatch[1]);
      if (idx === -1) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
        return;
      }
      const body = req.postDataJSON() as Partial<MockPerson>;
      const existing = persons[idx];
      if (!existing) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
        return;
      }
      persons[idx] = { ...existing, ...body, memberships: existing.memberships };
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(persons[idx]),
      });
      return;
    }
    if (method === 'DELETE' && idMatch) {
      const idx = persons.findIndex((p) => p.id === idMatch[1]);
      if (idx === -1) {
        await route.fulfill({ status: 404, body: '' });
        return;
      }
      persons.splice(idx, 1);
      await route.fulfill({ status: 204, body: '' });
      return;
    }
    await route.fallback();
  };
}

test('persons: lists the seeded row at /persons', async ({ page }) => {
  const persons: MockPerson[] = [{ ...seedPerson, memberships: [...seedPerson.memberships] }];
  await stubReferenceData(page);
  await page.route('**/api/v1/persons**', setupPersonsBackend(persons));

  await page.goto('/persons');

  await expect(page.locator('h1')).toHaveText('Persons');
  await expect(page.getByTestId('persons-table')).toBeVisible();
  await expect(page.getByTestId(`person-row-${seedPerson.id}`)).toBeVisible();
  await expect(page.getByTestId(`person-row-${seedPerson.id}`)).toContainText('Bühler, Anna');
});

test('persons: creating a new person via /persons/new appears in the list', async ({ page }) => {
  const persons: MockPerson[] = [{ ...seedPerson, memberships: [...seedPerson.memberships] }];
  await stubReferenceData(page);
  await page.route('**/api/v1/persons**', setupPersonsBackend(persons));

  await page.goto('/persons');
  await page.getByTestId('persons-new-button').click();
  await expect(page).toHaveURL('/persons/new');

  // Fill the parity-spec required fields (firstname / lastname / email)
  // + the member-state picker (the new tenant-scoped reference dropdown).
  await page.getByTestId('firstname-input').locator('input').fill('Marc');
  await page.getByTestId('lastname-input').locator('input').fill('Aurel');
  await page.getByTestId('email-input').locator('input').fill('marc.aurel@example.test');
  await page.getByTestId('role-glider-pilot').check();

  await page.getByTestId('person-save-button').click();
  await expect(page).toHaveURL('/persons');

  // New row visible in the list (lastname, firstname per the formatter).
  await expect(page.getByText('Aurel, Marc')).toBeVisible();
});

test('persons: edit round-trip persists name change', async ({ page }) => {
  const persons: MockPerson[] = [{ ...seedPerson, memberships: [...seedPerson.memberships] }];
  await stubReferenceData(page);
  await page.route('**/api/v1/persons**', setupPersonsBackend(persons));

  await page.goto('/persons');
  await page.getByTestId(`person-row-${seedPerson.id}`).click();
  await expect(page).toHaveURL(/\/persons\/pn-.+\/edit$/);

  await page.getByTestId('firstname-input').locator('input').fill('Annette');
  await page.getByTestId('person-save-button').click();

  await expect(page).toHaveURL('/persons');
  await expect(page.getByTestId(`person-row-${seedPerson.id}`)).toContainText('Bühler, Annette');
});
