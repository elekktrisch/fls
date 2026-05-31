/**
 * Canonical UUIDs for the `flight_crew_type` seed table (`V3` migration,
 * mirrored on the server in `FlightCrewTypeIds.java`).
 *
 * The form-model maps each crew slot to one of these IDs at submit; the
 * server expects UUID values for `FlightCrewItem.flightCrewTypeId`. A
 * future `/flight-crew-types` reference-data endpoint would replace this
 * static map.
 */
export const FLIGHT_CREW_TYPE_PILOT = '019e2e15-2c00-76b0-8000-0000000036b0';
export const FLIGHT_CREW_TYPE_CO_PILOT = '019e2e15-2c00-76b1-8000-0000000036b1';
export const FLIGHT_CREW_TYPE_FLIGHT_INSTRUCTOR = '019e2e15-2c00-76b2-8000-0000000036b2';
export const FLIGHT_CREW_TYPE_PASSENGER = '019e2e15-2c00-76b3-8000-0000000036b3';
export const FLIGHT_CREW_TYPE_WINCH_OPERATOR = '019e2e15-2c00-76b4-8000-0000000036b4';
export const FLIGHT_CREW_TYPE_OBSERVER = '019e2e15-2c00-76b5-8000-0000000036b5';
export const FLIGHT_CREW_TYPE_FLIGHT_COST_INVOICE_RECIPIENT =
  '019e2e15-2c00-76b6-8000-0000000036b6';
