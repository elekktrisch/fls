import type { FlightReportPageRequest, FlightReportSearchFilter } from '@api/generated/model';

/**
 * Custom-report filter codec — the round-trip between the builder form and the
 * route's `:filter` segment (legacy `flightreport-custom-configuration.html` →
 * `FlightReportFilterCriteria`).
 *
 * The custom builder (`custom/:category/:filter/edit`) encodes the picked
 * filter into the `:filter` route segment and navigates to
 * `custom/:category/:filter/apply`; the results view decodes it back. Keeping
 * the codec a pure, framework-free pair (encode/decode) means the
 * filter-round-trips-through-the-route-param AC is unit-testable without a
 * TestBed (web CLAUDE.md §8) and the builder + results page share ONE source
 * of truth for the wire shape — no duplicated JSON-shape logic.
 *
 * Shape: the filter is JSON-serialised then `encodeURIComponent`'d so it nests
 * safely as a single path segment (slashes/braces/quotes escaped). An empty
 * filter (the `edit` route's default) encodes to `%7B%7D` (`{}`) — matching the
 * T-01 spec's `…/custom/location/%7B%7D/edit` entry URL.
 */

/** Encode a search filter into the URL-safe `:filter` route segment. */
export function encodeCustomFilter(filter: FlightReportSearchFilter): string {
  return encodeURIComponent(JSON.stringify(filter ?? {}));
}

/**
 * Decode the `:filter` route segment back into a search filter. Returns an
 * empty filter `{}` for the empty/`{}` default; `null` only when the segment is
 * present but not valid JSON (a malformed/hand-edited URL — the results page
 * treats that as "no custom filter").
 *
 * Encoding-tolerant: `ActivatedRoute.paramMap` already percent-decodes the
 * segment ONCE, so the value handed in may be raw JSON (`{"…"}`) or still
 * percent-encoded once (`%7B%22…`). We try to parse, and on failure
 * percent-decode and retry (up to a couple of passes) — so the codec works
 * whether the navigation single- or double-encoded the segment.
 */
export function decodeCustomFilter(
  param: string | null | undefined,
): FlightReportSearchFilter | null {
  if (param === null || param === undefined || param === '') return {};
  let candidate = param;
  for (let pass = 0; pass < 3; pass++) {
    const trimmed = candidate.trim();
    if (trimmed === '' || trimmed === '{}') return {};
    try {
      const parsed = JSON.parse(trimmed) as unknown;
      if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
      return parsed as FlightReportSearchFilter;
    } catch {
      // Not JSON yet — try one more percent-decode pass.
    }
    let next: string;
    try {
      next = decodeURIComponent(candidate);
    } catch {
      return null;
    }
    if (next === candidate) return null; // No further decode possible.
    candidate = next;
  }
  return null;
}

/**
 * Wrap a decoded custom filter into the `{ searchFilter }` page request the
 * {@link import('./report.store').ReportStore} loads. The custom builder honours
 * only the FlightDuration sort key server-side (oracle § Pagination), so — like
 * the canned request — `sorting` is left unset → the backend default sort.
 */
export function customFilterRequest(filter: FlightReportSearchFilter): FlightReportPageRequest {
  return { searchFilter: filter };
}

/** Raw value of the custom-builder form (the `formToFilter` input shape). */
export interface CustomBuilderValue {
  readonly from: string;
  readonly to: string;
  readonly glider: boolean;
  readonly motor: boolean;
  readonly tow: boolean;
  readonly scopeId: string;
}

/**
 * Pure custom-builder form → search-filter builder. The whole mapping for this
 * filter form — deliberately one small function (LOW-CRAP rider): the date
 * range, the three type flags, and the conditional scope id keyed by category.
 * An empty From/To or scope id is omitted from the filter (the backend defaults
 * / tenant-scopes the missing dimension). Pure (plain value in, no FormGroup) so
 * it unit-tests without a TestBed (web CLAUDE.md §8).
 */
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
