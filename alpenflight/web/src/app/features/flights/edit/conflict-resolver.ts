import type { FlightDetail, FlightUpdateRequest } from '@api/generated/model';

const HH_MM_LENGTH = 5;

export const CONFLICT_FIELDS = [
  'aircraftId',
  'flightTypeId',
  'startLocationId',
  'ldgLocationId',
  'startDateTime',
  'ldgDateTime',
  'nrOfLdgs',
  'startTypeId',
  'flightDate',
  'comment',
  'couponNumber',
] as const;

export type ConflictFieldName = (typeof CONFLICT_FIELDS)[number];

export interface ConflictField {
  readonly name: ConflictFieldName;
  readonly mine: string | null;
  readonly theirs: string | null;
}

export interface FlightConflict {
  readonly flightId: string;
  readonly serverVersion: number;
  readonly fields: readonly ConflictField[];
}

function normalize(v: unknown): string | null {
  if (v == null || v === '') {
    return null;
  }
  return String(v);
}

function theirsOf(detail: FlightDetail, name: ConflictFieldName): string | null {
  const raw = (detail as Record<string, unknown>)[name];
  return normalize(raw);
}

function mineOf(mine: FlightUpdateRequest, name: ConflictFieldName): string | null {
  const raw = (mine as Record<string, unknown>)[name];
  return normalize(raw);
}

export function computeFieldDiffs(
  mine: FlightUpdateRequest,
  theirs: FlightDetail,
): readonly ConflictField[] {
  const out: ConflictField[] = [];
  for (const name of CONFLICT_FIELDS) {
    const m = mineOf(mine, name);
    const t = theirsOf(theirs, name);
    if (m !== t) {
      out.push({ name, mine: m, theirs: t });
    }
  }
  return out;
}

export function buildConflict(
  flightId: string,
  serverVersion: number,
  mine: FlightUpdateRequest,
  theirs: FlightDetail,
): FlightConflict {
  return {
    flightId,
    serverVersion,
    fields: computeFieldDiffs(mine, theirs),
  };
}

export const CONFLICT_FIELD_TO_GLIDER_CONTROL: Readonly<Record<ConflictFieldName, string | null>> =
  {
    aircraftId: 'aircraftId',
    flightTypeId: 'flightTypeId',
    startLocationId: 'startLocationId',
    ldgLocationId: 'ldgLocationId',
    startDateTime: 'startTime',
    ldgDateTime: 'ldgTime',
    nrOfLdgs: 'nrOfLdgs',
    startTypeId: null,
    flightDate: null,
    comment: 'flightComment',
    couponNumber: 'couponNumber',
  };

export function timeOfConflictValue(value: string | null): string | null {
  if (!value) {
    return null;
  }
  const timeSeparator = value.indexOf('T');
  return timeSeparator < 0
    ? value
    : value.slice(timeSeparator + 1, timeSeparator + 1 + HH_MM_LENGTH);
}
