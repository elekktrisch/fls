import type { FlightReportPageRequest, FlightReportSearchFilter } from '@api/generated/model';

const MAX_PERCENT_DECODE_PASSES = 3;

export function encodeCustomFilter(filter: FlightReportSearchFilter): string {
  return encodeURIComponent(JSON.stringify(filter ?? {}));
}

function percentDecodeOnce(value: string): string | null {
  try {
    return decodeURIComponent(value);
  } catch {
    return null;
  }
}

export function decodeCustomFilter(
  param: string | null | undefined,
): FlightReportSearchFilter | null {
  if (param === null || param === undefined || param === '') return {};
  let candidate = param;
  for (let pass = 0; pass < MAX_PERCENT_DECODE_PASSES; pass++) {
    const trimmed = candidate.trim();
    if (trimmed === '' || trimmed === '{}') return {};
    try {
      const parsed = JSON.parse(trimmed) as unknown;
      if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
      return parsed as FlightReportSearchFilter;
    } catch {
      const decodedOnce = percentDecodeOnce(candidate);
      if (decodedOnce === null || decodedOnce === candidate) return null;
      candidate = decodedOnce;
    }
  }
  return null;
}

export function customFilterRequest(filter: FlightReportSearchFilter): FlightReportPageRequest {
  return { searchFilter: filter };
}

export interface CustomBuilderValue {
  readonly from: string;
  readonly to: string;
  readonly glider: boolean;
  readonly motor: boolean;
  readonly tow: boolean;
  readonly scopeId: string;
}

export function formToFilter(
  value: CustomBuilderValue,
  category: string,
): FlightReportSearchFilter {
  const filter: FlightReportSearchFilter = {
    gliderFlights: value.glider,
    motorFlights: value.motor,
    towFlights: value.tow,
  };
  if (value.from !== '') filter.flightDateFrom = value.from;
  if (value.to !== '') filter.flightDateTo = value.to;
  if (value.scopeId !== '') {
    if (category === 'location') filter.locationId = value.scopeId;
    else filter.flightCrewPersonId = value.scopeId;
  }
  return filter;
}
