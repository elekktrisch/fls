import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { TranslocoDirective } from '@jsverse/transloco';
import { debounceTime, distinctUntilChanged, skip } from 'rxjs';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfInputComponent } from '@ui/atoms/af-input';
import { AfSelectComponent, type AfSelectOption } from '@ui/atoms/af-select';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfDataTableComponent } from '@ui/organisms/af-data-table';
import { AfDatePickerComponent, type DateValue } from '@ui/organisms/af-date-picker';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { formatIsoDateTime, localDateFromIso } from '@shared/util/date';

import { ListAuditEventsAction } from '@api/generated/model';
import type { ListAuditEventsAction as AuditAction } from '@api/generated/model';

import { actionBadgeClass, actionLabel } from './action-catalog';
import { AuditLogsStore } from '../audit-logs.store';

const ACTION_OPTIONS: readonly AfSelectOption<AuditAction>[] = (
  Object.values(ListAuditEventsAction) as AuditAction[]
).map((value) => ({ value, label: actionLabel(value) }));

/** Whole-day UTC bounds so an `occurredAt` instant on the picked day sits in range. */
function startOfDayIso(date: Date): string {
  return `${startOfLocalDateOnly(date)}T00:00:00.000Z`;
}

function endOfDayIso(date: Date): string {
  return `${startOfLocalDateOnly(date)}T23:59:59.999Z`;
}

function startOfLocalDateOnly(date: Date): string {
  const yyyy = date.getFullYear();
  const mm = String(date.getMonth() + 1).padStart(2, '0');
  const dd = String(date.getDate()).padStart(2, '0');
  return `${yyyy}-${mm}-${dd}`;
}

@Component({
  selector: 'af-audit-logs-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    AfButtonComponent,
    AfDataTableComponent,
    AfDatePickerComponent,
    AfFormFieldComponent,
    AfInputComponent,
    AfPageComponent,
    AfPageErrorComponent,
    AfPageHeaderComponent,
    AfSelectComponent,
    TranslocoDirective,
  ],
  template: `
    <af-page>
      <ng-container *transloco="let t; read: 'auditLogs'">
        <af-page-header [title]="t('title')" />

        <div class="mb-5 border border-slate-200 bg-white p-4">
          <div class="grid grid-cols-1 md:grid-cols-4 gap-3">
            <af-form-field [label]="t('filters.action')" for="AuditFilterAction">
              <af-select
                inputId="AuditFilterAction"
                [placeholder]="t('filters.allActions')"
                [value]="selectedAction()"
                (valueChange)="onActionChange($event)"
                [allowClear]="true"
                [options]="actionOptions"
                data-testid="audit-filter-action"
              />
            </af-form-field>

            <af-form-field [label]="t('filters.target')" for="AuditFilterTarget">
              <af-input
                inputId="AuditFilterTarget"
                [placeholder]="t('filters.targetPlaceholder')"
                [value]="targetInput()"
                (valueChange)="targetInput.set($event)"
                data-testid="audit-filter-target"
              />
            </af-form-field>

            <af-form-field [label]="t('filters.from')" for="AuditFilterFrom">
              <af-date-picker
                inputId="AuditFilterFrom"
                [placeholder]="t('filters.fromPlaceholder')"
                [value]="fromValue()"
                (valueChange)="onFromChange($event)"
                data-testid="audit-filter-from"
              />
            </af-form-field>

            <af-form-field [label]="t('filters.to')" for="AuditFilterTo">
              <af-date-picker
                inputId="AuditFilterTo"
                [placeholder]="t('filters.toPlaceholder')"
                [value]="toValue()"
                (valueChange)="onToChange($event)"
                data-testid="audit-filter-to"
              />
            </af-form-field>
          </div>

          <div class="mt-3 flex justify-end">
            <button
              type="button"
              class="text-sm text-slate-500 hover:text-slate-900 underline bg-transparent border-0 p-0 cursor-pointer"
              (click)="onClearFilters()"
              data-testid="audit-clear-filters"
            >
              {{ t('filters.clear') }}
            </button>
          </div>
        </div>

        <af-page-error
          [message]="store.loadError()"
          (retry)="store.loadPage()"
          data-testid="audit-logs-error"
        />

        @if (!store.isLoading() && store.isEmpty() && !store.hasError()) {
          <div
            class="px-3 py-6 text-sm text-slate-500 border border-slate-200 bg-slate-50"
            data-testid="audit-logs-empty"
          >
            {{ t('empty') }}
          </div>
        }

        <af-data-table
          data-testid="audit-logs-table"
          [items]="store.items()"
          [loading]="store.isLoading()"
          [total]="null"
          [showPagination]="false"
        >
          <ng-template #primary let-row>
            <span
              class="flex items-center gap-2"
              data-testid="audit-row"
              [attr.data-audit-id]="row.id"
            >
              <span
                class="inline-block px-2 py-0.5 text-xs border"
                [class]="badgeClass(row.action)"
                data-testid="audit-row-action"
              >
                {{ label(row.action) }}
              </span>
              <span class="text-slate-900 font-medium" data-testid="audit-row-target">
                {{ row.targetEntityType }}
              </span>
            </span>
          </ng-template>
          <ng-template #secondary let-row>
            <span class="tabular" data-testid="audit-row-actor">
              {{ row.systemActor ? t('systemActor') : row.actorUserId }}
            </span>
          </ng-template>
          <ng-template #meta let-row>
            <span class="flex items-center gap-3">
              <span
                class="text-xs tabular"
                [class]="row.failed ? 'text-red-700' : 'text-slate-500'"
                data-testid="audit-row-status"
              >
                {{ row.httpStatus }}
              </span>
              <span class="text-xs text-slate-500 tabular" data-testid="audit-row-time">
                {{ formatWhen(row.occurredAt) }}
              </span>
            </span>
          </ng-template>
        </af-data-table>

        <div class="mt-3 flex items-center gap-2">
          <af-button
            type="default"
            htmlType="button"
            [disabled]="!store.canPrev()"
            (clicked)="store.prevPage()"
            data-testid="audit-pager-prev"
          >
            {{ t('pager.prev') }}
          </af-button>
          <af-button
            type="default"
            htmlType="button"
            [disabled]="!store.canNext()"
            (clicked)="store.nextPage()"
            data-testid="audit-pager-next"
          >
            {{ t('pager.next') }}
          </af-button>
          <span class="text-xs text-slate-500 tabular" data-testid="audit-pager-offset">
            {{ t('pager.page', { offset: store.pageOffset() }) }}
          </span>
        </div>
      </ng-container>
    </af-page>
  `,
})
export class AuditLogsListPage implements OnInit {
  protected readonly store = inject(AuditLogsStore);

  protected readonly label = actionLabel;
  protected readonly badgeClass = actionBadgeClass;
  protected readonly formatWhen = formatIsoDateTime;
  protected readonly actionOptions = ACTION_OPTIONS;

  protected readonly selectedAction = computed<AuditAction | null>(
    () => this.store.filters().action ?? null,
  );

  protected readonly targetInput = signal<string>(this.store.filters().targetEntityType ?? '');

  // The from/to date-pickers surface the ISO instant boundaries as calendar days:
  // the store holds a start-of-day (from) / end-of-day (to) instant, so map it back
  // to the local date the user picked. Only the date component is user-facing.
  protected readonly fromValue = computed<Date | null>(() =>
    localDateFromIso(this.store.filters().occurredFrom?.slice(0, 10)),
  );
  protected readonly toValue = computed<Date | null>(() =>
    localDateFromIso(this.store.filters().occurredTo?.slice(0, 10)),
  );

  constructor() {
    // Debounce the free-text target filter (per-keystroke otherwise); skip the seed
    // emission + no-op re-emits so a cleared/unchanged value never reloads.
    toObservable(this.targetInput)
      .pipe(skip(1), debounceTime(250), distinctUntilChanged(), takeUntilDestroyed())
      .subscribe((raw) => {
        const targetEntityType = raw.trim() || undefined;
        this.store.setFilters({ targetEntityType });
      });
  }

  ngOnInit(): void {
    this.store.loadPage();
  }

  protected onActionChange(action: AuditAction | null): void {
    this.store.setFilters({ action: action ?? undefined });
  }

  protected onFromChange(value: DateValue): void {
    const picked = value instanceof Date ? value : null;
    this.store.setFilters({ occurredFrom: picked ? startOfDayIso(picked) : undefined });
  }

  protected onToChange(value: DateValue): void {
    const picked = value instanceof Date ? value : null;
    this.store.setFilters({ occurredTo: picked ? endOfDayIso(picked) : undefined });
  }

  protected onClearFilters(): void {
    this.targetInput.set('');
    this.store.clearFilters();
  }
}
