import type { FlightReportPageRequest } from '@api/generated/model';

import type { CannedType } from './canned-report';
import { cannedReportSpec, categoryOf } from './canned-report';

/**
 * Composes the `{ sorting, searchFilter }` page-request for a canned `:type`,
 * binding the principal-derived id the category needs:
 *
 *   • person reports   → `flightCrewPersonId` = the current user's PersonId
 *     (oracle § 1 — `FlightCrewPersonId = own PersonId`). The picker/results
 *     page reads it from `SessionStore.authenticatedUser().personId`.
 *   • location reports → `locationId` = the user's club homebase location
 *     (oracle § 1 — `LocationId = club.HomebaseId`).
 *
 * Both ids are passed in (the composer stays pure + unit-testable); the caller
 * sources them from the session: `SessionStore.authenticatedUser().personId`
 * and `.homebaseLocationId` (both populated by `/me` — S-165 + J-7 T-09b). A
 * null id is OMITTED from the filter — the backend is tenant-scoped (ADR 0008)
 * so a report with no person/location id still returns only the caller's club,
 * just unfiltered by that dimension.
 */
export function cannedReportRequest(
  type: CannedType,
  ids: { personId: string | null; homebaseLocationId: string | null },
  today = new Date(),
): FlightReportPageRequest {
  const spec = cannedReportSpec(type, today);
  const category = categoryOf(type);

  const searchFilter: NonNullable<FlightReportPageRequest['searchFilter']> = {
    flightDateFrom: spec.from,
    flightDateTo: spec.to,
    gliderFlights: spec.gliderFlights,
    motorFlights: spec.motorFlights,
    towFlights: spec.towFlights,
  };

  if (category === 'person' && ids.personId) {
    searchFilter.flightCrewPersonId = ids.personId;
  }
  if (category === 'location' && ids.homebaseLocationId) {
    searchFilter.locationId = ids.homebaseLocationId;
  }

  // Canned reports sort by FlightDate desc legacy-side; the backend's only
  // honoured sort key is FlightDuration (oracle § Pagination), so the canned
  // request leaves `sorting` unset → the backend default sort applies.
  return { searchFilter };
}
