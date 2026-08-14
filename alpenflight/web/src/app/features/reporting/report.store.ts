import { HttpErrorResponse, type HttpResponse } from '@angular/common/http';
import { DestroyRef, computed, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { tapResponse } from '@ngrx/operators';
import {
  patchState,
  signalStore,
  withComputed,
  withHooks,
  withMethods,
  withState,
} from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { firstValueFrom, pipe, switchMap, tap } from 'rxjs';

import { FlightreportsService } from '@api/generated/flightreports/flightreports.service';
import { LocationsService } from '@api/generated/locations/locations.service';
import { PersonsService } from '@api/generated/persons/persons.service';
import type {
  FlightReportDataRecord,
  FlightReportPageRequest,
  FlightReportResult,
  FlightReportSummary,
  LocationListItem,
  PersonListItem,
} from '@api/generated/model';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

const DEFAULT_PAGE_START = 0;
const DEFAULT_PAGE_SIZE = 500;

export interface ExcelDownload {
  readonly blob: Blob;
  readonly filename: string;
}

const DEFAULT_EXPORT_FILENAME = 'FlightReports.xlsx';

function filenameFromContentDisposition(header: string | null): string | null {
  if (!header) return null;
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header);
  return match?.[1] ? decodeURIComponent(match[1]) : null;
}

interface ReportState {
  filter: FlightReportPageRequest | null;
  items: readonly FlightReportDataRecord[];
  summaries: readonly FlightReportSummary[];
  totalRows: number;
  isLoading: boolean;
  loadError: string | null;
  personOptions: readonly PersonListItem[];
  locationOptions: readonly LocationListItem[];
}

const initial: ReportState = {
  filter: null,
  items: [],
  summaries: [],
  totalRows: 0,
  isLoading: false,
  loadError: null,
  personOptions: [],
  locationOptions: [],
};

export const ReportStore = signalStore(
  { providedIn: 'root' },
  withState<ReportState>(initial),
  withComputed(({ items, summaries, loadError, isLoading }) => ({
    isEmpty: computed(() => !isLoading() && items().length === 0 && summaries().length === 0),
    hasError: computed(() => loadError() !== null),
  })),
  withMethods(
    (
      store,
      api = inject(FlightreportsService),
      personsApi = inject(PersonsService),
      locationsApi = inject(LocationsService),
    ) => {
      const fetchPage = rxMethod<FlightReportPageRequest>(
        pipe(
          tap((request) =>
            patchState(store, { filter: request, isLoading: true, loadError: null }),
          ),
          switchMap((request) =>
            api.getFlightReportPage(DEFAULT_PAGE_START, DEFAULT_PAGE_SIZE, request).pipe(
              tapResponse({
                next: (res: FlightReportResult) =>
                  patchState(store, {
                    items: res.items,
                    summaries: res.summaries,
                    totalRows: res.totalRows,
                    isLoading: false,
                  }),
                error: (e: HttpErrorResponse) =>
                  patchState(store, { loadError: e.message, isLoading: false }),
              }),
            ),
          ),
        ),
      );

      const fetchPersons = rxMethod<void>(
        pipe(
          switchMap(() =>
            personsApi.listPersons().pipe(
              tapResponse({
                next: (persons: PersonListItem[]) => patchState(store, { personOptions: persons }),
                error: () => patchState(store, { personOptions: [] }),
              }),
            ),
          ),
        ),
      );
      const fetchLocations = rxMethod<void>(
        pipe(
          switchMap(() =>
            locationsApi.listLocations().pipe(
              tapResponse({
                next: (locations: LocationListItem[]) =>
                  patchState(store, { locationOptions: locations }),
                error: () => patchState(store, { locationOptions: [] }),
              }),
            ),
          ),
        ),
      );

      return {
        load(request: FlightReportPageRequest): void {
          fetchPage(request);
        },
        loadPersonOptions(): void {
          if (store.personOptions().length === 0) fetchPersons();
        },
        loadLocationOptions(): void {
          if (store.locationOptions().length === 0) fetchLocations();
        },
        reload(): void {
          const f = store.filter();
          if (f) fetchPage(f);
        },
        async exportExcel(request: FlightReportPageRequest): Promise<ExcelDownload> {
          const response = (await firstValueFrom(
            api.exportFlightReportExcel(DEFAULT_PAGE_START, DEFAULT_PAGE_SIZE, request, {
              observe: 'response',
              responseType: 'blob' as 'json',
            } as never),
          )) as HttpResponse<Blob>;
          const filename =
            filenameFromContentDisposition(response.headers.get('content-disposition')) ??
            DEFAULT_EXPORT_FILENAME;
          return { blob: response.body ?? new Blob(), filename };
        },
        clear(): void {
          patchState(store, initial);
        },
      };
    },
  ),
  withHooks({
    onInit(store) {
      const bus = inject(MUTATION_BUS);
      const destroyRef = inject(DestroyRef);
      bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
        if (evt.kind === 'session.tenantSwitch' || evt.kind === 'session.logout') {
          store.clear();
        }
      });
    },
  }),
);
