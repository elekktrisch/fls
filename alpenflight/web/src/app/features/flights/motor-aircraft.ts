import { FlightCreateRequestFlightAircraftType } from '@api/generated/model';
import type { FlightCreateRequestFlightAircraftType as CreateAcType } from '@api/generated/model';

import { needsTowplane } from './edit/flight-form.model';

/**
 * AlpenFlight unifies motor flights ("air movements") into the SAME `/flights`
 * list as glider flights — there is NO separate `/airmovements` screen. Only the
 * legacy `flsweb/` app split them into a `flights/airmovements/` module; the
 * rewrite deliberately does not carry that split forward (J-2 T-36 / S-064
 * reinterpreted: "no legacy-style duplication" = unified into /flights, not a
 * second route).
 *
 * A motor flight is simply a `Flight` created via the same wizard with a MOTOR
 * aircraft selected and no tow. The flight's `flightAircraftType` discriminator
 * is INFERRED from the selected primary aircraft, not from a route variant.
 */

/** The minimal aircraft shape the type inference needs (a list-row projection). */
export interface PrimaryAircraftKind {
  /** A motor engine on board (a pure glider is `false`). */
  readonly hasEngine: boolean;
  /** A dedicated tow plane — never the primary slot for a motor flight. */
  readonly isTowingAircraft: boolean;
}

/**
 * Is the selected primary aircraft a MOTOR aircraft? A non-towing aircraft with
 * an engine is a motor aircraft (gliders have no engine; tow planes fill the tow
 * slot, not the primary slot). When `null`/unknown the flight stays GLIDER.
 */
export function isMotorAircraft(aircraft: PrimaryAircraftKind | null | undefined): boolean {
  return !!aircraft && aircraft.hasEngine && !aircraft.isTowingAircraft;
}

/**
 * The create-request discriminator inferred from the selected primary aircraft:
 * MOTOR for a motor aircraft, GLIDER otherwise.
 */
export function primaryAircraftCreateType(
  aircraft: PrimaryAircraftKind | null | undefined,
): CreateAcType {
  return isMotorAircraft(aircraft)
    ? FlightCreateRequestFlightAircraftType.MOTOR
    : FlightCreateRequestFlightAircraftType.GLIDER;
}

/**
 * Whether the wizard renders the tow step for this primary aircraft + start
 * type. A motor flight never tows; a glider defers to the aerotow start type.
 */
export function flightNeedsTow(
  aircraft: PrimaryAircraftKind | null | undefined,
  startTypeId: string | null | undefined,
): boolean {
  if (isMotorAircraft(aircraft)) {
    return false;
  }
  return needsTowplane(startTypeId);
}
