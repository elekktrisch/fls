import { type APIRequestContext } from '@playwright/test';

import { seedFlightMasterdata } from './flight-parity-fixture';
import { daysAgo } from './seed-flight-date';
import { freshTestUser } from './test-user';

const CREW_TYPE_PILOT_OR_STUDENT = '019e2e15-2c00-76b0-8000-0000000036b0';
const CREW_TYPE_FLIGHT_INSTRUCTOR = '019e2e15-2c00-76b2-8000-0000000036b2';

const START_TYPE_WINCH = '019e2e15-2c00-7fa0-8000-000000000fa0';

const COMPLETED_FLIGHT_DAYS_AGO_WITHIN_REPORT_WINDOW = 1;

const REPORT_CREW_FIRSTNAME = 'Rapport';

export interface DailyReportCrewMember {
  personId: string;
  email: string;
  displayName: string;
}

export interface DailyReportSeed {
  optedIn: DailyReportCrewMember;
  optedOut: DailyReportCrewMember;
  immatriculation: string;
  renderedFlightDate: string;
}

interface PersonCreated {
  id: string;
  memberships?: { receiveFlightReports: boolean }[];
}

function ddMmYyyy(isoDate: string): string {
  const [year, month, day] = isoDate.split('-');
  return `${day}.${month}.${year}`;
}

async function createMember(
  api: APIRequestContext,
  bearer: string,
  label: string,
  receiveFlightReports: boolean,
): Promise<DailyReportCrewMember> {
  const email = freshTestUser().email;
  const lastname = `${label} ${email.split('@')[0]!.slice(-8).toUpperCase()}`;
  const res = await api.post('/api/v1/persons', {
    headers: { authorization: bearer, 'content-type': 'application/json' },
    data: {
      firstname: REPORT_CREW_FIRSTNAME,
      lastname,
      emailPrivate: email,
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
        receiveFlightReports,
        receiveAircraftReservationNotifications: false,
        receivePlanningDayRoleReminder: false,
        isActive: true,
      },
    },
  });
  if (res.status() !== 201 && res.status() !== 200) {
    throw new Error(`POST /api/v1/persons failed (${res.status()}): ${await res.text()}`);
  }

  const person = (await res.json()) as PersonCreated;
  const stored = person.memberships?.[0];
  if (!stored || stored.receiveFlightReports !== receiveFlightReports) {
    throw new Error(
      `the seeded member's club membership stored receiveFlightReports=` +
        `${stored?.receiveFlightReports} instead of ${receiveFlightReports}`,
    );
  }
  return { personId: person.id, email, displayName: `${REPORT_CREW_FIRSTNAME} ${lastname}` };
}

export async function seedDailyReportCrew(
  api: APIRequestContext,
  bearer: string,
): Promise<DailyReportSeed> {
  const masterdata = await seedFlightMasterdata(api, bearer);
  const optedIn = await createMember(api, bearer, 'Optin', true);
  const optedOut = await createMember(api, bearer, 'Optout', false);

  const flightDate = daysAgo(COMPLETED_FLIGHT_DAYS_AGO_WITHIN_REPORT_WINDOW);
  const res = await api.post('/api/v1/flights', {
    headers: { authorization: bearer, 'content-type': 'application/json' },
    data: {
      flightAircraftType: 'GLIDER',
      aircraftId: masterdata.gliderAircraftId,
      flightDate,
      startDateTime: `${flightDate}T09:00:00Z`,
      ldgDateTime: `${flightDate}T10:12:00Z`,
      startLocationId: masterdata.locationId,
      ldgLocationId: masterdata.locationId,
      flightTypeId: masterdata.gliderFlightTypeId,
      startTypeId: START_TYPE_WINCH,
      isSoloFlight: false,
      noStartTimeInformation: false,
      noLdgTimeInformation: false,
      crew: [
        { personId: optedIn.personId, flightCrewTypeId: CREW_TYPE_PILOT_OR_STUDENT },
        { personId: optedOut.personId, flightCrewTypeId: CREW_TYPE_FLIGHT_INSTRUCTOR },
      ],
    },
  });
  if (res.status() !== 201) {
    throw new Error(`POST /api/v1/flights failed (${res.status()}): ${await res.text()}`);
  }

  return {
    optedIn,
    optedOut,
    immatriculation: masterdata.gliderImmat,
    renderedFlightDate: ddMmYyyy(flightDate),
  };
}
