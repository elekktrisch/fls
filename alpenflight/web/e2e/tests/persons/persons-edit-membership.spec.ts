import { type Page, type Route } from '@playwright/test';
import { expect, test } from '../_helpers/console-guard';

import { enterViaNav } from '../_helpers/nav';


const CLUB_A_ID = 'clb-019e30c3-2c00-7001-8000-000000000001';
const PERSON_ID = 'pn-019e30c3-2c00-7001-8000-000000000a01';
const STATE_ACTIVE_ID = '019e2e15-2c00-7c01-8000-000000000c01';
const STATE_HONORARY_ID = '019e2e15-2c00-7c02-8000-000000000c02';

interface MockPersonClub {
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

type MembershipPut = Omit<MockPersonClub, 'id' | 'clubId' | 'memberStateName'>;

interface MockPerson {
  id: string;
  firstname: string;
  lastname: string;
  emailPrivate?: string;
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
  memberships: MockPersonClub[];
  inOtherClubsCount: number;
}

const mockMemberStates = [
  { id: STATE_ACTIVE_ID, name: 'Active' },
  { id: STATE_HONORARY_ID, name: 'Honorary' },
];

const seedPerson: MockPerson = {
  id: PERSON_ID,
  firstname: 'Anna',
  lastname: 'Bühler',
  emailPrivate: 'anna.buehler@example.test',
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
      memberStateId: STATE_ACTIVE_ID,
      memberStateName: 'Active',
      isMotorPilot: false,
      isTowPilot: false,
      isGliderInstructor: false,
      isGliderPilot: true,
      isGliderTrainee: false,
      isPassenger: false,
      isWinchOperator: true,
      isMotorInstructor: false,
      receiveFlightReports: true,
      receiveAircraftReservationNotifications: false,
      receivePlanningDayRoleReminder: false,
      isActive: true,
    },
  ],
  inOtherClubsCount: 0,
};

function toListItem(p: MockPerson): Record<string, unknown> {
  const pc = p.memberships[0];
  return {
    id: p.id,
    firstname: p.firstname,
    lastname: p.lastname,
    ...(pc?.memberNumber ? { memberNumber: pc.memberNumber } : {}),
    ...(pc?.memberStateId ? { memberStateId: pc.memberStateId } : {}),
    ...(pc?.memberStateName ? { memberStateName: pc.memberStateName } : {}),
    isActive: pc?.isActive ?? false,
    isMotorPilot: pc?.isMotorPilot ?? false,
    isTowPilot: pc?.isTowPilot ?? false,
    isGliderInstructor: pc?.isGliderInstructor ?? false,
    isGliderPilot: pc?.isGliderPilot ?? false,
    isGliderTrainee: pc?.isGliderTrainee ?? false,
    isWinchOperator: pc?.isWinchOperator ?? false,
    isMotorInstructor: pc?.isMotorInstructor ?? false,
  };
}

async function stubReferenceData(page: Page): Promise<void> {
  for (const path of [
    'countries',
    'club-states',
    'location-types',
    'aircraft-types',
    'aircraft-states',
    'counter-unit-types',
    'clubs',
    'locations',
    'aircraft',
    'flight-types',
    'flights',
  ]) {
    await page.route(`**/api/v1/${path}**`, (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
    );
  }
  await page.route('**/api/v1/club/member-states**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockMemberStates),
    }),
  );
  await page.route('**/api/v1/me/system-dashboard**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ totalClubs: 1, totalUsers: 1, totalFlights: 0 }),
    }),
  );
  await page.route('**/api/v1/me', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'mock-sysadmin',
        personId: null,
        clubId: CLUB_A_ID,
        roles: ['SYSTEM_ADMINISTRATOR', 'CLUB_ADMINISTRATOR'],
        firstName: 'Mock',
        lastName: 'Sysadmin',
        email: 'mock@local',
        username: 'mock-sysadmin',
      }),
    }),
  );
}

interface CapturedPuts {
  person: Record<string, unknown>[];
  membership: MembershipPut[];
}

function setupPersonsBackend(persons: MockPerson[], captured: CapturedPuts) {
  return async (route: Route) => {
    const req = route.request();
    const path = new URL(req.url()).pathname;
    const method = req.method();
    const idMatch = path.match(/^\/api\/v1\/persons\/(pn-[^/]+)$/);
    const clubsCurrentMatch = path.match(/^\/api\/v1\/persons\/(pn-[^/]+)\/clubs\/current$/);

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
    if (method === 'PUT' && idMatch) {
      const person = persons.find((p) => p.id === idMatch[1]);
      if (!person) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
        return;
      }
      const body = req.postDataJSON() as Record<string, unknown>;
      captured.person.push(body);
      person.firstname = body['firstname'] as string;
      person.lastname = body['lastname'] as string;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(person),
      });
      return;
    }
    if (method === 'PUT' && clubsCurrentMatch) {
      const person = persons.find((p) => p.id === clubsCurrentMatch[1]);
      const pc = person?.memberships[0];
      if (!person || !pc) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
        return;
      }
      const body = req.postDataJSON() as MembershipPut;
      captured.membership.push(body);
      person.memberships[0] = {
        id: pc.id,
        clubId: pc.clubId,
        ...(body.memberNumber ? { memberNumber: body.memberNumber } : {}),
        ...(body.memberStateId ? { memberStateId: body.memberStateId } : {}),
        ...(() => {
          const name = body.memberStateId
            ? mockMemberStates.find((m) => m.id === body.memberStateId)?.name
            : undefined;
          return name ? { memberStateName: name } : {};
        })(),
        isMotorPilot: body.isMotorPilot ?? false,
        isTowPilot: body.isTowPilot ?? false,
        isGliderInstructor: body.isGliderInstructor ?? false,
        isGliderPilot: body.isGliderPilot ?? false,
        isGliderTrainee: body.isGliderTrainee ?? false,
        isPassenger: body.isPassenger ?? false,
        isWinchOperator: body.isWinchOperator ?? false,
        isMotorInstructor: body.isMotorInstructor ?? false,
        receiveFlightReports: body.receiveFlightReports ?? false,
        receiveAircraftReservationNotifications:
          body.receiveAircraftReservationNotifications ?? false,
        receivePlanningDayRoleReminder: body.receivePlanningDayRoleReminder ?? false,
        isActive: body.isActive ?? false,
      };
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(person),
      });
      return;
    }
    await route.fallback();
  };
}

test('persons edit: memberNumber + role toggle + memberState round-trip through Save → re-open (J-26 T-04 data-loss fix)', async ({
  page,
}) => {
  const persons: MockPerson[] = [
    { ...seedPerson, memberships: seedPerson.memberships.map((m) => ({ ...m })) },
  ];
  const captured: CapturedPuts = { person: [], membership: [] };
  await stubReferenceData(page);
  await page.route('**/api/v1/persons**', setupPersonsBackend(persons, captured));

  await page.goto('/start?lang=de');
  await enterViaNav(page, '/persons');
  await expect(page).toHaveURL(/\/persons$/);
  await page.getByTestId(`person-row-${PERSON_ID}`).click();
  await expect(page).toHaveURL(/\/persons\/pn-.+\/edit$/);

  const memberNumber = page.getByTestId('member-number-input').locator('input');
  await expect(memberNumber).toHaveValue('M-001');
  await expect(page.getByTestId('role-motor-pilot')).not.toBeChecked();
  await expect(page.getByTestId('role-glider-pilot')).toBeChecked();

  await memberNumber.fill('M-777');
  await page.getByTestId('role-motor-pilot').check();
  await page.getByTestId('member-state-select').click();
  await page.getByTestId(`af-select-option-${STATE_HONORARY_ID}`).click();

  await page.getByTestId('person-save-button').click();
  await expect(page).toHaveURL(/\/persons$/);

  expect(captured.person).toHaveLength(1);
  expect(captured.membership).toHaveLength(1);
  const membershipPut = captured.membership[0];
  if (!membershipPut) throw new Error('membership PUT payload not captured');
  expect(membershipPut.memberNumber).toBe('M-777');
  expect(membershipPut.memberStateId).toBe(STATE_HONORARY_ID);
  expect(membershipPut.isMotorPilot).toBe(true);
  expect(membershipPut.isGliderPilot).toBe(true);
  expect(membershipPut.isWinchOperator).toBe(true);
  expect(membershipPut.receiveFlightReports).toBe(true);
  expect(membershipPut.isActive).toBe(true);

  await page.getByTestId(`person-row-${PERSON_ID}`).click();
  await expect(page).toHaveURL(/\/persons\/pn-.+\/edit$/);
  await expect(page.getByTestId('member-number-input').locator('input')).toHaveValue('M-777');
  await expect(page.getByTestId('role-motor-pilot')).toBeChecked();
  await expect(page.getByTestId('role-glider-pilot')).toBeChecked();
  await expect(page.getByTestId('member-state-select')).toContainText('Honorary');

  await page.screenshot({
    path: 'screenshots/persons/05-membership-roundtrip.png',
    fullPage: true,
  });
});
