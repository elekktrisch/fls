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
    discoveryFlightOperatorEmail: v.discoveryFlightOperatorEmail.trim(),
    scenicFlightOperatorEmail: v.scenicFlightOperatorEmail.trim(),
    ...(v.discoveryFlightTypeId === null ? {} : { discoveryFlightTypeId: v.discoveryFlightTypeId }),
    ...(v.homebaseId === null ? {} : { homebaseId: v.homebaseId }),
  };
}

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

export function isOwnClub(clubId: string | null, sessionClubId: string | null): boolean {
  return clubId !== null && clubId === sessionClubId;
}
