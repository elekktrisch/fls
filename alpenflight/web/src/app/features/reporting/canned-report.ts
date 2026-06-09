/**
 * Canned flight-report definitions + derived date-math.
 *
 * Reproduces the legacy `FlightReportsController.js:118-364` date math EXACTLY,
 * including the INTENDED off-by-one windows (last-7-days = today−7 … today = 8
 * inclusive days; same for 30-days / 12-months / 24-months). The journey's parity
 * oracle (J-7-flight-reports.md § "PRESERVE exactly") pins this as parity-critical,
 * so the math lives in a pure, unit-tested function — never re-derived inline.
 *
 * Flight-type defaults follow the JOURNEY NOTE (§ Spec must assert), NOT the raw
 * legacy controller: GliderFlights=true, MotorFlights=true, TowFlights=false.
 * (Legacy actually sets TowFlights=true for every canned report; the journey
 * corrects this to "Tow off" per the recorded AC — see J-7 § Parity decisions.)
 *
 * `:type` route segment → derived `{ from, to }` (both inclusive, `YYYY-MM-DD`)
 * + the type flags. The page store binds `flightCrewPersonId` (person category)
 * or `locationId` (location category) on top of this.
 */

export type ReportCategory = 'person' | 'location';

export interface CannedReportFlags {
  readonly gliderFlights: boolean;
  readonly motorFlights: boolean;
  readonly towFlights: boolean;
}

export interface CannedDateRange {
  /** Inclusive lower bound, `YYYY-MM-DD`. */
  readonly from: string;
  /** Inclusive upper bound, `YYYY-MM-DD`. */
  readonly to: string;
}

export interface CannedReportSpec extends CannedDateRange, CannedReportFlags {}

/** Default flight-type flags for every canned report (journey note). */
export const DEFAULT_CANNED_FLAGS: CannedReportFlags = {
  gliderFlights: true,
  motorFlights: true,
  towFlights: false,
};

/** Canned person-report `:type`s (the picker's person-category tiles). */
export const PERSON_CANNED_TYPES = [
  'my-flights-today',
  'my-flights-yesterday',
  'my-flights-last-7-days',
  'my-flights-last-30-days',
  'my-flights-last-12-months',
  'my-flights-last-24-months',
  'my-flights-this-year',
  'my-flights-previous-year',
] as const;

/** Canned location-report `:type`s (the picker's location-category tiles). */
export const LOCATION_CANNED_TYPES = [
  'location-flights-today',
  'location-flights-yesterday',
  'location-flights-this-year',
  'location-flights-previous-year',
] as const;

export type PersonCannedType = (typeof PERSON_CANNED_TYPES)[number];
export type LocationCannedType = (typeof LOCATION_CANNED_TYPES)[number];
export type CannedType = PersonCannedType | LocationCannedType;

/** Maps a canned `:type` to its window-kind; person/location share the windows. */
type WindowKind =
  | 'today'
  | 'yesterday'
  | 'last-7-days'
  | 'last-30-days'
  | 'last-12-months'
  | 'last-24-months'
  | 'this-year'
  | 'previous-year';

const TYPE_WINDOW: Readonly<Record<CannedType, WindowKind>> = {
  'my-flights-today': 'today',
  'my-flights-yesterday': 'yesterday',
  'my-flights-last-7-days': 'last-7-days',
  'my-flights-last-30-days': 'last-30-days',
  'my-flights-last-12-months': 'last-12-months',
  'my-flights-last-24-months': 'last-24-months',
  'my-flights-this-year': 'this-year',
  'my-flights-previous-year': 'previous-year',
  'location-flights-today': 'today',
  'location-flights-yesterday': 'yesterday',
  'location-flights-this-year': 'this-year',
  'location-flights-previous-year': 'previous-year',
};

export function isCannedType(type: string): type is CannedType {
  return type in TYPE_WINDOW;
}

export function categoryOf(type: CannedType): ReportCategory {
  return type.startsWith('location-') ? 'location' : 'person';
}

function toIso(d: Date): string {
  const yyyy = d.getFullYear();
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${yyyy}-${mm}-${dd}`;
}

/**
 * Computes the derived `{ from, to }` for a canned window-kind, mirroring
 * `moment()` local-day arithmetic. `today` is injectable so the util stays pure
 * and unit-testable against a fixed clock.
 *
 * Off-by-one is INTENTIONAL (legacy parity): `last-7-days` subtracts 7 days from
 * `today` for `from` and keeps `today` for `to` → an 8-day inclusive window. The
 * month/year-relative windows use the same calendar arithmetic as `moment().add`.
 */
export function cannedDateRange(window: WindowKind, today = new Date()): CannedDateRange {
  // Normalise to local midnight so the arithmetic never straddles a DST hour.
  const base = new Date(today.getFullYear(), today.getMonth(), today.getDate());
  const todayIso = toIso(base);

  switch (window) {
    case 'today':
      return { from: todayIso, to: todayIso };
    case 'yesterday': {
      const y = new Date(base);
      y.setDate(base.getDate() - 1);
      const yIso = toIso(y);
      return { from: yIso, to: yIso };
    }
    case 'last-7-days': {
      const from = new Date(base);
      from.setDate(base.getDate() - 7);
      return { from: toIso(from), to: todayIso };
    }
    case 'last-30-days': {
      const from = new Date(base);
      from.setDate(base.getDate() - 30);
      return { from: toIso(from), to: todayIso };
    }
    case 'last-12-months': {
      const from = new Date(base);
      from.setMonth(base.getMonth() - 12);
      return { from: toIso(from), to: todayIso };
    }
    case 'last-24-months': {
      const from = new Date(base);
      from.setMonth(base.getMonth() - 24);
      return { from: toIso(from), to: todayIso };
    }
    case 'this-year':
      return { from: toIso(new Date(base.getFullYear(), 0, 1)), to: todayIso };
    case 'previous-year': {
      const prev = base.getFullYear() - 1;
      return { from: toIso(new Date(prev, 0, 1)), to: toIso(new Date(prev, 11, 31)) };
    }
    default: {
      // Exhaustiveness guard — a new WindowKind without a branch is a compile error.
      const never: never = window;
      throw new Error(`Unhandled canned window: ${String(never)}`);
    }
  }
}

/**
 * Full canned spec for a `:type`: derived inclusive date range + default
 * flight-type flags. Throws on an unknown type (caller validates with
 * {@link isCannedType} first — the route resolves a 404/empty placeholder).
 */
export function cannedReportSpec(type: CannedType, today = new Date()): CannedReportSpec {
  const range = cannedDateRange(TYPE_WINDOW[type], today);
  return { ...range, ...DEFAULT_CANNED_FLAGS };
}
