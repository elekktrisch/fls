import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { formatIsoDateTime } from '@shared/util/date';

import type { JobResponse, JobRunResponse } from '@api/generated/model';

import { JobsStore } from '../jobs.store';

const NEVER_RUN = 'NEVER_RUN';

function statusOf(job: JobResponse): string {
  return job.lastRun?.status ?? NEVER_RUN;
}

function durationSeconds(run: JobRunResponse | null | undefined): number | null {
  if (!run?.startedAt || !run?.finishedAt) return null;
  const ms = Date.parse(run.finishedAt) - Date.parse(run.startedAt);
  return Number.isFinite(ms) ? Math.round(ms / 100) / 10 : null;
}

@Component({
  selector: 'af-jobs-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    AfButtonComponent,
    AfPageComponent,
    AfPageErrorComponent,
    AfPageHeaderComponent,
    TranslocoDirective,
  ],
  template: `
    <af-page>
      <ng-container *transloco="let t; read: 'jobs'">
        <af-page-header [title]="t('title')" />

        <af-page-error
          [message]="store.loadError()"
          (retry)="store.load()"
          data-testid="jobs-error"
        />

        @if (!store.isLoading() && store.isEmpty() && !store.hasError()) {
          <div
            class="px-3 py-6 text-sm text-slate-500 border border-slate-200 bg-slate-50"
            data-testid="jobs-empty"
          >
            {{ t('empty') }}
          </div>
        }

        <ul role="list" class="flex flex-col gap-2 list-none m-0 p-0" data-testid="jobs-table">
          @for (job of store.jobs(); track job.name) {
            <li
              class="bg-white border border-slate-200 flex items-center gap-4 p-4"
              data-testid="job-row"
              [attr.data-job-name]="job.name"
            >
              <span class="flex-1 min-w-0 flex flex-col gap-0.5">
                <span class="text-slate-900 font-medium truncate" data-testid="job-row-name">
                  {{ job.name }}
                </span>
                <span class="text-xs text-slate-500 tabular" data-testid="job-row-cron">
                  {{ job.cron }}
                </span>
              </span>

              <span class="flex-none flex flex-col items-end gap-0.5 text-right">
                <span class="text-xs" [class]="statusClass(job)" data-testid="job-row-status">
                  {{ status(job) }}
                </span>
                @if (durationOf(job.lastRun); as secs) {
                  <span class="text-xs text-slate-500 tabular" data-testid="job-row-duration">
                    {{ t('duration', { secs }) }}
                  </span>
                }
                @if (job.lastRun?.summary; as summary) {
                  <span class="text-xs text-slate-500 truncate" data-testid="job-row-summary">
                    {{ summary }}
                  </span>
                }
                @if (job.lastRun?.finishedAt; as when) {
                  <span class="text-xs text-slate-400 tabular" data-testid="job-row-time">
                    {{ formatWhen(when) }}
                  </span>
                }
              </span>

              <span class="flex-none">
                <af-button
                  type="default"
                  htmlType="button"
                  [loading]="store.runningJob() === job.name"
                  [disabled]="store.runningJob() !== null"
                  (clicked)="onRunNow(job.name)"
                  data-testid="job-run-now"
                >
                  {{ t('runNow') }}
                </af-button>
              </span>
            </li>
          }
        </ul>

        @if (store.lastResult(); as result) {
          <div
            class="mt-4 border border-slate-200 bg-slate-50 p-4 text-sm"
            data-testid="job-run-result"
          >
            <p class="m-0 font-medium text-slate-900">
              {{ t('result.heading', { name: store.lastResultJob() }) }}
            </p>
            <p
              class="mt-1 mb-0"
              [class]="resultStatusClass(result)"
              data-testid="job-run-result-status"
            >
              {{ result.status }}
            </p>
            @if (durationOf(result); as secs) {
              <p class="mt-1 mb-0 text-slate-500 tabular">{{ t('duration', { secs }) }}</p>
            }
            @if (result.summary; as summary) {
              <p class="mt-1 mb-0 text-slate-500">{{ summary }}</p>
            }
          </div>
        }
      </ng-container>
    </af-page>
  `,
})
export class JobsListPage implements OnInit {
  protected readonly store = inject(JobsStore);

  protected readonly status = statusOf;
  protected readonly durationOf = durationSeconds;
  protected readonly formatWhen = formatIsoDateTime;

  ngOnInit(): void {
    this.store.load();
  }

  protected onRunNow(name: string | undefined): void {
    if (name) this.store.runNow(name);
  }

  protected statusClass(job: JobResponse): string {
    return this.statusColor(statusOf(job));
  }

  protected resultStatusClass(run: JobRunResponse): string {
    return this.statusColor(run.status ?? NEVER_RUN);
  }

  private statusColor(status: string): string {
    if (status === 'FAILED') return 'text-red-700 font-medium';
    if (status === 'COMPLETED') return 'text-green-700 font-medium';
    if (status === 'RUNNING') return 'text-sky-700 font-medium';
    return 'text-slate-500';
  }
}
