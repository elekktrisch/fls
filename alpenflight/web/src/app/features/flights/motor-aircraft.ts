import { FlightCreateRequestFlightAircraftType } from '@api/generated/model';
import type { FlightCreateRequestFlightAircraftType as CreateAcType } from '@api/generated/model';

import { needsTowplane } from './edit/flight-form.model';

export interface PrimaryAircraftKind {
  readonly hasEngine: boolean;
  readonly isTowingAircraft: boolean;
}

export function isMotorAircraft(aircraft: PrimaryAircraftKind | null | undefined): boolean {
  return !!aircraft && aircraft.hasEngine && !aircraft.isTowingAircraft;
}

export function primaryAircraftCreateType(
  aircraft: PrimaryAircraftKind | null | undefined,
): CreateAcType {
  return isMotorAircraft(aircraft)
    ? FlightCreateRequestFlightAircraftType.MOTOR
    : FlightCreateRequestFlightAircraftType.GLIDER;
}

export function flightNeedsTow(
  aircraft: PrimaryAircraftKind | null | undefined,
  startTypeId: string | null | undefined,
): boolean {
  if (isMotorAircraft(aircraft)) {
    return false;
  }
  return needsTowplane(startTypeId);
}
