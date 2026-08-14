export type ReportCategory = 'person' | 'location';

export interface CannedReportFlags {
  readonly gliderFlights: boolean;
  readonly motorFlights: boolean;
  readonly towFlights: boolean;
}

export interface CannedDateRange {
  readonly from: string;
  readonly to: string;
}

export interface CannedReportSpec extends CannedDateRange, CannedReportFlags {}

export const DEFAULT_CANNED_FLAGS: CannedReportFlags = {
  gliderFlights: true,
  motorFlights: true,
  towFlights: false,
};

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

export const LOCATION_CANNED_TYPES = [
  'location-flights-today',
  'location-flights-yesterday',
  'location-flights-this-year',
  'location-flights-previous-year',
] as const;

export type PersonCannedType = (typeof PERSON_CANNED_TYPES)[number];
export type LocationCannedType = (typeof LOCATION_CANNED_TYPES)[number];
export type CannedType = PersonCannedType | LocationCannedType;

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

export function cannedDateRange(window: WindowKind, today = new Date()): CannedDateRange {
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
      const never: never = window;
      throw new Error(`Unhandled canned window: ${String(never)}`);
    }
  }
}

export function cannedReportSpec(type: CannedType, today = new Date()): CannedReportSpec {
  const range = cannedDateRange(TYPE_WINDOW[type], today);
  return { ...range, ...DEFAULT_CANNED_FLAGS };
}
