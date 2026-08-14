import { randomUUID } from 'node:crypto';

import { type APIRequestContext, type Browser } from '@playwright/test';

import { assignRealmRole, createUserWithAttributes, deleteUser } from './keycloak-admin';
import { fillKcLogin } from './kc-form';
import { freshTestUser, type TestUser } from './test-user';
import { ACTIVE_CLUB_STATE_ID, CH_COUNTRY_ID, captureSysadminBearer } from './two-club-fixture';

/**
 * The club an anonymous J-17 registration is proved against, seeded entirely
 * through the production HTTP surfaces (ADR 0027 §3) — club create, club
 * update, location create, aircraft register, discovery-day publish. No raw
 * SQL and no Flyway addition: a happy-path registration writes a Person, a
 * membership, a reservation and two mails, so a club carrying it in the SHARED
 * clean seed would hand every other journey's gate a surprise registrant and an
 * unexpected outbound mail.
 *
 * `seed-club-1` cannot stand in either: `public-routes.spec.ts` pins it at the
 * unavailable panel (`public_registration_enabled = false`), which is the
 * route-reachability proof, not this one.
 *
 * <h2>Exactly one double-seater glider</h2>
 *
 * The legacy aircraft pick is a `FirstOrDefault` with no `ORDER BY`
 * (`RegistrationService.cs:152-156`), so a club with two eligible gliders makes
 * "the reservation names THIS aircraft" a DB-order coin flip. The seed
 * registers one, and the club is fresh, so the assertion is deterministic.
 *
 * <h2>...or none at all</h2>
 *
 * {@link seedPublicRegistrationClubWithoutDoubleSeater} provisions the same club
 * minus the aircraft. The reservation-skip AC needs a club that genuinely cannot
 * be booked against — deleting or hiding the glider afterwards would leave the
 * registration proving something about aircraft state rather than about a club
 * that never had one. It keeps its homebase, so the organiser mail must name the
 * missing double-seater and NOT the missing homebase.
 */

const CLUB_ADMINISTRATOR_ROLE = 'CLUB_ADMINISTRATOR';

/** Legacy's eligible-aircraft predicate: club-owned, pure glider, two seats. */
const GLIDER_TYPE_CODE = 'GLIDER';
const DOUBLE_SEATER_SEATS = 2;

export interface PublicRegistrationClub {
  /** Raw club UUID — the form the `clubId` claim and `@TenantId` carry. */
  clubId: string;
  /** External `clb-<uuid>` form — the form club routes and DTOs carry. */
  externalClubId: string;
  clubName: string;
  slug: string;
  /** The club's organiser-notification recipient (T-05's recipient list). */
  operatorEmail: string;
  /** External `loc-<uuid>` — the club homebase the reservation is booked at. */
  homebaseId: string;
  homebaseName: string;
  /** The single published discovery-flight day, `yyyy-MM-dd`. */
  eventDate: string;
  /** The club's own administrator — drives its club-admin screens in a browser. */
  admin: TestUser;
  /** `Bearer …` for the club's own administrator — reads the written rows back. */
  adminAuthorization: string;
  /** `Bearer …` for the seeded sysadmin, so a second seed skips its login. */
  sysadminAuthorization: string;
  dispose: () => Promise<void>;
}

/** The registrable club a discovery reservation can actually be booked at. */
export interface PublicRegistrationClubWithDoubleSeater extends PublicRegistrationClub {
  /** External `ac-<uuid>` — the club's only eligible double-seater glider. */
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

/**
 * A far-future date, distinct per call: a fixed one would already be published
 * on a retry and the duplicate-date 409 would read as a failure of the publish
 * path rather than of the fixture.
 */
export function discoveryDayDate(): string {
  const day = new Date();
  day.setUTCDate(day.getUTCDate() + 400 + Math.floor(Math.random() * 300));
  return day.toISOString().slice(0, 10);
}

/**
 * The registrant block exactly as the browser posts it (`toRegistrantDetails`
 * in `registrant-form.ts`): `firstname` / `lastname` / `zip`, and no
 * `invoiceRecipient` or `sendCouponToInvoiceAddress` key while the invoice
 * address is the registrant's own. The object mapper rejects unknown
 * properties, so a body shaped like the FE form model would 400 — which is why
 * this lives beside the seed rather than being re-typed per call site.
 */
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

/**
 * Log the freshly-minted club administrator in through the SPA + real Keycloak
 * and capture the Bearer the OIDC interceptor attaches to its own `/api/v1/*`
 * calls. No realm client grants the resource-owner-password flow, so a browser
 * login is the only way to a real tenant-scoped token.
 */
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
    // A club-admin read surface, so an authed `/api/v1/*` call is guaranteed to
    // fire even if the shell's bootstrap prefetch changes.
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

/**
 * Provision the whole happy-path fixture and hand back the handles the two
 * anonymous flows assert against. Every value is read back off the club
 * projection before returning, so a seed that silently failed to take (the club
 * PUT is full-replace — an omitted key clears) fails HERE, naming the field,
 * rather than surfacing as a mysterious skipped reservation later.
 *
 * Each call provisions a FRESH club: a Playwright retry re-runs `beforeAll`, and
 * a run-stable slug would 409 on the unique index. Clubs accumulate harmlessly,
 * as they already do for `two-club-fixture`'s club B.
 */
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

/**
 * The same club with no aircraft at all — the reservation-skip subject. Returns
 * the base shape, so a spec cannot reach for a glider id this club has not got.
 */
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

/** Bearers a prior seed already paid a browser login for. */
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

    // Full-replace PUT: every field the club must keep travels on this body, or
    // the save clears it.
    const operatorEmail = `e2e-${runId()}-organiser-${tag}@example.com`;
    const configured = await api.put(`/api/v1/clubs/${externalClubId}`, {
      headers: { authorization: adminAuthorization, 'content-type': 'application/json' },
      data: {
        name: clubName,
        slug,
        publicRegistrationEnabled: true,
        countryId: CH_COUNTRY_ID,
        clubStateId: ACTIVE_CLUB_STATE_ID,
        discoveryFlightOperatorEmail: operatorEmail,
        homebaseId: homebase.id,
      },
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

    // Names come off the stored projections, not the request literals: the
    // spec asserts them against the rendered heading and the mail bodies, and a
    // server-side normalisation would otherwise make those assertions fiction.
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
