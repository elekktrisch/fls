import { randomUUID } from 'node:crypto';

import { type APIRequestContext, type Browser } from '@playwright/test';

import { assignRealmRole, createUserWithAttributes, deleteUser } from './keycloak-admin';
import { fillKcLogin } from './kc-form';
import { freshTestUser, type TestUser } from './test-user';
import { ACTIVE_CLUB_STATE_ID, CH_COUNTRY_ID, captureSysadminBearer } from './two-club-fixture';

const CLUB_ADMINISTRATOR_ROLE = 'CLUB_ADMINISTRATOR';

const GLIDER_TYPE_CODE = 'GLIDER';
const DOUBLE_SEATER_SEATS = 2;

export interface PublicRegistrationClub {
  clubId: string;
  externalClubId: string;
  clubName: string;
  slug: string;
  operatorEmail: string;
  homebaseId: string;
  homebaseName: string;
  eventDate: string;
  admin: TestUser;
  adminAuthorization: string;
  sysadminAuthorization: string;
  dispose: () => Promise<void>;
}

export interface PublicRegistrationClubWithDoubleSeater extends PublicRegistrationClub {
  gliderId: string;
  gliderImmatriculation: string;
}

function runId(): string {
  const id = process.env['E2E_RUN_ID'];
  if (!id) {
    throw new Error(
      'E2E_RUN_ID not set — real-idp-setup must run before the public-registration fixture',
    );
  }
  return id;
}

function nonce(): string {
  return randomUUID().replace(/-/g, '').slice(0, 8);
}

export function discoveryDayDate(): string {
  const day = new Date();
  day.setUTCDate(day.getUTCDate() + 400 + Math.floor(Math.random() * 300));
  return day.toISOString().slice(0, 10);
}

export function registrantWireBody(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    firstname: 'Nina',
    lastname: 'Brunner',
    addressLine1: 'Flugplatzstrasse 12',
    zip: '8600',
    city: 'Duebendorf',
    mobilePhone: '+41 79 555 21 12',
    privateEmail: 'nina.brunner@example.com',
    remarks: 'Erstflug, Geschenk',
    invoiceAddressIsSame: true,
    ...overrides,
  };
}

async function readJson<T>(
  api: APIRequestContext,
  path: string,
  authorization: string,
): Promise<T> {
  const res = await api.get(path, { headers: { authorization } });
  if (!res.ok()) {
    throw new Error(`GET ${path} failed (${res.status()}): ${await res.text()}`);
  }
  return (await res.json()) as T;
}

async function createJson<T>(
  api: APIRequestContext,
  path: string,
  authorization: string,
  data: Record<string, unknown>,
): Promise<T> {
  const res = await api.post(path, {
    headers: { authorization, 'content-type': 'application/json' },
    data,
  });
  if (res.status() !== 201) {
    throw new Error(`POST ${path} expected 201, got ${res.status()}: ${await res.text()}`);
  }
  return (await res.json()) as T;
}

async function captureClubAdminBearer(
  browser: Browser,
  baseURL: string,
  admin: TestUser,
): Promise<string> {
  const context = await browser.newContext({ baseURL });
  const page = await context.newPage();
  try {
    const bearerRequest = page.waitForRequest(
      (req) =>
        req.url().includes('/api/v1/') && /^Bearer /i.test(req.headers()['authorization'] ?? ''),
    );
    await page.goto('/');
    await page.getByTestId('landing-topbar-sign-in').click();
    await page.waitForURL(/\/realms\/alpenflight\//);
    await fillKcLogin(page, admin.email, admin.password);
    await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
    await page.goto('/locations');
    return (await bearerRequest).headers()['authorization']!;
  } finally {
    await context.close();
  }
}

interface ClubResponse {
  id: string;
  name: string;
  slug: string | null;
  publicRegistrationEnabled: boolean;
  homebaseId: string | null;
  discoveryFlightOperatorEmail: string | null;
}

interface LocationDetail {
  id: string;
  locationName: string;
}

interface AircraftDetail {
  id: string;
  immatriculation: string;
  nrOfSeats: number | null;
}

interface ReferenceRow {
  id: string;
  code: string;
}

export async function seedPublicRegistrationClub(
  browser: Browser,
  baseURL: string,
  reuse: SeedReuse = {},
): Promise<PublicRegistrationClubWithDoubleSeater> {
  const { glider, ...club } = await seedClub(browser, baseURL, true, reuse);
  if (!glider) {
    throw new Error('the seeded club was asked for a double-seater and came back without one');
  }
  return { ...club, gliderId: glider.id, gliderImmatriculation: glider.immatriculation };
}

export async function seedPublicRegistrationClubWithoutDoubleSeater(
  browser: Browser,
  baseURL: string,
  reuse: SeedReuse = {},
): Promise<PublicRegistrationClub> {
  const { glider, ...club } = await seedClub(browser, baseURL, false, reuse);
  if (glider) {
    throw new Error(`the gliderless club registered ${glider.immatriculation} anyway`);
  }
  return club;
}

export interface SeedReuse {
  sysadminAuthorization?: string;
}

interface SeededClub extends PublicRegistrationClub {
  glider: AircraftDetail | null;
}

async function seedClub(
  browser: Browser,
  baseURL: string,
  doubleSeater: boolean,
  reuse: SeedReuse,
): Promise<SeededClub> {
  const tag = nonce();
  const sysadmin = reuse.sysadminAuthorization ?? (await captureSysadminBearer(browser, baseURL));
  const context = await browser.newContext({ baseURL });
  const api = context.request;

  try {
    const slug = `e2e-${runId()}-j17-${tag}`;
    const clubName = `E2E J-17 Public Club ${tag}`;
    const club = await createJson<ClubResponse>(api, '/api/v1/clubs', sysadmin, {
      name: clubName,
      slug,
      clubKey: `J17${tag.slice(0, 6).toUpperCase()}`,
      publicRegistrationEnabled: true,
      countryId: CH_COUNTRY_ID,
      clubStateId: ACTIVE_CLUB_STATE_ID,
    });
    const externalClubId = club.id;
    const clubId = externalClubId.replace(/^clb-/, '');

    const admin = freshTestUser();
    const kcUserId = await createUserWithAttributes(admin, { clubId: [clubId] });
    await assignRealmRole(kcUserId, CLUB_ADMINISTRATOR_ROLE);
    const adminAuthorization = await captureClubAdminBearer(browser, baseURL, admin);

    const locationTypes = await readJson<ReferenceRow[]>(
      api,
      '/api/v1/location-types',
      adminAuthorization,
    );
    const locationTypeId = locationTypes[0]?.id;
    if (!locationTypeId) {
      throw new Error('the reference catalog carries no location type to seed a homebase with');
    }
    const homebaseName = `E2E J-17 Homebase ${tag}`;
    const homebase = await createJson<LocationDetail>(
      api,
      '/api/v1/locations',
      adminAuthorization,
      {
        locationName: homebaseName,
        countryId: CH_COUNTRY_ID,
        locationTypeId,
        isInboundRouteRequired: false,
        isOutboundRouteRequired: false,
        isFastEntryRecord: false,
      },
    );

    const glider = doubleSeater
      ? await registerDoubleSeater(api, adminAuthorization, tag, homebase.id)
      : null;

    const eventDate = discoveryDayDate();
    await createJson(api, '/api/v1/discovery-flight-days', adminAuthorization, { eventDate });

    const operatorEmail = `e2e-${runId()}-organiser-${tag}@example.com`;
    const everyFieldTheClubMustKeep = {
      name: clubName,
      slug,
      publicRegistrationEnabled: true,
      countryId: CH_COUNTRY_ID,
      clubStateId: ACTIVE_CLUB_STATE_ID,
      discoveryFlightOperatorEmail: operatorEmail,
      homebaseId: homebase.id,
    };
    const configured = await api.put(`/api/v1/clubs/${externalClubId}`, {
      headers: { authorization: adminAuthorization, 'content-type': 'application/json' },
      data: everyFieldTheClubMustKeep,
    });
    if (!configured.ok()) {
      throw new Error(
        `PUT /api/v1/clubs/${externalClubId} failed (${configured.status()}): ` +
          `${await configured.text()}`,
      );
    }

    const stored = await readJson<ClubResponse>(
      api,
      `/api/v1/clubs/${externalClubId}`,
      adminAuthorization,
    );
    assertSeeded(stored, { slug, homebaseId: homebase.id, operatorEmail });

    const days = await readJson<string[]>(
      api,
      `/api/v1/public/clubs/${slug}/discovery-flight-days`,
      adminAuthorization,
    );
    if (!days.includes(eventDate)) {
      throw new Error(
        `the seeded club offers ${JSON.stringify(days)} — the published day ${eventDate} is ` +
          'not bookable, so the anonymous picker would render an empty fieldset',
      );
    }

    return {
      clubId,
      externalClubId,
      clubName: stored.name,
      slug,
      operatorEmail,
      homebaseId: homebase.id,
      homebaseName: homebase.locationName,
      glider,
      eventDate,
      admin,
      adminAuthorization,
      sysadminAuthorization: sysadmin,
      dispose: async () => {
        await deleteUser(kcUserId, admin.email);
      },
    };
  } finally {
    await context.close();
  }
}

async function registerDoubleSeater(
  api: APIRequestContext,
  adminAuthorization: string,
  tag: string,
  homebaseId: string,
): Promise<AircraftDetail> {
  const aircraftTypes = await readJson<ReferenceRow[]>(
    api,
    '/api/v1/aircraft-types',
    adminAuthorization,
  );
  const gliderTypeId = aircraftTypes.find((type) => type.code === GLIDER_TYPE_CODE)?.id;
  if (!gliderTypeId) {
    throw new Error(`the aircraft-type catalog carries no ${GLIDER_TYPE_CODE} row`);
  }
  return createJson<AircraftDetail>(api, '/api/v1/aircraft', adminAuthorization, {
    aircraftTypeId: gliderTypeId,
    immatriculation: `HB-J17${tag.slice(0, 5).toUpperCase()}`,
    nrOfSeats: DOUBLE_SEATER_SEATS,
    homebaseId,
    isTowingOrWinchRequired: false,
    isTowingStartAllowed: false,
    isWinchStartAllowed: false,
    isTowingAircraft: false,
  });
}

function assertSeeded(
  stored: ClubResponse,
  expected: { slug: string; homebaseId: string; operatorEmail: string },
): void {
  const mismatches: string[] = [];
  if (!stored.publicRegistrationEnabled) mismatches.push('publicRegistrationEnabled is false');
  if (stored.slug !== expected.slug) mismatches.push(`slug is ${stored.slug}`);
  if (stored.homebaseId !== expected.homebaseId)
    mismatches.push(`homebaseId is ${stored.homebaseId}`);
  if (stored.discoveryFlightOperatorEmail !== expected.operatorEmail) {
    mismatches.push(`discoveryFlightOperatorEmail is ${stored.discoveryFlightOperatorEmail}`);
  }
  if (mismatches.length > 0) {
    throw new Error(
      `the seeded club did not store its registration configuration: ${mismatches.join('; ')}`,
    );
  }
}
