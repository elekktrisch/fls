import type { FlightDetail, FlightUpdateRequest } from '@api/generated/model';

/**
 * S-062h — 412 conflict-diff resolver (pure logic, Vitest-covered).
 *
 * On a stale `If-Match` PUT the backend answers 412 with the new
 * `serverVersion` but NOT the server's field values (see
 * `FlightsExceptionHandler.handleVersionMismatch` — RFC 7807 carries only
 * `expected` + `serverVersion`). The store therefore re-GETs the current
 * server detail and this module diffs the user's intended update ("mine")
 * against the freshly-loaded server detail ("theirs"), field by field.
 *
 * The result drives an inline keep-mine / keep-theirs dialog. There is NO
 * auto-retry: the diff is presented, the user picks per field, then
 * explicitly resubmits the merged choices (net-new affordance, not legacy
 * parity — legacy was last-write-wins).
 */

/** Fields surfaced in the conflict diff, in display order. */
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

/** One conflicting field: the value the user submitted vs the stored value. */
export interface ConflictField {
  readonly name: ConflictFieldName;
  readonly mine: string | null;
  readonly theirs: string | null;
}

/** A resolved 412 conflict, ready to render in the inline diff dialog. */
export interface FlightConflict {
  readonly flightId: string;
  /** The version the server now holds — the value the resubmit must If-Match. */
  readonly serverVersion: number;
  readonly fields: readonly ConflictField[];
}

function normalize(v: unknown): string | null {
  if (v == null || v === '') {
    return null;
  }
  return String(v);
}

/** Read a diffable field off the server detail, normalised to a string|null. */
function theirsOf(detail: FlightDetail, name: ConflictFieldName): string | null {
  // `comment` is the FlightDetail name for the update request's `comment`;
  // every other field carries the same key on both shapes.
  const raw = (detail as Record<string, unknown>)[name];
  return normalize(raw);
}

function mineOf(mine: FlightUpdateRequest, name: ConflictFieldName): string | null {
  const raw = (mine as Record<string, unknown>)[name];
  return normalize(raw);
}

/**
 * Compute the per-field diff between the user's intended update and the
 * server's current detail. Only fields whose normalised values differ are
 * returned — a field the user left untouched (same as server) is not a
 * conflict and is omitted, so the dialog shows exactly what the user must
 * adjudicate.
 */
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

/**
 * Build the full conflict descriptor the dialog consumes. `serverVersion`
 * comes from the 412 problem body; `theirs` is the re-GET'd server detail.
 */
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

/**
 * Glider sub-form control names that hold each conflict field's value, for
 * the resubmit path: when the user picks "keep theirs" on a field, the host
 * patches the named glider control to the field's `theirs` value before the
 * explicit resubmit. `null` = the field has no direct single-control mirror
 * (handled by the host's snapshot mapping; the diff still shows it).
 */
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

/** Convert a server ISO datetime to the form's HH:mm control value. */
export function timeOfConflictValue(value: string | null): string | null {
  if (!value) {
    return null;
  }
  const t = value.indexOf('T');
  return t < 0 ? value : value.slice(t + 1, t + 6);
}
