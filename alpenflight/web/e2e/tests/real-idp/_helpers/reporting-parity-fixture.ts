import { type APIRequestContext } from '@playwright/test';

import { seedFlightMasterdata, type FlightMasterdata } from './flight-parity-fixture';

const CREW_TYPE_PILOT_OR_STUDENT = '019e2e15-2c00-76b0-8000-0000000036b0';
const CREW_TYPE_FLIGHT_INSTRUCTOR = '019e2e15-2c00-76b2-8000-0000000036b2';

const START_TYPE_AEROTOW = '019e2e15-2c00-7fa1-8000-000000000fa1';
const START_TYPE_WINCH = '019e2e15-2c00-7fa0-8000-000000000fa0';
const START_TYPE_MOTOR = '019e2e15-2c00-7fa4-8000-000000000fa4';

export const SEED_CLUB_HOMEBASE_LOCATION_ID = 'loc-019e30c3-2c00-7001-8000-00000000c001';

export interface ReportingSeed {
  masterdata: FlightMasterdata;
  secondFlightTypeId: string;
  secondFlightTypeName: string;
  pilotPersonId: string;
  instructorPersonId: string;
  aerotowGliderFlightId: string;
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

function daysAgo(n: number): string {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() - n);
  return d.toISOString().slice(0, 10);
}

function iso(date: string, hhmm: string): string {
  return `${date}T${hhmm}:00Z`;
}

export async function seedReportingFixture(
  api: APIRequestContext,
  bearer: string,
): Promise<ReportingSeed> {
  const masterdata = await seedFlightMasterdata(api, bearer);
  const tag = Math.random().toString(36).slice(2, 7).toUpperCase();
  const secondFlightTypeName = `J7 XC ${tag}`;
  const secondFlightTypeId = await postFlightType(api, bearer, secondFlightTypeName, `J7X${tag}`);
  const instructorPersonId = await postInstructor(api, bearer, tag);

  const pilot = masterdata.pilotPersonId;
  const locationReportHomebase = SEED_CLUB_HOMEBASE_LOCATION_ID;

  const aerotowPairDate = daysAgo(2);
  const aerotowTowFlight = await postFlight(api, bearer, {
    flightAircraftType: 'TOW',
    aircraftId: masterdata.towAircraftId,
    flightDate: aerotowPairDate,
    startDateTime: iso(aerotowPairDate, '08:00'),
    ldgDateTime: iso(aerotowPairDate, '08:12'),
    startLocationId: locationReportHomebase,
    ldgLocationId: locationReportHomebase,
    flightTypeId: masterdata.gliderFlightTypeId,
    startTypeId: START_TYPE_AEROTOW,
    isSoloFlight: false,
    noStartTimeInformation: false,
    noLdgTimeInformation: false,
    crew: [{ personId: masterdata.towPilotPersonId, flightCrewTypeId: CREW_TYPE_PILOT_OR_STUDENT }],
  });

  const aerotowGliderFlight = await postFlight(api, bearer, {
    flightAircraftType: 'GLIDER',
    aircraftId: masterdata.gliderAircraftId,
    flightDate: aerotowPairDate,
    startDateTime: iso(aerotowPairDate, '08:00'),
    ldgDateTime: iso(aerotowPairDate, '09:30'),
    startLocationId: locationReportHomebase,
    ldgLocationId: locationReportHomebase,
    flightTypeId: masterdata.gliderFlightTypeId,
    startTypeId: START_TYPE_AEROTOW,
    isSoloFlight: false,
    noStartTimeInformation: false,
    noLdgTimeInformation: false,
    crew: [{ personId: pilot, flightCrewTypeId: CREW_TYPE_PILOT_OR_STUDENT }],
  });
  await putFlight(api, bearer, aerotowGliderFlight.id, {
    aircraftId: masterdata.gliderAircraftId,
    flightDate: aerotowPairDate,
    startDateTime: iso(aerotowPairDate, '08:00'),
    ldgDateTime: iso(aerotowPairDate, '09:30'),
    startLocationId: locationReportHomebase,
    ldgLocationId: locationReportHomebase,
    flightTypeId: masterdata.gliderFlightTypeId,
    startTypeId: START_TYPE_AEROTOW,
    isSoloFlight: false,
    noStartTimeInformation: false,
    noLdgTimeInformation: false,
    towFlightId: aerotowTowFlight.id,
    crew: [{ personId: pilot, flightCrewTypeId: CREW_TYPE_PILOT_OR_STUDENT }],
  });

  const pilotSecondGliderDate = daysAgo(3);
  await postFlight(api, bearer, {
    flightAircraftType: 'GLIDER',
    aircraftId: masterdata.gliderAircraftId,
    flightDate: pilotSecondGliderDate,
    startDateTime: iso(pilotSecondGliderDate, '10:00'),
    ldgDateTime: iso(pilotSecondGliderDate, '10:45'),
    startLocationId: locationReportHomebase,
    ldgLocationId: locationReportHomebase,
    flightTypeId: masterdata.gliderFlightTypeId,
    startTypeId: START_TYPE_WINCH,
    isSoloFlight: false,
    noStartTimeInformation: false,
    noLdgTimeInformation: false,
    crew: [{ personId: pilot, flightCrewTypeId: CREW_TYPE_PILOT_OR_STUDENT }],
  });

  const pilotMotorDate = daysAgo(1);
  await postFlight(api, bearer, {
    flightAircraftType: 'MOTOR',
    aircraftId: masterdata.motorAircraftId,
    flightDate: pilotMotorDate,
    startDateTime: iso(pilotMotorDate, '11:00'),
    ldgDateTime: iso(pilotMotorDate, '12:30'),
    startLocationId: locationReportHomebase,
    ldgLocationId: locationReportHomebase,
    flightTypeId: secondFlightTypeId,
    startTypeId: START_TYPE_MOTOR,
    isSoloFlight: false,
    noStartTimeInformation: false,
    noLdgTimeInformation: false,
    crew: [{ personId: pilot, flightCrewTypeId: CREW_TYPE_PILOT_OR_STUDENT }],
  });

  const pilotFlyingTheTowAircraftDate = daysAgo(1);
  await postFlight(api, bearer, {
    flightAircraftType: 'TOW',
    aircraftId: masterdata.towAircraftId,
    flightDate: pilotFlyingTheTowAircraftDate,
    startDateTime: iso(pilotFlyingTheTowAircraftDate, '15:00'),
    ldgDateTime: iso(pilotFlyingTheTowAircraftDate, '15:14'),
    startLocationId: locationReportHomebase,
    ldgLocationId: locationReportHomebase,
    flightTypeId: masterdata.gliderFlightTypeId,
    startTypeId: START_TYPE_AEROTOW,
    isSoloFlight: false,
    noStartTimeInformation: false,
    noLdgTimeInformation: false,
    crew: [{ personId: pilot, flightCrewTypeId: CREW_TYPE_PILOT_OR_STUDENT }],
  });

  const instructorNonSoloDate = daysAgo(4);
  await postFlight(api, bearer, {
    flightAircraftType: 'GLIDER',
    aircraftId: masterdata.gliderAircraftId,
    flightDate: instructorNonSoloDate,
    startDateTime: iso(instructorNonSoloDate, '13:00'),
    ldgDateTime: iso(instructorNonSoloDate, '13:45'),
    startLocationId: locationReportHomebase,
    ldgLocationId: locationReportHomebase,
    flightTypeId: secondFlightTypeId,
    startTypeId: START_TYPE_WINCH,
    isSoloFlight: false,
    noStartTimeInformation: false,
    noLdgTimeInformation: false,
    crew: [{ personId: instructorPersonId, flightCrewTypeId: CREW_TYPE_FLIGHT_INSTRUCTOR }],
  });

  const instructorSoloDate = daysAgo(5);
  await postFlight(api, bearer, {
    flightAircraftType: 'GLIDER',
    aircraftId: masterdata.gliderAircraftId,
    flightDate: instructorSoloDate,
    startDateTime: iso(instructorSoloDate, '14:00'),
    ldgDateTime: iso(instructorSoloDate, '14:30'),
    startLocationId: locationReportHomebase,
    ldgLocationId: locationReportHomebase,
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
    aerotowGliderFlightId: aerotowGliderFlight.id,
    towFlightId: aerotowTowFlight.id,
  };
}

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
