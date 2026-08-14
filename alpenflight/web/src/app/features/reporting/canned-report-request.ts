import type { FlightReportPageRequest } from '@api/generated/model';

import type { CannedType } from './canned-report';
import { cannedReportSpec, categoryOf } from './canned-report';

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

  return { searchFilter };
}
