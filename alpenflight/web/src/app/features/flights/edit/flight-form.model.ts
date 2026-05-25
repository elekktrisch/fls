import { FormControl, FormGroup, NonNullableFormBuilder } from '@angular/forms';

import { FlightCreateRequestFlightAircraftType } from '@api/generated/model';
import type {
  FlightCreateRequest,
  FlightCrewItem,
  FlightDetail,
  FlightTemplateResponse,
  FlightUpdateRequest,
} from '@api/generated/model';

const EMPTY_GUID = '00000000-0000-0000-0000-000000000000';

const FCT = {
  PILOT: 'pilot',
  CO_PILOT: 'coPilot',
  INSTRUCTOR: 'instructor',
  OBSERVER: 'observer',
  PASSENGER: 'passenger',
  WINCH_OPERATOR: 'winchOperator',
} as const;

type CrewSlot = (typeof FCT)[keyof typeof FCT];

interface CrewSubForm {
  aircraftId: FormControl<string | null>;
  flightTypeId: FormControl<string | null>;
  pilotPersonId: FormControl<string | null>;
  coPilotPersonId: FormControl<string | null>;
  instructorPersonId: FormControl<string | null>;
  observerPersonId: FormControl<string | null>;
  passengerPersonId: FormControl<string | null>;
  winchOperatorPersonId: FormControl<string | null>;
  startLocationId: FormControl<string | null>;
  ldgLocationId: FormControl<string | null>;
  outboundRoute: FormControl<string | null>;
  inboundRoute: FormControl<string | null>;
  startTime: FormControl<string | null>;
  ldgTime: FormControl<string | null>;
  duration: FormControl<string | null>;
  noStartTimeInformation: FormControl<boolean>;
  noLdgTimeInformation: FormControl<boolean>;
  nrOfLdgs: FormControl<number | null>;
  engineStartOperatingCounterInSeconds: FormControl<number | null>;
  engineEndOperatingCounterInSeconds: FormControl<number | null>;
  flightCostBalanceTypeId: FormControl<string | null>;
  invoiceRecipientPersonId: FormControl<string | null>;
  couponNumber: FormControl<string | null>;
  flightComment: FormControl<string | null>;
  isSoloFlight: FormControl<boolean>;
}

export type GliderFlightForm = FormGroup<CrewSubForm>;
export type TowFlightForm = FormGroup<CrewSubForm>;

export interface FlightFormShape {
  flightId: FormControl<string | null>;
  flightDate: FormControl<string | null>;
  startTypeId: FormControl<string | null>;
  canUpdateRecord: FormControl<boolean>;
  canDeleteRecord: FormControl<boolean>;
  glider: GliderFlightForm;
  tow: TowFlightForm;
}

export type FlightForm = FormGroup<FlightFormShape>;

function buildCrewSubForm(fb: NonNullableFormBuilder): FormGroup<CrewSubForm> {
  return fb.group<CrewSubForm>({
    aircraftId: fb.control<string | null>(null),
    flightTypeId: fb.control<string | null>(null),
    pilotPersonId: fb.control<string | null>(null),
    coPilotPersonId: fb.control<string | null>(null),
    instructorPersonId: fb.control<string | null>(null),
    observerPersonId: fb.control<string | null>(null),
    passengerPersonId: fb.control<string | null>(null),
    winchOperatorPersonId: fb.control<string | null>(null),
    startLocationId: fb.control<string | null>(null),
    ldgLocationId: fb.control<string | null>(null),
    outboundRoute: fb.control<string | null>(null),
    inboundRoute: fb.control<string | null>(null),
    startTime: fb.control<string | null>(null),
    ldgTime: fb.control<string | null>(null),
    duration: fb.control<string | null>(null),
    noStartTimeInformation: fb.control<boolean>(false),
    noLdgTimeInformation: fb.control<boolean>(false),
    nrOfLdgs: fb.control<number | null>(null),
    engineStartOperatingCounterInSeconds: fb.control<number | null>(null),
    engineEndOperatingCounterInSeconds: fb.control<number | null>(null),
    flightCostBalanceTypeId: fb.control<string | null>(null),
    invoiceRecipientPersonId: fb.control<string | null>(null),
    couponNumber: fb.control<string | null>(null),
    flightComment: fb.control<string | null>(null),
    isSoloFlight: fb.control<boolean>(false),
  });
}

export function buildFlightForm(fb: NonNullableFormBuilder): FlightForm {
  return fb.group<FlightFormShape>({
    flightId: fb.control<string | null>(null),
    flightDate: fb.control<string | null>(null),
    startTypeId: fb.control<string | null>(null),
    canUpdateRecord: fb.control<boolean>(true),
    canDeleteRecord: fb.control<boolean>(true),
    glider: buildCrewSubForm(fb),
    tow: buildCrewSubForm(fb),
  });
}

import { isAerotow } from './flight-start-types';

/**
 * The legacy `StartType.Towing` semantics. Today: matched against the
 * Aerotow UUID seeded by `V2__identity_and_reference.sql`. Once a real
 * `/start-types` endpoint exists this collapses into a store lookup.
 */
export function needsTowplane(startTypeId: string | null | undefined): boolean {
  return isAerotow(startTypeId);
}

function nullIfEmptyGuid(v: string | null | undefined): string | null {
  if (v == null) return null;
  if (v === EMPTY_GUID) return null;
  return v;
}

function extractCrew(crew: readonly FlightCrewItem[] | undefined, slot: CrewSlot): string | null {
  if (!crew) return null;
  const item = crew.find((c) => c.flightCrewTypeId === slot);
  return item ? nullIfEmptyGuid(item.personId) : null;
}

function packCrew(g: Partial<CrewSnapshot>): FlightCrewItem[] {
  const out: FlightCrewItem[] = [];
  const push = (personId: string | null | undefined, flightCrewTypeId: CrewSlot): void => {
    if (personId) out.push({ personId, flightCrewTypeId });
  };
  push(g.pilotPersonId, FCT.PILOT);
  push(g.coPilotPersonId, FCT.CO_PILOT);
  push(g.instructorPersonId, FCT.INSTRUCTOR);
  push(g.observerPersonId, FCT.OBSERVER);
  push(g.passengerPersonId, FCT.PASSENGER);
  push(g.winchOperatorPersonId, FCT.WINCH_OPERATOR);
  return out;
}

export interface CrewSnapshot {
  aircraftId: string | null;
  flightTypeId: string | null;
  pilotPersonId: string | null;
  coPilotPersonId: string | null;
  instructorPersonId: string | null;
  observerPersonId: string | null;
  passengerPersonId: string | null;
  winchOperatorPersonId: string | null;
  startLocationId: string | null;
  ldgLocationId: string | null;
  outboundRoute: string | null;
  inboundRoute: string | null;
  startTime: string | null;
  ldgTime: string | null;
  duration: string | null;
  noStartTimeInformation: boolean;
  noLdgTimeInformation: boolean;
  nrOfLdgs: number | null;
  engineStartOperatingCounterInSeconds: number | null;
  engineEndOperatingCounterInSeconds: number | null;
  flightCostBalanceTypeId: string | null;
  invoiceRecipientPersonId: string | null;
  couponNumber: string | null;
  flightComment: string | null;
  isSoloFlight: boolean;
}

export interface FlightFormSnapshot {
  flightId: string | null;
  flightDate: string | null;
  startTypeId: string | null;
  canUpdateRecord: boolean;
  canDeleteRecord: boolean;
  glider: CrewSnapshot;
  tow: CrewSnapshot;
}

function detailToCrewSnapshot(d: FlightDetail): CrewSnapshot {
  // Empty-Guid normalization is load-bearing on BOTH edit-load AND copy-template
  // — without it the form silently 400s on save (S-062a known FK gap).
  const crew = d.crew ?? [];
  return {
    aircraftId: nullIfEmptyGuid(d.aircraftId),
    flightTypeId: nullIfEmptyGuid(d.flightTypeId),
    pilotPersonId: extractCrew(crew, FCT.PILOT),
    coPilotPersonId: extractCrew(crew, FCT.CO_PILOT),
    instructorPersonId: extractCrew(crew, FCT.INSTRUCTOR),
    observerPersonId: extractCrew(crew, FCT.OBSERVER),
    passengerPersonId: extractCrew(crew, FCT.PASSENGER),
    winchOperatorPersonId: extractCrew(crew, FCT.WINCH_OPERATOR),
    startLocationId: nullIfEmptyGuid(d.startLocationId),
    ldgLocationId: nullIfEmptyGuid(d.ldgLocationId),
    outboundRoute: d.outboundRoute ?? null,
    inboundRoute: d.inboundRoute ?? null,
    startTime: timeOfIso(d.startDateTime),
    ldgTime: timeOfIso(d.ldgDateTime),
    duration: null,
    noStartTimeInformation: d.noStartTimeInformation,
    noLdgTimeInformation: d.noLdgTimeInformation,
    nrOfLdgs: d.nrOfLdgs ?? null,
    engineStartOperatingCounterInSeconds: d.engineStartOperatingCounterInSeconds ?? null,
    engineEndOperatingCounterInSeconds: d.engineEndOperatingCounterInSeconds ?? null,
    flightCostBalanceTypeId: nullIfEmptyGuid(d.flightCostBalanceTypeId),
    invoiceRecipientPersonId: null,
    couponNumber: d.couponNumber ?? null,
    flightComment: d.comment ?? null,
    isSoloFlight: d.isSoloFlight,
  };
}

function templateToCrewSnapshot(t: FlightTemplateResponse): CrewSnapshot {
  // FlightTemplateResponse is the editable-surface subset — no times / engine
  // counters / comment / coupon. Crew + aircraft + locations + routes only.
  const crew = t.crew ?? [];
  return {
    aircraftId: nullIfEmptyGuid(t.aircraftId),
    flightTypeId: nullIfEmptyGuid(t.flightTypeId),
    pilotPersonId: extractCrew(crew, FCT.PILOT),
    coPilotPersonId: extractCrew(crew, FCT.CO_PILOT),
    instructorPersonId: extractCrew(crew, FCT.INSTRUCTOR),
    observerPersonId: extractCrew(crew, FCT.OBSERVER),
    passengerPersonId: extractCrew(crew, FCT.PASSENGER),
    winchOperatorPersonId: extractCrew(crew, FCT.WINCH_OPERATOR),
    startLocationId: nullIfEmptyGuid(t.startLocationId),
    ldgLocationId: nullIfEmptyGuid(t.ldgLocationId),
    outboundRoute: t.outboundRoute ?? null,
    inboundRoute: t.inboundRoute ?? null,
    startTime: null,
    ldgTime: null,
    duration: null,
    noStartTimeInformation: t.noStartTimeInformation,
    noLdgTimeInformation: t.noLdgTimeInformation,
    nrOfLdgs: t.nrOfLdgs ?? null,
    engineStartOperatingCounterInSeconds: null,
    engineEndOperatingCounterInSeconds: null,
    flightCostBalanceTypeId: nullIfEmptyGuid(t.flightCostBalanceTypeId),
    invoiceRecipientPersonId: null,
    couponNumber: null,
    flightComment: null,
    isSoloFlight: t.isSoloFlight,
  };
}

function blankCrew(): CrewSnapshot {
  return {
    aircraftId: null,
    flightTypeId: null,
    pilotPersonId: null,
    coPilotPersonId: null,
    instructorPersonId: null,
    observerPersonId: null,
    passengerPersonId: null,
    winchOperatorPersonId: null,
    startLocationId: null,
    ldgLocationId: null,
    outboundRoute: null,
    inboundRoute: null,
    startTime: null,
    ldgTime: null,
    duration: null,
    noStartTimeInformation: false,
    noLdgTimeInformation: false,
    nrOfLdgs: null,
    engineStartOperatingCounterInSeconds: null,
    engineEndOperatingCounterInSeconds: null,
    flightCostBalanceTypeId: null,
    invoiceRecipientPersonId: null,
    couponNumber: null,
    flightComment: null,
    isSoloFlight: false,
  };
}

function timeOfIso(iso: string | undefined): string | null {
  if (!iso) return null;
  // Server returns ISO datetimes; the form stores HH:mm only.
  const t = iso.indexOf('T');
  if (t < 0) return null;
  return iso.slice(t + 1, t + 6);
}

function isoOfDateAndTime(date: string | null, time: string | null): string | undefined {
  if (!date || !time) return undefined;
  return `${date}T${time}:00Z`;
}

/**
 * Build a form snapshot from a server `FlightDetail` (edit load) — paired-row aware:
 * when the glider detail has `towFlightId` set, the caller passes the resolved tow
 * detail. Otherwise the tow sub-group is blank.
 */
export function flightDetailToFormSnapshot(
  glider: FlightDetail,
  tow: FlightDetail | undefined,
): FlightFormSnapshot {
  return {
    flightId: glider.id,
    flightDate: glider.flightDate ?? null,
    startTypeId: glider.startTypeId ?? null,
    canUpdateRecord: true,
    canDeleteRecord: true,
    glider: detailToCrewSnapshot(glider),
    tow: tow ? detailToCrewSnapshot(tow) : blankCrew(),
  };
}

export function templateToFormSnapshot(template: FlightTemplateResponse): FlightFormSnapshot {
  return {
    flightId: null,
    flightDate: template.flightDate ?? null,
    startTypeId: template.startTypeId ?? null,
    canUpdateRecord: true,
    canDeleteRecord: true,
    glider: templateToCrewSnapshot(template),
    tow: blankCrew(),
  };
}

/**
 * Submit-time mirror: glider→tow sync of startDateTime / startLocationId / outboundRoute
 * BEFORE the tow-discard check (parity with `FlightsController.js:370-372`).
 *
 * Returns the pair of create requests. The caller (FlightStore.savePair) orchestrates
 * the 3-call paired-create chain (POST glider → POST tow → PUT link).
 */
export function snapshotToCreateRequests(s: FlightFormSnapshot): {
  glider: FlightCreateRequest;
  tow: FlightCreateRequest | undefined;
} {
  const glider = subFormToCreate(s.glider, s.flightDate, s.startTypeId, 'glider');
  const needsTow = needsTowplane(s.startTypeId) && !!s.tow.aircraftId;
  if (!needsTow) {
    return { glider, tow: undefined };
  }
  // Glider→tow sync at submit (legacy parity); the UI mirrors are display-only.
  const synced: CrewSnapshot = {
    ...s.tow,
    startLocationId: s.glider.startLocationId,
    startTime: s.glider.startTime,
    outboundRoute: s.glider.outboundRoute,
  };
  return {
    glider,
    tow: subFormToCreate(synced, s.flightDate, s.startTypeId, 'tow'),
  };
}

export function snapshotToUpdateRequest(
  s: FlightFormSnapshot,
  side: 'glider' | 'tow',
  towFlightId?: string,
): FlightUpdateRequest {
  const sub = side === 'glider' ? s.glider : s.tow;
  const synced: CrewSnapshot =
    side === 'tow'
      ? {
          ...sub,
          startLocationId: s.glider.startLocationId,
          startTime: s.glider.startTime,
          outboundRoute: s.glider.outboundRoute,
        }
      : sub;
  const req = subFormToUpdate(synced, s.flightDate);
  if (side === 'glider' && towFlightId) {
    req.towFlightId = towFlightId;
  }
  return req;
}

function subFormToCreate(
  c: CrewSnapshot,
  flightDate: string | null,
  startTypeId: string | null,
  side: 'glider' | 'tow',
): FlightCreateRequest {
  if (!c.aircraftId) {
    throw new Error(`subFormToCreate: ${side} aircraft is required to submit`);
  }
  const req: FlightCreateRequest = {
    flightAircraftType:
      side === 'glider'
        ? FlightCreateRequestFlightAircraftType.GLIDER
        : FlightCreateRequestFlightAircraftType.TOW,
    aircraftId: c.aircraftId,
    crew: packCrew(c),
  };
  if (flightDate) req.flightDate = flightDate;
  if (startTypeId) req.startTypeId = startTypeId;
  if (c.flightTypeId) req.flightTypeId = c.flightTypeId;
  if (c.startLocationId) req.startLocationId = c.startLocationId;
  if (c.ldgLocationId) req.ldgLocationId = c.ldgLocationId;
  if (c.outboundRoute) req.outboundRoute = c.outboundRoute;
  if (c.inboundRoute) req.inboundRoute = c.inboundRoute;
  const start = isoOfDateAndTime(flightDate, c.startTime);
  if (start) req.startDateTime = start;
  const ldg = isoOfDateAndTime(flightDate, c.ldgTime);
  if (ldg) req.ldgDateTime = ldg;
  req.noStartTimeInformation = c.noStartTimeInformation;
  req.noLdgTimeInformation = c.noLdgTimeInformation;
  if (c.nrOfLdgs != null) req.nrOfLdgs = c.nrOfLdgs;
  if (c.engineStartOperatingCounterInSeconds != null) {
    req.engineStartOperatingCounterInSeconds = c.engineStartOperatingCounterInSeconds;
  }
  if (c.engineEndOperatingCounterInSeconds != null) {
    req.engineEndOperatingCounterInSeconds = c.engineEndOperatingCounterInSeconds;
  }
  if (c.flightCostBalanceTypeId) req.flightCostBalanceTypeId = c.flightCostBalanceTypeId;
  if (c.couponNumber) req.couponNumber = c.couponNumber;
  if (c.flightComment) req.comment = c.flightComment;
  req.isSoloFlight = c.isSoloFlight;
  return req;
}

function subFormToUpdate(c: CrewSnapshot, flightDate: string | null): FlightUpdateRequest {
  if (!c.aircraftId) {
    throw new Error('subFormToUpdate: aircraft is required');
  }
  // FlightUpdateRequest is the same surface as FlightCreateRequest minus the
  // discriminator. Reuse the create mapper and strip the discriminator field.
  const created = subFormToCreate(c, flightDate, null, 'glider');
  const { flightAircraftType: _drop, ...rest } = created;
  void _drop;
  return rest;
}
