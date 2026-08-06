import { type APIRequestContext } from '@playwright/test';

import { seedFlightMasterdata } from './flight-parity-fixture';
import { freshTestUser } from './test-user';

/**
 * The crew and the flight the `daily-report` job must act on, seeded through
 * the production create APIs (ADR 0027 §3) into the caller's own tenant.
 *
 * <h2>Not in the shared clean seed</h2>
 *
 * A person carrying `receiveFlightReports` in the Flyway seed would hand every
 * other journey's gate an outbound message on any run that triggers the report
 * pass. The opt-in is minted per call instead, so only the spec that asserts
 * the mail causes one.
 *
 * <h2>Two people on one flight</h2>
 *
 * "A Flugrapport arrived" is satisfied by any report mail from any club. The
 * opt-out crew member is the counter-example that makes the assertion
 * discriminate: both flew the SAME flight, both hold a reported crew role, and
 * only `PersonClub.receiveFlightReports` separates them — so a pass that
 * ignored the flag would mail both, and the spec sees it.
 *
 * <h2>Attribution</h2>
 *
 * Both addresses are run-unique, the member's rendered name carries the same
 * run-unique tail, and the flight is flown on a freshly-registered glider — so
 * the mail is matched on THIS run's recipient AND on content naming THIS
 * flight, never on the mere existence of a report mail.
 */

/** V3-seeded flight-crew-type PKs — the raw UUIDs `FlightCrewItem` carries. */
const CREW_TYPE_PILOT_OR_STUDENT = '019e2e15-2c00-76b0-8000-0000000036b0';
const CREW_TYPE_FLIGHT_INSTRUCTOR = '019e2e15-2c00-76b2-8000-0000000036b2';

/** V2-seeded WINCH start type. */
const START_TYPE_WINCH = '019e2e15-2c00-7fa0-8000-000000000fa0';

/**
 * Yesterday, not today: the flight carries wall-clock start / landing times,
 * and a gate running before 09:00Z would otherwise seed a flight that has not
 * happened yet. One day back is still inside the job's report window
 * (`DailyReportJob.REPORT_WINDOW_DAYS`).
 */
const FLIGHT_DAYS_AGO = 1;

const FIRSTNAME = 'Rapport';

export interface DailyReportCrewMember {
  /** `pn-<uuid>` — the form `FlightCrewItem.personId` carries. */
  personId: string;
  /** Run-unique communication address; the report mail's recipient. */
  email: string;
  /** `First Last` — the name the report template greets, run-unique by its tail. */
  displayName: string;
}

export interface DailyReportSeed {
  /** `PersonClub.receiveFlightReports = true` — the pilot the pass must mail. */
  optedIn: DailyReportCrewMember;
  /** Same flight, instructor seat, opted OUT — the one the pass must skip. */
  optedOut: DailyReportCrewMember;
  /** The glider the reported flight was flown on, as the mail's row names it. */
  immatriculation: string;
  /** The flight day in the `dd.MM.yyyy` form the report template renders. */
  renderedFlightDate: string;
}

interface PersonCreated {
  id: string;
  memberships?: { receiveFlightReports: boolean }[];
}

function daysAgo(n: number): string {
  const day = new Date();
  day.setUTCDate(day.getUTCDate() - n);
  return day.toISOString().slice(0, 10);
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
      firstname: FIRSTNAME,
      lastname,
      emailPrivate: email,
      // `Person.emailForCommunication()` prefers the business address only when
      // this is set, and the job mails whatever it returns.
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
  // The opt-in / opt-out pair IS the assertion; a flag that silently failed to
  // store must fail here naming the field, not later as a Mailpit timeout.
  if (!stored || stored.receiveFlightReports !== receiveFlightReports) {
    throw new Error(
      `the seeded member's club membership stored receiveFlightReports=` +
        `${stored?.receiveFlightReports} instead of ${receiveFlightReports}`,
    );
  }
  return { personId: person.id, email, displayName: `${FIRSTNAME} ${lastname}` };
}

/**
 * Seed the opted-in pilot, the opted-out instructor, and the single unreported
 * glider flight they both flew, in the tenant `bearer` resolves to. The
 * masterdata closure (location / flight type / aircraft) is the J-2 seeder's,
 * so the glider's immatriculation is fresh per call.
 */
export async function seedDailyReportCrew(
  api: APIRequestContext,
  bearer: string,
): Promise<DailyReportSeed> {
  const masterdata = await seedFlightMasterdata(api, bearer);
  const optedIn = await createMember(api, bearer, 'Optin', true);
  const optedOut = await createMember(api, bearer, 'Optout', false);

  const flightDate = daysAgo(FLIGHT_DAYS_AGO);
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
