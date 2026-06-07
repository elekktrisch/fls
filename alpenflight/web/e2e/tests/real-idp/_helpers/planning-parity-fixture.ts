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
const GLIDER_AIRCRAFT_TYPE_ID = '019e2e15-2c00-7af9-8000-000000002af9';

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
  /**
   * `ac-<uuid>` a fresh aircraft seed-club-1 manages + its immat — so the inline
   * per-day reservations panel can be POPULATED with a real J-5 reservation on the
   * captured planning day (T-16 populated-list parity shot). The aircraft is also
   * run-tagged so a retry's re-seed never collides on the immat unique index.
   */
  aircraftId: string;
  aircraftImmat: string;
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
    towPilotId: towPilot.id,
    towPilotName: towPilot.name,
    flightOpId: flightOp.id,
    flightOpName: flightOp.name,
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
