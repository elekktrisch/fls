import { type APIRequestContext } from '@playwright/test';

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

function runId(): string {
  const id = process.env['E2E_RUN_ID'];
  if (!id) {
    throw new Error('E2E_RUN_ID not set — real-idp-setup must run before the planning fixture');
  }
  return id;
}

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
  /** `pn-<uuid>` tow-pilot person (with a seed-club-1 membership → pickable). */
  towPilotId: string;
  towPilotName: string;
  /** `pn-<uuid>` flight-operator person (with a seed-club-1 membership → pickable). */
  flightOpId: string;
  flightOpName: string;
}

/** Seed one crew person WITH a seed-club-1 membership (→ pickable in /persons). */
async function seedCrewPerson(
  api: APIRequestContext,
  bearer: string,
  firstname: string,
  lastname: string,
  flags: { isGliderInstructor?: boolean; isTowPilot?: boolean },
): Promise<{ id: string; name: string }> {
  const person = await postJson(api, bearer, '/api/v1/persons', {
    firstname,
    lastname,
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
  return { id: String(person['id']), name: `${firstname} ${lastname}` };
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

  const instructor = await seedCrewPerson(api, bearer, 'Ingrid', `Fluglehrer ${tag}`, {
    isGliderInstructor: true,
  });
  const towPilot = await seedCrewPerson(api, bearer, 'Theo', `Schlepppilot ${tag}`, {
    isTowPilot: true,
  });
  const flightOp = await seedCrewPerson(api, bearer, 'Frieda', `Flugleiter ${tag}`, {});

  return {
    locationId: String(location['id']),
    locationName: `J6 Planning Field ${tag}`,
    instructorId: instructor.id,
    instructorName: instructor.name,
    towPilotId: towPilot.id,
    towPilotName: towPilot.name,
    flightOpId: flightOp.id,
    flightOpName: flightOp.name,
  };
}
