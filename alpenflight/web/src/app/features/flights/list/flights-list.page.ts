import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NzDropDownModule } from 'ng-zorro-antd/dropdown';
import { NzEmptyModule } from 'ng-zorro-antd/empty';
import { NzSpinModule } from 'ng-zorro-antd/spin';

import { AircraftStore } from '@features/aircraft/aircraft.store';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfIconComponent } from '@ui/atoms/af-icon';
import { AfSelectComponent, type AfSelectOption } from '@ui/atoms/af-select';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfDatePickerComponent, type DateValue } from '@ui/organisms/af-date-picker';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import {
  FlightListItemAirState,
  FlightListItemFlightAircraftType,
  FlightListItemProcessState,
} from '@api/generated/model';
import type {
  FlightListItem,
  FlightListItemAirState as AirState,
  FlightListItemFlightAircraftType as AcType,
  FlightListItemProcessState as ProcessState,
} from '@api/generated/model';

import { FlightStore } from '../flight.store';

const AIR_STATE_OPTIONS: readonly AfSelectOption<AirState>[] = [
  { value: FlightListItemAirState.NEW, label: 'New' },
  { value: FlightListItemAirState.FLIGHT_PLAN_OPEN, label: 'Flight plan open' },
  { value: FlightListItemAirState.MIGHT_BE_STARTED, label: 'Might be started' },
  { value: FlightListItemAirState.STARTED, label: 'Started' },
  {
    value: FlightListItemAirState.MIGHT_BE_LANDED_OR_IN_AIR,
    label: 'Might be landed / in air',
  },
  { value: FlightListItemAirState.LANDED, label: 'Landed' },
  { value: FlightListItemAirState.FLIGHT_PLAN_CLOSED, label: 'Flight plan closed' },
];

const AIRCRAFT_TYPE_OPTIONS: readonly AfSelectOption<AcType>[] = [
  { value: FlightListItemFlightAircraftType.GLIDER, label: 'Glider' },
  { value: FlightListItemFlightAircraftType.TOW, label: 'Tow' },
  { value: FlightListItemFlightAircraftType.MOTOR, label: 'Motor' },
];

const PROCESS_STATE_LABEL: Readonly<Record<ProcessState, string>> = {
  [FlightListItemProcessState.NOT_PROCESSED]: 'Not processed',
  [FlightListItemProcessState.INVALID]: 'Invalid',
  [FlightListItemProcessState.VALID]: 'Valid',
  [FlightListItemProcessState.LOCKED]: 'Locked',
  [FlightListItemProcessState.DELIVERY_PREPARATION_ERROR]: 'Delivery prep error',
  [FlightListItemProcessState.DELIVERY_PREPARED]: 'Delivery prepared',
  [FlightListItemProcessState.DELIVERY_BOOKED]: 'Delivery booked',
  [FlightListItemProcessState.EXCLUDED_FROM_DELIVERY_PROCESS]: 'Excluded',
};

function toIsoDate(d: Date): string {
  const yyyy = d.getFullYear();
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${yyyy}-${mm}-${dd}`;
}

function formatLegacyDate(iso?: string): string {
  if (!iso || iso.length !== 10) return iso ?? '';
  // Surface dd.MM.yyyy — parity with legacy `flights.html`
  // (`flight.FlightDate | date:'dd.MM.yyyy'`).
  const [yyyy, mm, dd] = iso.split('-');
  return `${dd}.${mm}.${yyyy}`;
}

function formatTime(iso?: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${hh}:${mm}`;
}

function durationBlock(start?: string, ldg?: string): string {
  if (!start || !ldg) return '-';
  const s = new Date(start).getTime();
  const e = new Date(ldg).getTime();
  if (Number.isNaN(s) || Number.isNaN(e) || e < s) return '-';
  const mins = Math.round((e - s) / 60000);
  const hh = Math.floor(mins / 60);
  const mm = String(mins % 60).padStart(2, '0');
  return `${String(hh).padStart(2, '0')}:${mm}`;
}

function airStateLabel(state: AirState): string {
  return AIR_STATE_OPTIONS.find((o) => o.value === state)?.label ?? state;
}

type Tone = 'ok' | 'warn' | 'danger' | 'neutral';

function airStateTone(state: AirState): Tone {
  switch (state) {
    case FlightListItemAirState.LANDED:
    case FlightListItemAirState.FLIGHT_PLAN_CLOSED:
      return 'ok';
    case FlightListItemAirState.STARTED:
    case FlightListItemAirState.MIGHT_BE_STARTED:
    case FlightListItemAirState.MIGHT_BE_LANDED_OR_IN_AIR:
      return 'warn';
    case FlightListItemAirState.FLIGHT_PLAN_OPEN:
    case FlightListItemAirState.NEW:
    default:
      return 'neutral';
  }
}

function processStateTone(state: ProcessState): Tone {
  switch (state) {
    // Valid + downstream delivery states are operationally "done" / good.
    case FlightListItemProcessState.VALID:
    case FlightListItemProcessState.LOCKED:
    case FlightListItemProcessState.DELIVERY_PREPARED:
    case FlightListItemProcessState.DELIVERY_BOOKED:
      return 'ok';
    // Operator-required-attention conditions surface red (distinct from
    // amber/warn which marks in-flight transient states elsewhere).
    case FlightListItemProcessState.INVALID:
    case FlightListItemProcessState.DELIVERY_PREPARATION_ERROR:
      return 'danger';
    case FlightListItemProcessState.NOT_PROCESSED:
    case FlightListItemProcessState.EXCLUDED_FROM_DELIVERY_PROCESS:
    default:
      return 'neutral';
  }
}

function toneClasses(tone: Tone): string {
  switch (tone) {
    case 'ok':
      return 'bg-emerald-50 text-emerald-700';
    case 'warn':
      return 'bg-amber-50 text-amber-700';
    case 'danger':
      return 'bg-rose-50 text-rose-700';
    case 'neutral':
    default:
      return 'bg-slate-100 text-slate-700';
  }
}

function toneDotClass(tone: Tone): string {
  switch (tone) {
    case 'ok':
      return 'bg-emerald-500';
    case 'warn':
      return 'bg-amber-500';
    case 'danger':
      return 'bg-rose-500';
    case 'neutral':
    default:
      return 'bg-slate-500';
  }
}

/**
 * Logbook (flight list). Visual reference: docs/modernization/design-reference
 * screens-logbook.jsx + screenshots/02-desktop-cards.png. Card-per-flight,
 * header row (date / type pill / air-state pill / duration), emphasis row
 * (aircraft immatriculation), labels grid (aircraft / takeoff / landing).
 *
 * Columns are grounded in legacy `flsweb/src/flights/flights.html`: FlightDate
 * (rendered dd.MM.yyyy), Immatriculation, takeoff/landing times, glider-flight
 * duration, AirState, ProcessState. Pilot / second-crew / locations / comments
 * / tow columns from legacy are deferred with the rest of the decoration set
 * (per S-062a "decorations deferred").
 *
 * Server-side date-range filter; air-state / aircraft-type narrowing is
 * client-side over the loaded page until `/flights/search` lands.
 */
@Component({
  selector: 'af-flights-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    AfButtonComponent,
    AfDatePickerComponent,
    AfFormFieldComponent,
    AfIconComponent,
    AfPageComponent,
    AfPageErrorComponent,
    AfPageHeaderComponent,
    AfSelectComponent,
    NzDropDownModule,
    NzEmptyModule,
    NzSpinModule,
    RouterLink,
  ],
  template: `
    <af-page>
      <af-page-header title="Flights">
        <af-button
          type="primary"
          htmlType="button"
          (clicked)="router.navigateByUrl('/flights/new')"
          data-testid="flights-new-button"
        >
          New flight
        </af-button>
      </af-page-header>

      <p class="text-sm text-slate-500 mt-0 mb-4" data-testid="flights-summary">
        <span class="tabular">{{ summary() }}</span>
      </p>

      <div class="mb-5 grid grid-cols-1 md:grid-cols-5 gap-3 border border-slate-200 bg-white p-4">
        <!-- Two single-mode pickers instead of mode=range: the range
          variant of nz-range-picker deadlocks under zoneless Angular
          (reproducible at /dev/primitives). Switch back to a single
          range picker once S-008 fixes the primitive. -->
        <af-form-field label="From" for="FlightDateFrom">
          <af-date-picker
            mode="single"
            placeholder="From"
            [value]="dateFromValue()"
            (valueChange)="onDateFromChange($event)"
            data-testid="flights-date-from"
          />
        </af-form-field>

        <af-form-field label="To" for="FlightDateTo">
          <af-date-picker
            mode="single"
            placeholder="To"
            [value]="dateToValue()"
            (valueChange)="onDateToChange($event)"
            data-testid="flights-date-to"
          />
        </af-form-field>

        <af-form-field label="Air state" for="FlightAirStateFilter">
          <af-select
            inputId="FlightAirStateFilter"
            placeholder="All air states"
            [value]="selectedAirState()"
            (valueChange)="onAirStateChange($event)"
            [allowClear]="true"
            [options]="airStateOptions"
            data-testid="flights-air-state-filter"
          />
        </af-form-field>

        <af-form-field label="Aircraft type" for="FlightAircraftTypeFilter">
          <af-select
            inputId="FlightAircraftTypeFilter"
            placeholder="All aircraft types"
            [value]="selectedAircraftType()"
            (valueChange)="onAircraftTypeChange($event)"
            [allowClear]="true"
            [options]="aircraftTypeOptions"
            data-testid="flights-aircraft-type-filter"
          />
        </af-form-field>

        <div class="flex items-end">
          <button
            type="button"
            class="text-sm text-slate-500 hover:text-slate-900 underline bg-transparent border-0 p-0 cursor-pointer"
            (click)="onClearFilters()"
            data-testid="flights-clear-filters"
          >
            Clear filters
          </button>
        </div>
      </div>

      @if (store.loadError()) {
        <af-page-error
          [message]="store.loadError()"
          (retry)="store.refresh()"
          data-testid="flights-error"
        />
      }

      <div class="border border-slate-200 bg-white" data-testid="flights-table">
        @if (store.isLoading()) {
          <div class="flex justify-center py-12">
            <nz-spin />
          </div>
        } @else if (store.visibleEntities().length === 0) {
          <div class="py-12 text-center text-sm text-slate-500" data-testid="flights-empty">
            @if (hasActiveFilter()) {
              <p class="m-0">No flights match the active filters.</p>
              <button
                type="button"
                class="mt-2 underline bg-transparent border-0 cursor-pointer text-brand-600 hover:text-brand-700"
                (click)="onClearFilters()"
                data-testid="flights-empty-clear-filters"
              >
                Clear filters
              </button>
            } @else {
              <p class="m-0">No flights yet.</p>
            }
          </div>
        } @else {
          <ul role="list" class="list-none m-0 p-0">
            @for (fl of store.visibleEntities(); track fl.id) {
              <li
                class="flex flex-col gap-3 px-5 py-4 border-b border-slate-200 last:border-b-0 hover:bg-slate-50 transition-colors cursor-pointer"
                [attr.data-testid]="'flights-row-' + fl.id"
                (click)="openEdit(fl.id)"
                (keydown.enter)="openEdit(fl.id)"
                tabindex="0"
              >
                <!-- header row: date · type pill · status pill · duration -->
                <div class="flex items-center gap-3 flex-wrap text-sm">
                  <a
                    class="tabular font-medium text-slate-900 no-underline hover:text-brand-700"
                    [routerLink]="['/flights', fl.id, 'edit']"
                    [attr.data-testid]="'flights-row-link-' + fl.id"
                  >
                    {{ formatDate(fl.flightDate) || '-' }}
                  </a>
                  <span
                    class="inline-flex items-center px-2 py-px text-xs font-medium bg-slate-100 text-slate-700"
                    [attr.data-testid]="'flights-aircraft-type-' + fl.id"
                  >
                    {{ aircraftTypeLabel(fl.flightAircraftType) }}
                  </span>
                  <span
                    class="inline-flex items-center gap-1.5 px-2 py-px text-xs font-medium"
                    [class]="toneClass(fl.airState)"
                    [attr.data-testid]="'flights-air-state-' + fl.id"
                  >
                    <span class="w-1.5 h-1.5 rounded-full" [class]="toneDot(fl.airState)"></span>
                    {{ airStateText(fl.airState) }}
                  </span>
                  <span
                    class="inline-flex items-center gap-1.5 px-2 py-px text-xs font-medium"
                    [class]="processToneClass(fl.processState)"
                    [attr.data-testid]="'flights-process-state-' + fl.id"
                  >
                    <span
                      class="w-1.5 h-1.5 rounded-full"
                      [class]="processToneDot(fl.processState)"
                    ></span>
                    {{ processStateText(fl.processState) }}
                  </span>
                  <span class="flex-1"></span>
                  <span class="text-[10px] uppercase tracking-wider font-medium text-slate-500">
                    Duration
                  </span>
                  <span
                    class="tabular text-base font-medium text-slate-900"
                    [attr.data-testid]="'flights-duration-' + fl.id"
                  >
                    {{ duration(fl) }}
                  </span>
                  <button
                    type="button"
                    class="w-8 h-8 inline-flex items-center justify-center bg-transparent border-0 text-slate-500 cursor-pointer hover:text-slate-900 hover:bg-slate-100"
                    nz-dropdown
                    [nzDropdownMenu]="rowMenu"
                    nzTrigger="click"
                    nzPlacement="bottomRight"
                    [attr.aria-label]="'Actions for flight on ' + (fl.flightDate ?? fl.id)"
                    [attr.data-testid]="'flights-kebab-' + fl.id"
                    (click)="$event.stopPropagation()"
                  >
                    <af-icon name="more-vertical" [size]="18" />
                  </button>
                  <nz-dropdown-menu #rowMenu="nzDropdownMenu">
                    <ul
                      class="list-none m-0 p-1 min-w-[10rem] bg-white border border-slate-200"
                      role="menu"
                    >
                      <li role="none">
                        <a
                          role="menuitem"
                          class="flex items-center gap-2 w-full py-1.5 px-2.5 text-[15px] text-slate-900 no-underline cursor-pointer text-left hover:bg-slate-50"
                          [routerLink]="['/flights', fl.id, 'edit']"
                          [attr.data-testid]="'flights-edit-' + fl.id"
                        >
                          <af-icon name="pencil" [size]="14" />
                          <span>Edit</span>
                        </a>
                      </li>
                      <li role="none">
                        <a
                          role="menuitem"
                          class="flex items-center gap-2 w-full py-1.5 px-2.5 text-[15px] text-slate-900 no-underline cursor-pointer text-left hover:bg-slate-50"
                          [routerLink]="['/flights/copy', fl.id]"
                          [attr.data-testid]="'flights-copy-' + fl.id"
                        >
                          <af-icon name="copy" [size]="14" />
                          <span>Copy</span>
                        </a>
                      </li>
                    </ul>
                  </nz-dropdown-menu>
                </div>

                <!-- emphasis row: aircraft immatriculation (legacy
                  Immatriculation column). Pilot name (legacy PilotName
                  + SecondCrewName) is intentionally absent — list DTO
                  ships only aircraftId per S-062a; PIC name needs a
                  person lookup that the list endpoint does not decorate. -->
                <div class="flex items-baseline gap-2">
                  <span
                    class="tabular text-xl font-medium text-slate-900"
                    [attr.data-testid]="'flights-immat-' + fl.id"
                  >
                    {{ aircraftImmat(fl.aircraftId) }}
                  </span>
                </div>

                <!-- labels grid — Takeoff / Landing times (legacy
                  parity columns we can resolve today). Aircraft immat
                  is the emphasis row above, not repeated here. Pilot,
                  location and comment surfaces are deferred with the
                  rest of the decoration set (per S-062a "list
                  decorations deferred"). -->
                <dl class="grid grid-cols-2 gap-x-6 gap-y-2 m-0">
                  <div class="min-w-0">
                    <dt class="text-[10px] uppercase tracking-wider font-medium text-slate-500">
                      Takeoff
                    </dt>
                    <dd class="m-0 text-sm tabular text-slate-900">
                      {{ formatTime(fl.startDateTime) || '-' }}
                    </dd>
                  </div>
                  <div class="min-w-0">
                    <dt class="text-[10px] uppercase tracking-wider font-medium text-slate-500">
                      Landing
                    </dt>
                    <dd class="m-0 text-sm tabular text-slate-900">
                      {{ formatTime(fl.ldgDateTime) || '-' }}
                    </dd>
                  </div>
                </dl>
              </li>
            }
          </ul>
        }
      </div>
    </af-page>
  `,
})
export class FlightsListPage {
  protected readonly store = inject(FlightStore);
  private readonly aircraft = inject(AircraftStore);
  protected readonly router = inject(Router);

  protected readonly airStateOptions = AIR_STATE_OPTIONS;
  protected readonly aircraftTypeOptions = AIRCRAFT_TYPE_OPTIONS;

  protected readonly summary = computed(() => {
    const total = this.store.entities().length;
    const visible = this.store.visibleEntities().length;
    if (total === 0) {
      return 'No flights yet';
    }
    if (visible === total) {
      return `${total} ${total === 1 ? 'flight' : 'flights'}`;
    }
    return `${visible} of ${total} flights`;
  });

  protected readonly dateFromValue = computed<DateValue>(() => {
    const from = this.store.dateFrom();
    return from ? new Date(from) : null;
  });

  protected readonly dateToValue = computed<DateValue>(() => {
    const to = this.store.dateTo();
    return to ? new Date(to) : null;
  });

  protected readonly selectedAirState = computed<AirState | null>(() => {
    const sel = this.store.clientFilter().airStates;
    return sel.length === 1 ? sel[0]! : null;
  });

  protected readonly selectedAircraftType = computed<AcType | null>(() => {
    const sel = this.store.clientFilter().aircraftTypes;
    return sel.length === 1 ? sel[0]! : null;
  });

  protected aircraftImmat(id: string): string {
    // Falls back to `…` while AircraftStore hydrates so the row never
    // surfaces a raw UUID. The store is global + warmed by sibling
    // navigation; first-paint races are bounded by SessionStore prefetch.
    const immat = this.aircraft.entityMap()[id]?.immatriculation;
    return immat ?? '…';
  }

  protected aircraftTypeLabel(t: AcType): string {
    return AIRCRAFT_TYPE_OPTIONS.find((o) => o.value === t)?.label ?? t;
  }

  protected airStateText(state: AirState): string {
    return airStateLabel(state);
  }

  protected toneClass(state: AirState): string {
    return toneClasses(airStateTone(state));
  }

  protected toneDot(state: AirState): string {
    return toneDotClass(airStateTone(state));
  }

  protected processStateText(state: ProcessState): string {
    return PROCESS_STATE_LABEL[state];
  }

  protected processToneClass(state: ProcessState): string {
    return toneClasses(processStateTone(state));
  }

  protected processToneDot(state: ProcessState): string {
    return toneDotClass(processStateTone(state));
  }

  protected duration(fl: FlightListItem): string {
    return durationBlock(fl.startDateTime, fl.ldgDateTime);
  }

  protected formatTime(iso?: string): string {
    return formatTime(iso);
  }

  protected formatDate(iso?: string): string {
    return formatLegacyDate(iso);
  }

  protected onDateFromChange(value: DateValue): void {
    const from = value instanceof Date ? toIsoDate(value) : null;
    this.store.setDateRange({ from, to: this.store.dateTo() });
  }

  protected onDateToChange(value: DateValue): void {
    const to = value instanceof Date ? toIsoDate(value) : null;
    this.store.setDateRange({ from: this.store.dateFrom(), to });
  }

  protected onAirStateChange(value: AirState | null): void {
    this.store.setClientFilter({ airStates: value ? [value] : [] });
  }

  protected onAircraftTypeChange(value: AcType | null): void {
    this.store.setClientFilter({ aircraftTypes: value ? [value] : [] });
  }

  protected onClearFilters(): void {
    this.store.clearClientFilter();
    this.store.setDateRange({ from: null, to: null });
  }

  protected openEdit(flightId: string): void {
    this.router.navigate(['/flights', flightId, 'edit']);
  }

  protected hasActiveFilter(): boolean {
    const f = this.store.clientFilter();
    return (
      this.store.dateFrom() !== null ||
      this.store.dateTo() !== null ||
      f.airStates.length > 0 ||
      f.processStateIds.length > 0 ||
      f.aircraftTypes.length > 0
    );
  }
}
