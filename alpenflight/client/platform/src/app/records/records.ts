import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { ListToolbar, type ToolbarChip } from '../shared/list-toolbar/list-toolbar';
import { RecordList } from '../shared/record-list/record-list';
import type { RecordItemData, RecordMarkerTone } from '../shared/record-item/record-item';
import type { SortField, SortState } from '../shared/sort-control/sort-control';

// Static demo data — no backend call this story. Story 1.6 replaces DEMO_FLIGHTS with
// httpResource against the aircraft endpoint; the public API of ListToolbar/RecordList does not
// change. Shaped as a small logbook, mirroring DESIGN.md's own worked record-item examples, so
// every marker tone, the live metric, the absent metric, and the settled state all have a real
// row to render.
interface DemoFlight {
  readonly id: string;
  readonly dateIso: string;
  readonly dateLabel: string;
  readonly registration: string;
  readonly crew: string;
  readonly blockStart: string | null;
  readonly route: string;
  readonly durationMinutes: number | null;
  readonly durationLabel: string | null;
  readonly marker: { readonly label: string; readonly tone: RecordMarkerTone };
  readonly settled: boolean;
  readonly live: boolean;
}

const DEMO_FLIGHTS: readonly DemoFlight[] = [
  {
    id: 'f1',
    dateIso: '2026-08-21',
    dateLabel: '21.08.2026',
    registration: 'HB-3215',
    crew: 'S. AEBI',
    blockStart: 'OFF 10:24',
    route: 'LSZF → LSZF',
    durationMinutes: 42,
    durationLabel: '00:42',
    marker: { label: 'AIRBORNE', tone: 'airborne' },
    settled: false,
    live: true,
  },
  {
    id: 'f2',
    dateIso: '2026-08-21',
    dateLabel: '21.08.2026',
    registration: 'HB-2101',
    crew: 'M. WEBER',
    blockStart: null,
    route: 'LSZF → LSZF',
    durationMinutes: null,
    durationLabel: null,
    marker: { label: 'OPEN', tone: 'open' },
    settled: false,
    live: false,
  },
  {
    id: 'f3',
    dateIso: '2026-08-21',
    dateLabel: '21.08.2026',
    registration: 'HB-1944',
    crew: 'L. FREI',
    blockStart: 'OFF 11:02',
    route: 'LSZF → LSZF',
    durationMinutes: 78,
    durationLabel: '01:18',
    marker: { label: 'LOCKED', tone: 'locked' },
    settled: false,
    live: false,
  },
  {
    id: 'f4',
    dateIso: '2026-08-21',
    dateLabel: '21.08.2026',
    registration: 'HB-3215',
    crew: 'D. ROTH',
    blockStart: 'OFF 09:10',
    route: 'LSZF → LSTO',
    durationMinutes: 35,
    durationLabel: '00:35',
    marker: { label: 'UNSENT', tone: 'unsent' },
    settled: false,
    live: false,
  },
  {
    id: 'f5',
    dateIso: '2026-08-14',
    dateLabel: '14.08.2026',
    registration: 'HB-2101',
    crew: 'S. AEBI',
    blockStart: 'OFF 09:45',
    route: 'LSZF → LSZF',
    durationMinutes: 52,
    durationLabel: '00:52',
    marker: { label: 'BILLED', tone: 'billed' },
    settled: true,
    live: false,
  },
  {
    id: 'f6',
    dateIso: '2026-08-14',
    dateLabel: '14.08.2026',
    registration: 'HB-1944',
    crew: 'M. WEBER',
    blockStart: 'OFF 14:20',
    route: 'LSZF → LSZF',
    durationMinutes: 65,
    durationLabel: '01:05',
    marker: { label: 'BILLED', tone: 'billed' },
    settled: true,
    live: false,
  },
  {
    id: 'f7',
    dateIso: '2026-08-14',
    dateLabel: '14.08.2026',
    registration: 'HB-EAB',
    crew: 'L. FREI',
    blockStart: 'OFF 15:00',
    route: 'LSZF → LSTO',
    durationMinutes: 48,
    durationLabel: '00:48',
    marker: { label: 'BILLED', tone: 'billed' },
    settled: true,
    live: false,
  },
];

const GROUP_LABEL_BY_ID = new Map(DEMO_FLIGHTS.map((flight) => [flight.id, flight.dateLabel]));

function matchesQuery(flight: DemoFlight, query: string): boolean {
  const trimmed = query.trim().toLowerCase();
  if (!trimmed) {
    return true;
  }
  const haystack = [
    flight.registration,
    flight.crew,
    flight.blockStart,
    flight.route,
    flight.durationLabel,
  ]
    .filter((part): part is string => part !== null)
    .join(' ')
    .toLowerCase();
  return haystack.includes(trimmed);
}

const CHIP_PREDICATES: Record<string, (flight: DemoFlight) => boolean> = {
  airborne: (flight) => flight.marker.tone === 'airborne',
  settled: (flight) => flight.settled,
  unsent: (flight) => flight.marker.tone === 'unsent',
};

// Direction lives inside each comparator, not as an outer multiplier, so an absent value's
// placement never inverts with the sort direction — a null duration always sorts last, whether
// ascending or descending.
function compareByDate(a: DemoFlight, b: DemoFlight, direction: 1 | -1): number {
  return (
    direction * (a.dateIso.localeCompare(b.dateIso) || a.registration.localeCompare(b.registration))
  );
}

function compareByDuration(a: DemoFlight, b: DemoFlight, direction: 1 | -1): number {
  if (a.durationMinutes === b.durationMinutes) {
    return 0;
  }
  if (a.durationMinutes === null) {
    return 1;
  }
  if (b.durationMinutes === null) {
    return -1;
  }
  return direction * (a.durationMinutes - b.durationMinutes);
}

const SORT_COMPARATORS: Record<
  string,
  (a: DemoFlight, b: DemoFlight, direction: 1 | -1) => number
> = {
  date: compareByDate,
  duration: compareByDuration,
};

@Component({
  selector: 'app-records',
  imports: [ListToolbar, RecordList],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './records.html',
})
export class Records {
  protected readonly chips: readonly ToolbarChip[] = [
    { key: 'airborne', label: 'Airborne' },
    { key: 'settled', label: 'Settled' },
    { key: 'unsent', label: 'Unsent' },
  ];

  protected readonly sortFields: readonly SortField[] = [
    { key: 'date', label: 'Date' },
    { key: 'duration', label: 'Duration' },
  ];

  protected readonly query = signal('');
  protected readonly activeChipKeys = signal<ReadonlySet<string>>(new Set());
  protected readonly sort = signal<SortState>({ key: 'date', direction: 'desc' });

  private readonly filteredFlights = computed(() => {
    const query = this.query();
    const activeKeys = this.activeChipKeys();
    return DEMO_FLIGHTS.filter(
      (flight) =>
        matchesQuery(flight, query) &&
        Array.from(activeKeys).every((key) => CHIP_PREDICATES[key](flight)),
    );
  });

  private readonly sortedFlights = computed(() => {
    const comparator = SORT_COMPARATORS[this.sort().key];
    const direction = this.sort().direction === 'asc' ? 1 : -1;
    return [...this.filteredFlights()].sort((a, b) => comparator(a, b, direction));
  });

  protected readonly records = computed<readonly RecordItemData[]>(() =>
    this.sortedFlights().map((flight) => ({
      id: flight.id,
      identity: flight.registration,
      meta: [flight.crew, flight.blockStart, flight.route].filter(
        (part): part is string => part !== null,
      ),
      metric: flight.durationLabel,
      metricLive: flight.live,
      marker: flight.marker,
      settled: flight.settled,
    })),
  );

  // RecordList's groups preserve the order each group name first appears in the (already-sorted)
  // items it's given (see its own doc comment) -- grouping by date only makes sense while date is
  // also the active sort, since group order otherwise tracks wherever the sorted array happens to
  // put each date's first item, not chronological order. Sorting by any other field renders one
  // flat, fully-ordered list instead.
  protected readonly groupBy = computed<((record: RecordItemData) => string) | undefined>(() =>
    this.sort().key === 'date' ? (record) => GROUP_LABEL_BY_ID.get(record.id) ?? '' : undefined,
  );
}
