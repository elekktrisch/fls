import { type APIRequestContext } from '@playwright/test';

import { seedFlightMasterdata, type FlightMasterdata } from './flight-parity-fixture';

/**
 * J-7 reporting-parity seed — the read-side fixture for
 * `flight-reports-parity.spec.ts`. J-7 is READ-SIDE over J-2 flight data and
 * adds NO migration mapper (journey frontmatter `migration: N/A`); this helper
 * seeds the flights the report assertions need THROUGH THE REAL create APIs (no
 * mocking, same chain the gate proves), reusing J-2's
 * {@link seedFlightMasterdata} for the masterdata closure.
 *
 * WHY a new seed affordance (not the J-2 `seedFlightParity` bundle): the J-2
 * migration bundle seeds a fixed set (one aerotow glider + tow + motor +
 * delivery-booked glider, with a single pilot+copilot crew and ONE flight type)
 * and the migrated club's admin is a provisioned Keycloak admin, NOT a crew
 * Person. The J-7 report contract needs MORE than the J-2 bundle provides — the
 * oracle's "Minimum legacy seed":
 *   - a pilot with ≥3 GLIDER flights incl. an aerotow pair,
 *   - the SAME pilot also PilotOrStudent on a MOTOR + a TOW flight (so the
 *     person-report summary shows Pilot (Motor)/(Towing) with the CORRECTED
 *     non-zero TotalFlights — the legacy-bug correction),
 *   - varied flight types (for the location-report group-by-FlightTypeName),
 *   - an instructor with a SOLO and a NON-SOLO flight (the Instructor vs
 *     Instructor (Soloflights) split),
 *   - a club-B flight (the tenant-isolation key-error).
 *
 * Per the task brief ("if a needed seed affordance is missing, build it in the
 * e2e fixture layer — not a new backend mapper") this is pure REAL-API seeding;
 * it reuses the J-2 masterdata seeder and adds flight creates + a couple of
 * extra masterdata rows (a second flight type, an instructor person). The
 * principal that drives the person report (the custom builder picks the seeded
 * pilot) is the same club-A admin from the two-club fixture.
 *
 * The CLUB-B flight is seeded separately (with club B's admin Bearer) by the
 * spec via {@link seedClubBFlight} so the isolation case has a real other-tenant
 * subject.
 */

/** V3-seeded flight-crew-type PKs (raw UUIDs — the create wire shape, FlightDtos). */
const CREW_TYPE_PILOT_OR_STUDENT = '019e2e15-2c00-76b0-8000-0000000036b0';
const CREW_TYPE_FLIGHT_INSTRUCTOR = '019e2e15-2c00-76b2-8000-0000000036b2';

/** V2-seeded start-type PKs (raw UUID — `FlightCreateRequest.startTypeId`). */
const START_TYPE_AEROTOW = '019e2e15-2c00-7fa1-8000-000000000fa1';
const START_TYPE_WINCH = '019e2e15-2c00-7fa0-8000-000000000fa0';
const START_TYPE_MOTOR = '019e2e15-2c00-7fa4-8000-000000000fa4';

export interface ReportingSeed {
  /** The masterdata closure (aircraft / locations / flight types / pilots). */
  masterdata: FlightMasterdata;
  /** A SECOND flight type so the location report groups ≥2 FlightTypeName rows. */
  secondFlightTypeId: string;
  secondFlightTypeName: string;
  /** The seeded glider pilot person id (`pn-…`) — drives the person report. */
  pilotPersonId: string;
  /** The seeded instructor person id (`pn-…`) — drives the Instructor split. */
  instructorPersonId: string;
  /** The aerotow GLIDER flight id (`fl-…`); its detail carries a towFlightId. */
  aerotowGliderFlightId: string;
  /** The linked TOW flight id (`fl-…`). */
  towFlightId: string;
}

interface FlightDetailLike {
  id: string;
  aircraftId: string;
  flightDate?: string | null;
  startLocationId?: string | null;
  ldgLocationId?: string | null;
  flightTypeId?: string | null;
  startTypeId?: string | null;
  isSoloFlight?: boolean;
  crew?: { personId: string; flightCrewTypeId: string }[];
}

async function postFlight(
  api: APIRequestContext,
  bearer: string,
  body: Record<string, unknown>,
): Promise<FlightDetailLike> {
  const res = await api.post('/api/v1/flights', {
    headers: { authorization: bearer, 'content-type': 'application/json' },
    data: body,
  });
  if (res.status() !== 201) {
    throw new Error(`POST /api/v1/flights failed (${res.status()}): ${await res.text()}`);
  }
  return (await res.json()) as FlightDetailLike;
}

async function putFlight(
  api: APIRequestContext,
  bearer: string,
  id: string,
  body: Record<string, unknown>,
): Promise<void> {
  const res = await api.put(`/api/v1/flights/${id}`, {
    headers: { authorization: bearer, 'content-type': 'application/json' },
    data: body,
  });
  if (res.status() !== 200) {
    throw new Error(`PUT /api/v1/flights/${id} failed (${res.status()}): ${await res.text()}`);
  }
}

async function postFlightType(
  api: APIRequestContext,
  bearer: string,
  name: string,
  code: string,
): Promise<string> {
  const res = await api.post('/api/v1/flight-types', {
    headers: { authorization: bearer, 'content-type': 'application/json' },
    data: {
      flightTypeName: name,
      flightCode: code.slice(0, 30),
      isInstructorRequired: false,
      isObserverPilotOrInstructorRequired: false,
      isCheckFlight: false,
      isPassengerFlight: false,
      isSoloFlight: false,
      isForGliderFlights: true,
      isForTowFlights: true,
      isForMotorFlights: true,
      isFlightCostBalanceSelectable: false,
      isCouponNumberRequired: false,
      isForAircraftReservationType: false,
    },
  });
  if (res.status() !== 201 && res.status() !== 200) {
    throw new Error(`POST /api/v1/flight-types failed (${res.status()}): ${await res.text()}`);
  }
  return String(((await res.json()) as { id: string }).id);
}

async function postInstructor(
  api: APIRequestContext,
  bearer: string,
  tag: string,
): Promise<string> {
  const res = await api.post('/api/v1/persons', {
    headers: { authorization: bearer, 'content-type': 'application/json' },
    data: {
      firstname: 'J7',
      lastname: `Instructor ${tag}`,
      preferMailToBusinessMail: false,
      receiveOwnedAircraftStatisticReports: false,
      enableAddress: false,
      initialClubMembership: {
        isMotorPilot: false,
        isTowPilot: false,
        isGliderInstructor: true,
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
    },
  });
  if (res.status() !== 201 && res.status() !== 200) {
    throw new Error(
      `POST /api/v1/persons (instructor) failed (${res.status()}): ${await res.text()}`,
    );
  }
  return String(((await res.json()) as { id: string }).id);
}

/** A flight_date N days before today (UTC), `YYYY-MM-DD` — keeps the seed inside
 *  any canned window the report asserts (today / this-year / last-30-days). */
function daysAgo(n: number): string {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() - n);
  return d.toISOString().slice(0, 10);
}

function iso(date: string, hhmm: string): string {
  return `${date}T${hhmm}:00Z`;
}

/**
 * Seed the full J-7 reporting fixture into CLUB A through the REAL create APIs.
 *
 * Flights (all flight_date within the last few days so today/this-year/30-day
 * windows all contain them):
 *   1. TOW flight (tow aircraft, pilot=PilotOrStudent) — its own row.
 *   2. AEROTOW GLIDER (glider aircraft, pilot=PilotOrStudent), then PUT-linked
 *      to (1) → the glider's detail carries towFlightId (nested-tow block).
 *   3. A second GLIDER (winch) flown by the same pilot — so the pilot has ≥3
 *      glider flights (2 glider + the aerotow pair counts the glider once; this
 *      adds the third).
 *   4. A MOTOR flight (motor aircraft, pilot=PilotOrStudent) — so the person
 *      summary shows Pilot (Motor) with non-zero TotalFlights.
 *   5. An INSTRUCTOR non-solo glider flight (instructor=FlightInstructor).
 *   6. An INSTRUCTOR SOLO glider flight (instructor=FlightInstructor, solo) —
 *      the Instructor vs Instructor (Soloflights) split.
 *   #5/#6 use the SECOND flight type so the location report groups ≥2
 *   FlightTypeName rows.
 */
export async function seedReportingFixture(
  api: APIRequestContext,
  bearer: string,
): Promise<ReportingSeed> {
  const masterdata = await seedFlightMasterdata(api, bearer);
  const tag = Math.random().toString(36).slice(2, 7).toUpperCase();
  const secondFlightTypeName = `J7 XC ${tag}`;
  const secondFlightTypeId = await postFlightType(api, bearer, secondFlightTypeName, `J7X${tag}`);
  const instructorPersonId = await postInstructor(api, bearer, tag);

  // Two distinct flight types are now seeded (the masterdata seeder's `J2 Local
  // <tag>` glider/motor/tow type + the second `J7 XC <tag>` here), so the
  // location report groups ≥2 FlightTypeName rows + Total. The spec asserts the
  // row COUNT (≥2 groups), not the exact names, so no name is plumbed through.

  const pilot = masterdata.pilotPersonId;

  // 1. TOW flight (its own row).
  const towDate = daysAgo(2);
  const tow = await postFlight(api, bearer, {
    flightAircraftType: 'TOW',
    aircraftId: masterdata.towAircraftId,
    flightDate: towDate,
    startDateTime: iso(towDate, '08:00'),
    ldgDateTime: iso(towDate, '08:12'),
    startLocationId: masterdata.locationId,
    ldgLocationId: masterdata.locationId,
    flightTypeId: masterdata.gliderFlightTypeId,
    startTypeId: START_TYPE_AEROTOW,
    isSoloFlight: false,
    noStartTimeInformation: false,
    noLdgTimeInformation: false,
    crew: [{ personId: masterdata.towPilotPersonId, flightCrewTypeId: CREW_TYPE_PILOT_OR_STUDENT }],
  });

  // 2. AEROTOW GLIDER (pilot), then PUT-link to the tow.
  const glider = await postFlight(api, bearer, {
    flightAircraftType: 'GLIDER',
    aircraftId: masterdata.gliderAircraftId,
    flightDate: towDate,
    startDateTime: iso(towDate, '08:00'),
    ldgDateTime: iso(towDate, '09:30'),
    startLocationId: masterdata.locationId,
    ldgLocationId: masterdata.locationId,
    flightTypeId: masterdata.gliderFlightTypeId,
    startTypeId: START_TYPE_AEROTOW,
    isSoloFlight: false,
    noStartTimeInformation: false,
    noLdgTimeInformation: false,
    crew: [{ personId: pilot, flightCrewTypeId: CREW_TYPE_PILOT_OR_STUDENT }],
  });
  await putFlight(api, bearer, glider.id, {
    aircraftId: masterdata.gliderAircraftId,
    flightDate: towDate,
    startDateTime: iso(towDate, '08:00'),
    ldgDateTime: iso(towDate, '09:30'),
    startLocationId: masterdata.locationId,
    ldgLocationId: masterdata.locationId,
    flightTypeId: masterdata.gliderFlightTypeId,
    startTypeId: START_TYPE_AEROTOW,
    isSoloFlight: false,
    noStartTimeInformation: false,
    noLdgTimeInformation: false,
    towFlightId: tow.id,
    crew: [{ personId: pilot, flightCrewTypeId: CREW_TYPE_PILOT_OR_STUDENT }],
  });

  // 3. A second (winch) GLIDER flown by the same pilot — third glider flight.
  const winchDate = daysAgo(3);
  await postFlight(api, bearer, {
    flightAircraftType: 'GLIDER',
    aircraftId: masterdata.gliderAircraftId,
    flightDate: winchDate,
    startDateTime: iso(winchDate, '10:00'),
    ldgDateTime: iso(winchDate, '10:45'),
    startLocationId: masterdata.locationId,
    ldgLocationId: masterdata.locationId,
    flightTypeId: masterdata.gliderFlightTypeId,
    startTypeId: START_TYPE_WINCH,
    isSoloFlight: false,
    noStartTimeInformation: false,
    noLdgTimeInformation: false,
    crew: [{ personId: pilot, flightCrewTypeId: CREW_TYPE_PILOT_OR_STUDENT }],
  });

  // 4. A MOTOR flight flown by the same pilot — Pilot (Motor) non-zero count.
  const motorDate = daysAgo(1);
  await postFlight(api, bearer, {
    flightAircraftType: 'MOTOR',
    aircraftId: masterdata.motorAircraftId,
    flightDate: motorDate,
    startDateTime: iso(motorDate, '11:00'),
    ldgDateTime: iso(motorDate, '12:30'),
    startLocationId: masterdata.locationId,
    ldgLocationId: masterdata.locationId,
    flightTypeId: secondFlightTypeId,
    startTypeId: START_TYPE_MOTOR,
    isSoloFlight: false,
    noStartTimeInformation: false,
    noLdgTimeInformation: false,
    crew: [{ personId: pilot, flightCrewTypeId: CREW_TYPE_PILOT_OR_STUDENT }],
  });

  // 5. INSTRUCTOR non-solo glider flight (second flight type for location group).
  const instrDate = daysAgo(4);
  await postFlight(api, bearer, {
    flightAircraftType: 'GLIDER',
    aircraftId: masterdata.gliderAircraftId,
    flightDate: instrDate,
    startDateTime: iso(instrDate, '13:00'),
    ldgDateTime: iso(instrDate, '13:45'),
    startLocationId: masterdata.locationId,
    ldgLocationId: masterdata.locationId,
    flightTypeId: secondFlightTypeId,
    startTypeId: START_TYPE_WINCH,
    isSoloFlight: false,
    noStartTimeInformation: false,
    noLdgTimeInformation: false,
    crew: [{ personId: instructorPersonId, flightCrewTypeId: CREW_TYPE_FLIGHT_INSTRUCTOR }],
  });

  // 6. INSTRUCTOR SOLO glider flight — the Instructor (Soloflights) split.
  const soloDate = daysAgo(5);
  await postFlight(api, bearer, {
    flightAircraftType: 'GLIDER',
    aircraftId: masterdata.gliderAircraftId,
    flightDate: soloDate,
    startDateTime: iso(soloDate, '14:00'),
    ldgDateTime: iso(soloDate, '14:30'),
    startLocationId: masterdata.locationId,
    ldgLocationId: masterdata.locationId,
    flightTypeId: secondFlightTypeId,
    startTypeId: START_TYPE_WINCH,
    isSoloFlight: true,
    noStartTimeInformation: false,
    noLdgTimeInformation: false,
    crew: [{ personId: instructorPersonId, flightCrewTypeId: CREW_TYPE_FLIGHT_INSTRUCTOR }],
  });

  return {
    masterdata,
    secondFlightTypeId,
    secondFlightTypeName,
    pilotPersonId: pilot,
    instructorPersonId,
    aerotowGliderFlightId: glider.id,
    towFlightId: tow.id,
  };
}

/**
 * Seed ONE flight into CLUB B (with club B's admin Bearer) so the
 * tenant-isolation case has a real other-tenant subject: a club-A principal
 * filtering by club B's location must see NONE of these. Returns the club-B
 * start location id (the foreign location the isolation case filters by).
 */
export async function seedClubBFlight(
  api: APIRequestContext,
  bearer: string,
): Promise<{ locationId: string }> {
  const masterdata = await seedFlightMasterdata(api, bearer);
  const date = daysAgo(1);
  await postFlight(api, bearer, {
    flightAircraftType: 'GLIDER',
    aircraftId: masterdata.gliderAircraftId,
    flightDate: date,
    startDateTime: iso(date, '09:00'),
    ldgDateTime: iso(date, '10:00'),
    startLocationId: masterdata.locationId,
    ldgLocationId: masterdata.locationId,
    flightTypeId: masterdata.gliderFlightTypeId,
    startTypeId: START_TYPE_WINCH,
    isSoloFlight: false,
    noStartTimeInformation: false,
    noLdgTimeInformation: false,
    crew: [{ personId: masterdata.pilotPersonId, flightCrewTypeId: CREW_TYPE_PILOT_OR_STUDENT }],
  });
  return { locationId: masterdata.locationId };
}
