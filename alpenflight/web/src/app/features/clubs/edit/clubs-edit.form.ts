import type { ClubCreateRequest, ClubUpdateRequest } from '@api/generated/model';

export interface ClubEditFormValue {
  readonly name: string;
  readonly slug: string;
  readonly clubKey: string;
  readonly publicRegistrationEnabled: boolean;
  readonly countryId: string;
  readonly clubStateId: string;
  readonly discoveryFlightOperatorEmail: string;
  readonly scenicFlightOperatorEmail: string;
  readonly discoveryFlightTypeId: string | null;
  readonly homebaseId: string | null;
}

/**
 * The club PUT is full-replace: a field the payload omits is persisted as
 * cleared. This list is the form's declared coverage of the request DTO —
 * `CLUB_UPDATE_IS_FULLY_COVERED` stops compiling the moment a field is added to
 * `ClubUpdateRequest` without being listed here, and the accompanying spec
 * fails until `buildClubUpdateRequest` actually emits it.
 */
export const CLUB_UPDATE_FIELDS = [
  'name',
  'slug',
  'publicRegistrationEnabled',
  'countryId',
  'clubStateId',
  'discoveryFlightOperatorEmail',
  'scenicFlightOperatorEmail',
  'discoveryFlightTypeId',
  'homebaseId',
] as const satisfies readonly (keyof ClubUpdateRequest)[];

type UncoveredUpdateField = Exclude<keyof ClubUpdateRequest, (typeof CLUB_UPDATE_FIELDS)[number]>;

export const CLUB_UPDATE_IS_FULLY_COVERED: [UncoveredUpdateField] extends [never] ? true : false =
  true;

export function buildClubUpdateRequest(v: ClubEditFormValue): ClubUpdateRequest {
  return {
    name: v.name,
    slug: v.slug,
    publicRegistrationEnabled: v.publicRegistrationEnabled,
    countryId: v.countryId,
    clubStateId: v.clubStateId,
    // Blank stays an empty string rather than dropping the key: the server
    // reads it as "clear the recipients", and a cleared list stays visible on
    // the wire instead of looking like a field the form forgot.
    discoveryFlightOperatorEmail: v.discoveryFlightOperatorEmail.trim(),
    scenicFlightOperatorEmail: v.scenicFlightOperatorEmail.trim(),
    // An empty string would violate the `ft-<uuid>` pattern, so a cleared
    // flight type is expressed by omitting the key.
    ...(v.discoveryFlightTypeId === null ? {} : { discoveryFlightTypeId: v.discoveryFlightTypeId }),
    // Same `loc-<uuid>` pattern constraint; the server reads the absent key as
    // "no homebase", which is the state a club without one is meant to be in.
    ...(v.homebaseId === null ? {} : { homebaseId: v.homebaseId }),
  };
}

/** The create endpoint rejects unknown properties, so the edit-only controls stay off the POST. */
export function buildClubCreateRequest(v: ClubEditFormValue): ClubCreateRequest {
  return {
    name: v.name,
    slug: v.slug,
    clubKey: v.clubKey,
    publicRegistrationEnabled: v.publicRegistrationEnabled,
    countryId: v.countryId,
    clubStateId: v.clubStateId,
  };
}

/**
 * The discovery-flight-day resource and the flight-type / location catalogs are
 * scoped to the caller's own tenant and carry no club id, so all three are only
 * meaningful while the club under edit is the principal's own — a sysadmin
 * editing another club would otherwise manage its own club's days under that
 * club's heading and pick its own club's airfield as that club's homebase.
 */
export function isOwnClub(clubId: string | null, sessionClubId: string | null): boolean {
  return clubId !== null && clubId === sessionClubId;
}
