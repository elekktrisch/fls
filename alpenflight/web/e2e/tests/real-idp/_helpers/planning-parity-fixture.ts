import { randomUUID } from 'node:crypto';

import { type APIRequestContext, type Browser, type Page, expect } from '@playwright/test';

import { assignRealmRole, createUserWithAttributes, deleteUser } from './keycloak-admin';
import { fillKcLogin } from './kc-form';
import {
  E2E_EMAIL_PREFIX,
  E2E_EMAIL_SUFFIX,
  E2E_CANNED_PASSWORD,
  type TestUser,
} from './test-user';

const SEED_CLUB_ID = '019e30c3-2c00-7001-8000-000000000001';

const CH_COUNTRY_ID = '019e2e15-2c00-74be-8000-0000000004be';
const GRASS_RUNWAY_LOCATION_TYPE_ID = '019e2e15-2c00-72c9-8000-0000000032c9';
const GLIDER_AIRCRAFT_TYPE_ID = '019e2e15-2c00-7af9-8000-000000002af9';

function runId(): string {
  const id = process.env['E2E_RUN_ID'];
  if (!id) {
    throw new Error('E2E_RUN_ID not set — real-idp-setup must run before the planning fixture');
  }
  return id;
}

// ext: must match V34__dev_planning_seed.sql
export const SEED_CLUB_NOTIFICATION_ADDRESS = 'flugbetrieb@seed-club-1.example';

let shortTagSeq = 0;

function shortTag(): string {
  const seq = (shortTagSeq++).toString(36);
  return `${runId().slice(0, 3)}${Date.now().toString(36).slice(-4)}${seq}`.toUpperCase();
}

async function postJson(
  api: APIRequestContext,
  bearer: string,
  path: string,
  data: unknown,
): Promise<Record<string, unknown>> {
  const res = await api.post(path, {
    headers: { authorization: bearer, 'content-type': 'application/json' },
    data: data as object,
  });
  if (res.status() !== 201 && res.status() !== 200) {
    throw new Error(`POST ${path} failed (${res.status()}): ${await res.text()}`);
  }
  return (await res.json()) as Record<string, unknown>;
}

export interface PlanningMasterdata {
  locationId: string;
  locationName: string;
  instructorId: string;
  instructorName: string;
  instructorEmail: string;
  towPilotId: string;
  towPilotName: string;
  towPilotEmail: string;
  flightOpId: string;
  flightOpName: string;
  flightOpEmail: string;
  aircraftId: string;
  aircraftImmat: string;
}

async function seedCrewPerson(
  api: APIRequestContext,
  bearer: string,
  firstname: string,
  lastname: string,
  email: string,
  flags: { isGliderInstructor?: boolean; isTowPilot?: boolean },
): Promise<{ id: string; name: string; email: string }> {
  const person = await postJson(api, bearer, '/api/v1/persons', {
    firstname,
    lastname,
    emailPrivate: email,
    preferMailToBusinessMail: false,
    receiveOwnedAircraftStatisticReports: false,
    enableAddress: false,
    initialClubMembership: {
      isMotorPilot: false,
      isTowPilot: flags.isTowPilot ?? false,
      isGliderInstructor: flags.isGliderInstructor ?? false,
      isGliderPilot: true,
      isGliderTrainee: false,
      isPassenger: false,
      isWinchOperator: false,
      isMotorInstructor: false,
      receiveFlightReports: false,
      receiveAircraftReservationNotifications: false,
      receivePlanningDayRoleReminder: true,
      isActive: true,
    },
  });
  return { id: String(person['id']), name: `${firstname} ${lastname}`, email };
}

export async function seedFreshPlanningLocation(
  api: APIRequestContext,
  bearer: string,
  label: string,
): Promise<{ locationId: string; locationName: string }> {
  const tag = shortTag();
  const locationName = `J6 ${label} ${tag}`;
  const location = await postJson(api, bearer, '/api/v1/locations', {
    locationName,
    locationShortName: 'J6PF',
    countryId: CH_COUNTRY_ID,
    locationTypeId: GRASS_RUNWAY_LOCATION_TYPE_ID,
    isInboundRouteRequired: false,
    isOutboundRouteRequired: false,
    isFastEntryRecord: false,
  });
  return { locationId: String(location['id']), locationName };
}

export async function seedPlanningMasterdata(
  api: APIRequestContext,
  bearer: string,
): Promise<PlanningMasterdata> {
  const tag = shortTag();

  const location = await postJson(api, bearer, '/api/v1/locations', {
    locationName: `J6 Planning Field ${tag}`,
    locationShortName: 'J6PF',
    countryId: CH_COUNTRY_ID,
    locationTypeId: GRASS_RUNWAY_LOCATION_TYPE_ID,
    isInboundRouteRequired: false,
    isOutboundRouteRequired: false,
    isFastEntryRecord: false,
  });

  const lc = tag.toLowerCase();
  const instructor = await seedCrewPerson(
    api,
    bearer,
    'Ingrid',
    `Fluglehrer ${tag}`,
    `ingrid.${lc}@crew.example`,
    { isGliderInstructor: true },
  );
  const towPilot = await seedCrewPerson(
    api,
    bearer,
    'Theo',
    `Schlepppilot ${tag}`,
    `theo.${lc}@crew.example`,
    { isTowPilot: true },
  );
  const flightOp = await seedCrewPerson(
    api,
    bearer,
    'Frieda',
    `Flugleiter ${tag}`,
    `frieda.${lc}@crew.example`,
    {},
  );

  const aircraftImmat = `HB-P${tag.slice(-3)}`;
  const aircraft = await postJson(api, bearer, '/api/v1/aircraft', {
    aircraftTypeId: GLIDER_AIRCRAFT_TYPE_ID,
    immatriculation: aircraftImmat,
    manufacturerName: 'Schleicher',
    aircraftModel: 'ASK 21',
    nrOfSeats: 2,
    isTowingOrWinchRequired: false,
    isTowingStartAllowed: false,
    isWinchStartAllowed: false,
    isTowingAircraft: false,
  });

  return {
    locationId: String(location['id']),
    locationName: `J6 Planning Field ${tag}`,
    instructorId: instructor.id,
    instructorName: instructor.name,
    instructorEmail: instructor.email,
    towPilotId: towPilot.id,
    towPilotName: towPilot.name,
    towPilotEmail: towPilot.email,
    flightOpId: flightOp.id,
    flightOpName: flightOp.name,
    flightOpEmail: flightOp.email,
    aircraftId: String(aircraft['id']),
    aircraftImmat,
  };
}

const MIDDAY_UTC_WINDOW_START = 'T10:00:00Z';
const MIDDAY_UTC_WINDOW_END = 'T11:00:00Z';

export async function seedReservationOnPlanningDay(
  api: APIRequestContext,
  bearer: string,
  args: {
    planningDate: string;
    locationId: string;
    aircraftId: string;
    pilotPersonId: string;
    reservationTypeId: string;
  },
): Promise<string> {
  const res = await api.post('/api/v1/aircraft-reservations', {
    headers: { authorization: bearer, 'content-type': 'application/json' },
    data: {
      aircraftId: args.aircraftId,
      pilotPersonId: args.pilotPersonId,
      locationId: args.locationId,
      reservationTypeId: args.reservationTypeId,
      start: `${args.planningDate}${MIDDAY_UTC_WINDOW_START}`,
      end: `${args.planningDate}${MIDDAY_UTC_WINDOW_END}`,
      isAllDay: false,
      remarks: 'J-6 inline-panel parity reservation',
    },
  });
  if (res.status() !== 201) {
    throw new Error(
      `seedReservationOnPlanningDay: reservation create must 201 — got ${res.status()}: ${await res.text()}`,
    );
  }
  const location = res.headers()['location'];
  if (!location) {
    throw new Error('seedReservationOnPlanningDay: create must return a 201 Location header');
  }
  return new URL(location, 'http://localhost').pathname.split('/').pop() ?? '';
}

const PILOT_ROLE = 'PILOT';

export interface SeedClubPilot {
  user: TestUser;
  kcUserId: string;
  dispose: () => Promise<void>;
}

export async function provisionSeedClubPilot(): Promise<SeedClubPilot> {
  const id = process.env['E2E_RUN_ID'];
  if (!id) {
    throw new Error('E2E_RUN_ID not set — real-idp-setup must run before provisioning the pilot');
  }
  const nonce = randomUUID().replace(/-/g, '').slice(0, 8);
  const user: TestUser = {
    email: `${E2E_EMAIL_PREFIX}${id}-j6-pilot-${nonce}${E2E_EMAIL_SUFFIX}`,
    password: E2E_CANNED_PASSWORD,
    firstName: 'E2e',
    lastName: 'Pilot',
  };
  const kcUserId = await createUserWithAttributes(user, { clubId: [SEED_CLUB_ID] });
  await assignRealmRole(kcUserId, PILOT_ROLE);
  return {
    user,
    kcUserId,
    dispose: async () => {
      await deleteUser(kcUserId, user.email);
    },
  };
}

export async function captureSeedClubPilotBearer(
  browser: Browser,
  baseURL: string,
  pilot: SeedClubPilot,
): Promise<string> {
  const context = await browser.newContext({ baseURL });
  const page = await context.newPage();
  try {
    const bearerPromise = page.waitForRequest((req) => {
      const auth = req.headers()['authorization'];
      return req.url().includes('/api/v1/') && typeof auth === 'string' && /^Bearer /i.test(auth);
    });
    await loginAsSeedClubPilot(page, pilot);
    await page.goto('/planning');
    const req = await bearerPromise;
    return req.headers()['authorization']!;
  } finally {
    await context.close();
  }
}

export async function loginAsSeedClubPilot(page: Page, pilot: SeedClubPilot): Promise<void> {
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, pilot.user.email, pilot.user.password);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
  await expect(page.getByTestId('landing-topbar-sign-in')).toHaveCount(0);
}
