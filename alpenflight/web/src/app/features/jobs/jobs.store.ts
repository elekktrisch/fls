import { HttpErrorResponse } from '@angular/common/http';
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
import { pipe, switchMap, tap } from 'rxjs';

import { AdminJobsService } from '@api/generated/admin-jobs/admin-jobs.service';
import type { JobResponse, JobRunResponse } from '@api/generated/model';

import { MUTATION_BUS } from '../../core/mutation-bus/mutation-bus';

interface JobsState {
  jobs: readonly JobResponse[];
  isLoading: boolean;
  loadError: string | null;
  runningJob: string | null;
  lastResult: JobRunResponse | null;
  lastResultJob: string | null;
}

const initialState: JobsState = {
  jobs: [],
  isLoading: false,
  loadError: null,
  runningJob: null,
  lastResult: null,
  lastResultJob: null,
};

export const JobsStore = signalStore(
  { providedIn: 'root' },
  withState<JobsState>(initialState),
  withComputed(({ jobs, loadError }) => ({
    isEmpty: computed(() => jobs().length === 0),
    hasError: computed(() => loadError() !== null),
  })),
  withMethods((store, jobsApi = inject(AdminJobsService)) => {
    const load = rxMethod<void>(
      pipe(
        tap(() => patchState(store, { isLoading: true, loadError: null })),
        switchMap(() =>
          jobsApi.listJobs().pipe(
            tapResponse({
              next: (jobs: JobResponse[]) => patchState(store, { jobs, isLoading: false }),
              error: (e: HttpErrorResponse) =>
                patchState(store, { loadError: e.message, isLoading: false }),
            }),
          ),
        ),
      ),
    );

    const runNow = rxMethod<string>(
      pipe(
        tap((name) =>
          patchState(store, { runningJob: name, lastResult: null, lastResultJob: null }),
        ),
        switchMap((name) =>
          jobsApi.runJob(name).pipe(
            tapResponse({
              next: (run: JobRunResponse) =>
                patchState(store, {
                  runningJob: null,
                  lastResult: run,
                  lastResultJob: name,
                  jobs: store.jobs().map((j) => (j.name === name ? { ...j, lastRun: run } : j)),
                }),
              error: (e: HttpErrorResponse) =>
                patchState(store, { runningJob: null, loadError: e.message }),
            }),
          ),
        ),
      ),
    );

    return { load, runNow };
  }),
  withHooks({
    onInit(store) {
      const bus = inject(MUTATION_BUS);
      const destroyRef = inject(DestroyRef);
      bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
        if (evt.kind === 'session.logout' || evt.kind === 'session.tenantSwitch') {
          patchState(store, initialState);
        }
      });
    },
  }),
);
