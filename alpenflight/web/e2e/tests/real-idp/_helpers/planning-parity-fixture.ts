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

/** seed-club-1 (V5 walking skeleton) — the @TenantId club every planning row is scoped to. */
const SEED_CLUB_ID = '019e30c3-2c00-7001-8000-000000000001';

/**
 * J-6 planning real-chain fixture — the clean-seed masterdata seeder for the
 * `planning-migration-parity.spec.ts` clean-seed half (T-13 capture pull-forward).
 *
 * The V34 dev seed (`V34__dev_planning_seed.sql`) already plants 2 sample future
 * planning days (e01 weekday/full-crew at Bern-Belp · e02 weekend/bare at Thun)
 * + 2 locations + 3 crew persons + 3 assignment types on seed-club-1 — so the
 * `/planning` future-days LIST renders non-empty against a clean realm without
 * any per-spec seeding. That seed is what the list-render capture asserts on.
 *
 * What V34 does NOT give us: the 3 seed crew persons (b1/b2/b3) carry NO
 * PersonClub membership, so they never surface in `/api/v1/persons` (the person
 * listitem read pivots FROM PersonClub — J-2 T-20) and are therefore NOT
 * pickable in the create/edit form's 3 crew `<af-select>`s. To drive the FULL
 * "create a day with date + location + 3-role crew + remarks" capture through
 * the real UI, this fixture seeds — as `clubadmin4`, through the REAL create
 * APIs (no mocking) — a FRESH location + 3 crew persons WITH a seed-club-1
 * membership so the pickers offer them. The fresh location also keeps the
 * happy-create off the V34 seed days' (date, location) so it never trips the
 * duplicate-409 (V4 ux_pln_club_date_loc) by accident.
 *
 * PRINCIPAL: reuses the J-5 `clubadmin4` (seed-club-1) helpers from
 * `reservation-parity-fixture.ts` — the same journey-agnostic seed-club-1
 * CLUB_ADMINISTRATOR principal (`loginAsReservationAdmin` /
 * `captureReservationAdminBearer`). Planning admin screens are ClubAdmin-gated,
 * so a real ClubAdmin is the correct low-privilege-appropriate principal here
 * (the PILOT-vs-creator authz delete/update probe stays T-16).
 */

/** Canonical reference seeds (V2/V3) the create requests reference. */
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

/**
 * seed-club-1's planning-day notification address — the recipient the imminent
 * (day+1) pass of `PlanningDayNotificationJob` mails (`Club.getPlanningDayInfoMailTo`).
 * Set on seed-club-1 by `V34__dev_planning_seed.sql` so the club opts in
 * (`wantsPlanningDayNotifications` = a non-blank address). The T-16 notification
 * real-idp case fires the guarded run-now affordance and asserts THIS address
 * receives the `planningday-ok`/`-cancel` mail via mailpit. Must match the V34 seed.
 */
export const SEED_CLUB_NOTIFICATION_ADDRESS = 'flugbetrieb@seed-club-1.example';

function shortTag(): string {
  return `${runId().slice(0, 3)}${Date.now().toString(36).slice(-4)}`.toUpperCase();
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

/** The masterdata a clean-seed planning day references (all SPA-prefixed ids). */
export interface PlanningMasterdata {
  /** `loc-<uuid>` a FRESH seed-club-1 location (distinct from the V34 seed days). */
  locationId: string;
  locationName: string;
  /** `pn-<uuid>` instructor person (with a seed-club-1 membership → pickable). */
  instructorId: string;
  instructorName: string;
  /** Unique-per-run private email — the week-ahead (day+7) assignment-mail recipient. */
  instructorEmail: string;
  /** `pn-<uuid>` tow-pilot person (with a seed-club-1 membership → pickable). */
  towPilotId: string;
  towPilotName: string;
  /** Unique-per-run private email — the week-ahead (day+7) assignment-mail recipient. */
  towPilotEmail: string;
  /** `pn-<uuid>` flight-operator person (with a seed-club-1 membership → pickable). */
  flightOpId: string;
  flightOpName: string;
  /** Unique-per-run private email — the week-ahead (day+7) assignment-mail recipient. */
  flightOpEmail: string;
  /**
   * `ac-<uuid>` a fresh aircraft seed-club-1 manages + its immat — so the inline
   * per-day reservations panel can be POPULATED with a real J-5 reservation on the
   * captured planning day (T-16 populated-list parity shot). The aircraft is also
   * run-tagged so a retry's re-seed never collides on the immat unique index.
   */
  aircraftId: string;
  aircraftImmat: string;
}

/**
 * Seed one crew person WITH a seed-club-1 membership (→ pickable in /persons) and
 * a UNIQUE-per-run private email. `Person.emailForCommunication()` returns the
 * private email (`preferMailToBusinessMail=false`), so the week-ahead (day+7)
 * notification pass mails this exact address — unique-per-run so the mailpit
 * `to:` match is unambiguous (the mailpit-client fails loud on >1 match).
 */
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

/**
 * Seed (as `clubadmin4`, through the REAL create APIs — no mocking) the
 * masterdata the clean-seed planning create/edit chain references: a FRESH
 * location + 3 crew persons WITH a seed-club-1 membership so the form's 3 crew
 * `<af-select>`s offer them. All names are run-tagged so a retry's re-seed never
 * collides on a unique index, and the fresh location keeps the happy-create off
 * the V34 seed days' (date, location).
 */
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

  // A fresh aircraft seed-club-1 manages — the inline reservations panel's
  // populated-list parity shot (T-16) reserves THIS aircraft on the captured
  // planning day so ≥1 `<af-reservation-row>` renders.
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

/**
 * Create a J-5 `AircraftReservation` on the GIVEN planning day's exact date +
 * location through the REAL reservations create API (no mocking), so the planning
 * edit screen's inline per-day reservations panel renders a POPULATED list (the
 * J-5 read-side join is `listAircraftReservationsForDay(date)` filtered to
 * `r.locationId === locationId` — `planning.store.ts:210-223`; the reservation
 * MUST be on the day's exact date + location to surface). Reserves a midday UTC
 * window (10:00–11:00Z) on `planningDate` so it overlaps that UTC day regardless
 * of the runner's local zone. Returns the created reservation id so the caller
 * tracks it for afterAll cleanup (the shared seed-club-1 tenant is never
 * truncated).
 *
 * @param reservationTypeId the V31-seeded default type (read via
 *   `fetchReservationTypeId` from the reservation fixture) — the create request's
 *   `reservationTypeId`.
 */
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
      start: `${args.planningDate}T10:00:00Z`,
      end: `${args.planningDate}T11:00:00Z`,
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

// ===========================================================================
// REAL LOW-PRIVILEGE PILOT — for the delete/update authz probe. The oracle:
// delete/update is gated to `CLUB_ADMINISTRATOR` OR the record creator
// (`PlanningDayService.cs:407-425`). The mock-admin principal is admin-everything
// and HIDES this gate ([[project_real_idp_real_roles_catches_authz_gaps]]); a real
// PILOT bound to seed-club-1 — neither admin NOR the day's creator — must be
// FORBIDDEN (403) from deleting a clubadmin4-created day. Provisioned the
// two-club-fixture way: a fresh `e2e-…@example.com` KC user (swept by teardown)
// with the `clubId` attribute set to seed-club-1's UUID (the realm maps it to a
// `clubId` claim the backend parses directly as the tenant) + the PILOT realm role.
// ===========================================================================

const PILOT_ROLE = 'PILOT';

/** A loginable real PILOT bound to seed-club-1 (a low-privilege, non-admin principal). */
export interface SeedClubPilot {
  user: TestUser;
  kcUserId: string;
  /** Remove the KC user (call in afterAll). */
  dispose: () => Promise<void>;
}

/**
 * Provision a real PILOT (realm role `PILOT`, no admin) bound to seed-club-1, so
 * the delete-authz probe drives a genuinely low-privilege principal. Disjoint
 * per call (run-id + fresh nonce) so a co-located spec / retry never collides on
 * `ux_user_username_lower_alive`.
 */
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

/**
 * Log the PILOT in through the SPA + real Keycloak and capture the Bearer the
 * OIDC interceptor attaches to its first `/api/v1/*` read — so the spec can issue
 * a direct REST delete as the PILOT identity and assert the 403 authz gate.
 */
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
    // The planning list issues GET /api/v1/planning-days/overview/future, stamped
    // by the interceptor with the PILOT's Bearer.
    await page.goto('/planning');
    const req = await bearerPromise;
    return req.headers()['authorization']!;
  } finally {
    await context.close();
  }
}

/** Drive the PILOT through the SPA login form (real Keycloak), landing authed. */
export async function loginAsSeedClubPilot(page: Page, pilot: SeedClubPilot): Promise<void> {
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, pilot.user.email, pilot.user.password);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
  await expect(page.getByTestId('landing-topbar-sign-in')).toHaveCount(0);
}
