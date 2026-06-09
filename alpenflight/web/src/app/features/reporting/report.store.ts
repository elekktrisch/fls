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
import type {
  FlightReportDataRecord,
  FlightReportPageRequest,
  FlightReportResult,
  FlightReportSummary,
} from '@api/generated/model';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

// Canned reports request a large window legacy-side (count 1000); the backend
// clamps to its 500 cap (oracle § Pagination). Page from offset 0.
const DEFAULT_PAGE_START = 0;
const DEFAULT_PAGE_SIZE = 500;

/** The fetched Excel export: the streamed `.xlsx` blob + its server filename. */
export interface ExcelDownload {
  readonly blob: Blob;
  readonly filename: string;
}

const DEFAULT_EXPORT_FILENAME = 'FlightReports.xlsx';

/** Parse `attachment; filename="FlightReports.xlsx"` → the filename, or null. */
function filenameFromContentDisposition(header: string | null): string | null {
  if (!header) return null;
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header);
  return match?.[1] ? decodeURIComponent(match[1]) : null;
}

interface ReportState {
  /** The filter the current result was loaded with (drives the criteria panel). */
  filter: FlightReportPageRequest | null;
  items: readonly FlightReportDataRecord[];
  summaries: readonly FlightReportSummary[];
  totalRows: number;
  isLoading: boolean;
  loadError: string | null;
}

const initial: ReportState = {
  filter: null,
  items: [],
  summaries: [],
  totalRows: 0,
  isLoading: false,
  loadError: null,
};

/**
 * Flight-report read store. Calls `getFlightReportPage` with the
 * `{ sorting, searchFilter }` request shape and exposes the result + summaries
 * + loading/error for the results page (T-10) and custom builder (T-11).
 *
 * The store is filter-agnostic: callers (the canned-report route resolver, the
 * custom builder) compose the `searchFilter` — including binding
 * `flightCrewPersonId` (person reports → current user's PersonId) or `locationId`
 * (location reports → club homebase) — and pass it to `load()`. Sourcing those
 * IDs is the caller's concern (the picker reads SessionStore.personId; the
 * homebase source is flagged in the T-09 report — not yet on the web wire).
 */
export const ReportStore = signalStore(
  { providedIn: 'root' },
  withState<ReportState>(initial),
  withComputed(({ items, summaries, loadError, isLoading }) => ({
    isEmpty: computed(() => !isLoading() && items().length === 0 && summaries().length === 0),
    hasError: computed(() => loadError() !== null),
  })),
  withMethods((store, api = inject(FlightreportsService)) => {
    const fetchPage = rxMethod<FlightReportPageRequest>(
      pipe(
        tap((request) => patchState(store, { filter: request, isLoading: true, loadError: null })),
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

    return {
      /** Load a report page for the given `{ sorting, searchFilter }` request. */
      load(request: FlightReportPageRequest): void {
        fetchPage(request);
      },
      /** Re-run the last-loaded filter (visibility refresh / retry). */
      reload(): void {
        const f = store.filter();
        if (f) fetchPage(f);
      },
      /**
       * Fetch the streamed `.xlsx` export for the given request. The store owns
       * the HTTP call (web/CLAUDE.md §4 — no HttpClient in components); the
       * caller performs the browser download (DOM is a component concern). The
       * orval client types the body as `StreamingResponseBody`, so we override
       * `responseType: 'blob'` + `observe: 'response'` to read the binary body
       * AND the `Content-Disposition` filename.
       */
      async exportExcel(request: FlightReportPageRequest): Promise<ExcelDownload> {
        const response = (await firstValueFrom(
          api.exportFlightReportExcel(DEFAULT_PAGE_START, DEFAULT_PAGE_SIZE, request, {
            observe: 'response',
            // `responseType` is outside the orval-generated option type (it only
            // ever generates JSON bodies); the cast keeps Angular's HttpClient
            // returning the raw blob instead of attempting a JSON parse.
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
  }),
  withHooks({
    onInit(store) {
      const bus = inject(MUTATION_BUS);
      const destroyRef = inject(DestroyRef);
      // Read-side store: clear on tenant switch / logout so a stale club's
      // report never bleeds across a tenant boundary (web/CLAUDE.md § 4b).
      bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
        if (evt.kind === 'session.tenantSwitch' || evt.kind === 'session.logout') {
          store.clear();
        }
      });
    },
  }),
);
