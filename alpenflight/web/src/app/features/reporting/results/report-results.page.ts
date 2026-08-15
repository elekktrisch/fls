import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { NzSpinModule } from 'ng-zorro-antd/spin';

import { SessionStore } from '@core/session/session.store';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { formatIsoDateDdMmYyyy } from '@shared/util/date';

import type { FlightReportPageRequest } from '@api/generated/model';

import { cannedReportRequest } from '../canned-report-request';
import { isCannedType } from '../canned-report';
import { customFilterRequest, decodeCustomFilter } from '../custom-filter';
import { ReportStore } from '../report.store';

function formatTime(iso?: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${hh}:${mm}`;
}

@Component({
  selector: 'af-report-results',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    AfButtonComponent,
    AfPageComponent,
    AfPageErrorComponent,
    AfPageHeaderComponent,
    NzSpinModule,
    RouterLink,
  ],
  template: `
    <af-page>
      <af-page-header [title]="pageTitle()">
        <af-button
          type="default"
          htmlType="button"
          [disabled]="store.isEmpty() || exporting()"
          (clicked)="onExport()"
          data-testid="report-excel-export"
        >
          {{ exporting() ? 'Exporting…' : 'Export Excel' }}
        </af-button>
      </af-page-header>

      <section
        class="mb-6 border border-slate-200 bg-white p-4"
        data-testid="report-filter-criteria"
      >
        <h2 class="text-[10px] uppercase tracking-wider font-medium text-slate-500 mt-0 mb-3">
          Filter
        </h2>
        <dl class="grid grid-cols-1 sm:grid-cols-3 gap-x-6 gap-y-3 m-0">
          <div class="min-w-0">
            <dt class="text-[10px] uppercase tracking-wider font-medium text-slate-500">
              Date range
            </dt>
            <dd class="m-0 text-sm tabular text-slate-900" data-testid="report-filter-range">
              {{ rangeLabel() }}
            </dd>
          </div>
          <div class="min-w-0">
            <dt class="text-[10px] uppercase tracking-wider font-medium text-slate-500">
              Flight types
            </dt>
            <dd class="m-0 text-sm text-slate-900" data-testid="report-filter-types">
              {{ flightTypesLabel() }}
            </dd>
          </div>
          <div class="min-w-0">
            <dt class="text-[10px] uppercase tracking-wider font-medium text-slate-500">
              {{ category() === 'location' ? 'Location' : 'Person' }}
            </dt>
            <dd class="m-0 text-sm text-slate-900" data-testid="report-filter-scope">
              {{ scopeLabel() }}
            </dd>
          </div>
        </dl>
      </section>

      @if (store.loadError()) {
        <af-page-error
          [message]="store.loadError()"
          (retry)="store.reload()"
          data-testid="report-error"
        />
      }

      @if (store.isLoading()) {
        <div class="flex justify-center py-16 border border-slate-200 bg-white">
          <nz-spin />
        </div>
      } @else if (store.isEmpty()) {
        <div
          class="py-16 text-center text-sm text-slate-500 border border-slate-200 bg-white"
          data-testid="report-empty"
        >
          <p class="m-0">No flights match this report.</p>
        </div>
      } @else {
        <div class="mb-6 border border-slate-200 bg-white overflow-x-auto">
          <table class="w-full text-sm border-collapse" data-testid="report-summary-table">
            <thead>
              <tr class="text-left text-[10px] uppercase tracking-wider text-slate-500">
                <th class="px-4 py-2 font-medium">Group</th>
                <th class="px-4 py-2 font-medium text-right">Starts</th>
                <th class="px-4 py-2 font-medium text-right">Landings</th>
                <th class="px-4 py-2 font-medium text-right">Flights</th>
                <th class="px-4 py-2 font-medium text-right">Duration</th>
              </tr>
            </thead>
            <tbody>
              @for (s of store.summaries(); track s.groupBy) {
                <tr
                  class="border-t border-slate-200 text-slate-900"
                  data-testid="report-summary-row"
                >
                  <td class="px-4 py-2">{{ s.groupBy }}</td>
                  <td class="px-4 py-2 text-right tabular">{{ s.totalStarts }}</td>
                  <td class="px-4 py-2 text-right tabular">{{ s.totalLdgs }}</td>
                  <td class="px-4 py-2 text-right tabular">{{ s.totalFlights }}</td>
                  <td class="px-4 py-2 text-right tabular">
                    {{ durationLabel(s.totalFlightDuration) }}
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>

        <div class="border border-slate-200 bg-white overflow-x-auto">
          <table class="w-full text-sm border-collapse" data-testid="report-flights-table">
            <thead>
              <tr class="text-left text-[10px] uppercase tracking-wider text-slate-500">
                <th class="px-3 py-2 font-medium">Date</th>
                <th class="px-3 py-2 font-medium">Aircraft</th>
                <th class="px-3 py-2 font-medium">Pilot</th>
                <th class="px-3 py-2 font-medium">2nd crew</th>
                <th class="px-3 py-2 font-medium">Solo</th>
                <th class="px-3 py-2 font-medium">Type</th>
                <th class="px-3 py-2 font-medium">From</th>
                <th class="px-3 py-2 font-medium">To</th>
                <th class="px-3 py-2 font-medium">Takeoff</th>
                <th class="px-3 py-2 font-medium">Landing</th>
                <th class="px-3 py-2 font-medium text-right">Duration</th>
                <th class="px-3 py-2 font-medium">Comment</th>
              </tr>
            </thead>
            <tbody>
              @for (fl of store.items(); track fl.flightId) {
                <tr
                  class="border-t border-slate-200 text-slate-900 align-top"
                  [attr.data-testid]="'report-flights-row'"
                >
                  <td class="px-3 py-2 tabular">{{ formatDate(fl.flightDate) }}</td>
                  <td class="px-3 py-2 tabular">{{ fl.immatriculation }}</td>
                  <td class="px-3 py-2">{{ fl.pilotName }}</td>
                  <td class="px-3 py-2">{{ fl.secondCrewName }}</td>
                  <td class="px-3 py-2 text-center">{{ fl.isSoloFlight ? '✓' : '' }}</td>
                  <td class="px-3 py-2">{{ fl.flightTypeName }}</td>
                  <td class="px-3 py-2">{{ fl.startLocation }}</td>
                  <td class="px-3 py-2">{{ fl.ldgLocation }}</td>
                  <td class="px-3 py-2 tabular">{{ time(fl.startDateTime) }}</td>
                  <td class="px-3 py-2 tabular">{{ time(fl.ldgDateTime) }}</td>
                  <td class="px-3 py-2 text-right tabular">{{ fl.flightDuration }}</td>
                  <td class="px-3 py-2 text-slate-500">{{ fl.flightComment }}</td>
                </tr>
                @if (fl.towFlight; as tow) {
                  <tr
                    class="text-xs text-slate-600 bg-slate-50"
                    [attr.data-testid]="'report-flights-tow-row'"
                  >
                    <td class="px-3 py-1.5"></td>
                    <td class="px-3 py-1.5 tabular">
                      <span class="text-slate-400 mr-1">↳ Tow</span>{{ tow.immatriculation }}
                    </td>
                    <td class="px-3 py-1.5">{{ tow.pilotName }}</td>
                    <td class="px-3 py-1.5"></td>
                    <td class="px-3 py-1.5"></td>
                    <td class="px-3 py-1.5">{{ tow.flightTypeName }}</td>
                    <td class="px-3 py-1.5">{{ tow.startLocation }}</td>
                    <td class="px-3 py-1.5">{{ tow.ldgLocation }}</td>
                    <td class="px-3 py-1.5 tabular">{{ time(tow.startDateTime) }}</td>
                    <td class="px-3 py-1.5 tabular">{{ time(tow.ldgDateTime) }}</td>
                    <td class="px-3 py-1.5 text-right tabular">{{ tow.flightDuration }}</td>
                    <td class="px-3 py-1.5"></td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>
      }

      <p class="mt-4 text-sm text-slate-500">
        <a class="underline text-brand-600 hover:text-brand-700" routerLink="/flightreports">
          Back to reports
        </a>
      </p>
    </af-page>
  `,
})
export class ReportResultsPage {
  protected readonly store = inject(ReportStore);
  private readonly session = inject(SessionStore);
  private readonly route = inject(ActivatedRoute);

  private readonly routeParams = toSignal(this.route.paramMap, { requireSync: true });
  protected readonly category = computed(() => this.routeParams().get('category') ?? 'person');
  protected readonly type = computed(() => this.routeParams().get('type') ?? '');
  private readonly filterParam = computed(() => this.routeParams().get('filter'));

  protected readonly exporting = signal(false);

  protected readonly pageTitle = computed(() =>
    this.category() === 'location' ? 'Location flight report' : 'My flight report',
  );

  private readonly request = computed<FlightReportPageRequest | null>(() => {
    const encoded = this.filterParam();
    if (encoded !== null) {
      const filter = decodeCustomFilter(encoded);
      return filter ? customFilterRequest(filter) : null;
    }
    const t = this.type();
    if (!isCannedType(t)) return null;
    const user = this.session.authenticatedUser();
    return cannedReportRequest(t, {
      personId: user?.personId ?? null,
      homebaseLocationId: user?.homebaseLocationId ?? null,
    });
  });

  protected readonly rangeLabel = computed(() => {
    const f = this.request()?.searchFilter;
    if (!f?.flightDateFrom || !f.flightDateTo) return '–';
    return `${formatIsoDateDdMmYyyy(f.flightDateFrom)} – ${formatIsoDateDdMmYyyy(f.flightDateTo)}`;
  });

  protected readonly flightTypesLabel = computed(() => {
    const f = this.request()?.searchFilter;
    if (!f) return '–';
    const on: string[] = [];
    if (f.gliderFlights) on.push('Glider');
    if (f.motorFlights) on.push('Motor');
    if (f.towFlights) on.push('Tow');
    return on.length > 0 ? on.join(', ') : 'None';
  });

  protected readonly scopeLabel = computed(() => {
    const f = this.request()?.searchFilter;
    const isCustom = this.filterParam() !== null;
    if (this.category() === 'location') {
      if (!f?.locationId) return 'Whole club';
      return isCustom ? 'Selected location' : 'Club homebase';
    }
    if (!f?.flightCrewPersonId) return 'Whole club';
    return isCustom ? 'Selected person' : 'Me';
  });

  constructor() {
    effect(() => {
      const request = this.request();
      if (request) {
        this.store.load(request);
      }
    });
  }

  protected formatDate(iso?: string): string {
    return formatIsoDateDdMmYyyy(iso);
  }

  protected time(iso?: string): string {
    return formatTime(iso);
  }

  protected durationLabel(value: string): string {
    return value || '0:00';
  }

  protected async onExport(): Promise<void> {
    const request = this.request();
    if (!request || this.exporting()) return;
    this.exporting.set(true);
    try {
      const download = await this.store.exportExcel(request).catch(() => null);
      if (download !== null) {
        const url = URL.createObjectURL(download.blob);
        try {
          const anchor = document.createElement('a');
          anchor.href = url;
          anchor.download = download.filename;
          document.body.appendChild(anchor);
          anchor.click();
          document.body.removeChild(anchor);
        } finally {
          URL.revokeObjectURL(url);
        }
      }
    } finally {
      this.exporting.set(false);
    }
  }
}
