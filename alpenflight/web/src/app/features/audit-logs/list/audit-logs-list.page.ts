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

import { NzSpinModule } from 'ng-zorro-antd/spin';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfInputComponent } from '@ui/atoms/af-input';
import { AfSelectComponent, type AfSelectOption } from '@ui/atoms/af-select';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfDatePickerComponent, type DateValue } from '@ui/organisms/af-date-picker';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { formatIsoDateTime, localDateFromIso } from '@shared/util/date';

import { ListAuditEventsAction } from '@api/generated/model';
import type { AuditEventRow, ListAuditEventsAction as AuditAction } from '@api/generated/model';

import { actionBadgeClass, actionLabel } from './action-catalog';
import { buildAuditDiff } from './audit-diff';
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
    AfDatePickerComponent,
    AfFormFieldComponent,
    AfInputComponent,
    AfPageComponent,
    AfPageErrorComponent,
    AfPageHeaderComponent,
    AfSelectComponent,
    NzSpinModule,
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

        @if (store.isLoading()) {
          <div class="flex justify-center py-12" data-testid="audit-logs-table">
            <nz-spin />
          </div>
        } @else {
          <ul
            role="list"
            class="flex flex-col gap-2 list-none m-0 p-0"
            data-testid="audit-logs-table"
          >
            @for (row of store.items(); track row.id) {
              <li class="bg-white border border-slate-200 hover:border-slate-300">
                <button
                  type="button"
                  class="w-full flex items-center gap-4 p-4 text-left bg-transparent border-0 cursor-pointer"
                  [attr.aria-expanded]="isExpanded(row)"
                  [attr.aria-label]="t('detail.toggle')"
                  (click)="toggleRow(row)"
                  data-testid="audit-row"
                  [attr.data-audit-id]="row.id"
                >
                  <span class="flex-1 min-w-0 flex items-center gap-2">
                    <span
                      class="inline-block px-2 py-0.5 text-xs border"
                      [class]="badgeClass(row.action)"
                      data-testid="audit-row-action"
                    >
                      {{ label(row.action) }}
                    </span>
                    <span
                      class="text-slate-900 font-medium truncate"
                      data-testid="audit-row-target"
                    >
                      {{ row.targetEntityType }}
                    </span>
                  </span>
                  <span
                    class="flex-none text-sm text-slate-500 tabular"
                    data-testid="audit-row-actor"
                  >
                    {{ row.systemActor ? t('systemActor') : row.actorUserId }}
                  </span>
                  <span class="flex-none flex items-center gap-3">
                    <span
                      class="text-xs tabular"
                      [class]="row.failed ? 'text-red-700' : 'text-slate-500'"
                      data-testid="audit-row-status"
                    >
                      {{ row.httpStatus ?? '—' }}
                    </span>
                    <span class="text-xs text-slate-500 tabular" data-testid="audit-row-time">
                      {{ formatWhen(row.occurredAt) }}
                    </span>
                  </span>
                </button>

                @if (isExpanded(row)) {
                  @let diff = diffOf(row);
                  <div
                    class="border-t border-slate-200 bg-slate-50 px-4 py-3 text-sm"
                    data-testid="audit-row-detail"
                  >
                    @if (diff.mode === 'empty') {
                      <p class="text-slate-500 m-0">{{ t('detail.noPayload') }}</p>
                    } @else if (diff.mode === 'diff') {
                      <table class="w-full border-collapse font-mono text-xs">
                        <thead>
                          <tr class="text-left text-slate-500">
                            <th class="py-1 pr-4 font-medium">{{ t('detail.field') }}</th>
                            <th class="py-1 pr-4 font-medium">{{ t('detail.before') }}</th>
                            <th class="py-1 font-medium">{{ t('detail.after') }}</th>
                          </tr>
                        </thead>
                        <tbody>
                          @for (dr of diff.rows; track dr.key) {
                            <tr
                              class="align-top border-t border-slate-200"
                              [class.bg-amber-50]="dr.changed"
                            >
                              <td class="py-1 pr-4 text-slate-600">{{ dr.key }}</td>
                              <td class="py-1 pr-4 text-slate-500 whitespace-pre-wrap break-all">
                                {{ dr.before }}
                              </td>
                              <td
                                class="py-1 whitespace-pre-wrap break-all"
                                [class]="
                                  dr.changed ? 'text-slate-900 font-medium' : 'text-slate-500'
                                "
                              >
                                {{ dr.after }}
                              </td>
                            </tr>
                          }
                        </tbody>
                      </table>
                    } @else {
                      <table class="w-full border-collapse font-mono text-xs">
                        <thead>
                          <tr class="text-left text-slate-500">
                            <th class="py-1 pr-4 font-medium">{{ t('detail.field') }}</th>
                            <th class="py-1 font-medium">{{ t('detail.value') }}</th>
                          </tr>
                        </thead>
                        <tbody>
                          @for (dr of diff.rows; track dr.key) {
                            <tr class="align-top border-t border-slate-200">
                              <td class="py-1 pr-4 text-slate-600">{{ dr.key }}</td>
                              <td class="py-1 text-slate-900 whitespace-pre-wrap break-all">
                                {{ diff.mode === 'delete' ? dr.before : dr.after }}
                              </td>
                            </tr>
                          }
                        </tbody>
                      </table>
                    }

                    @if (row.failed && row.failureReason) {
                      <p class="mt-3 mb-0 text-red-700" data-testid="audit-row-failure">
                        {{ t('detail.failureReason') }}: {{ row.failureReason }}
                      </p>
                    }
                  </div>
                }
              </li>
            }
          </ul>
        }

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

  // View-only state: which row's before/after payload is currently expanded.
  // One at a time — kept on the page, not the store (it's not domain state).
  protected readonly expandedRowId = signal<string | null>(null);

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

  protected isExpanded(row: AuditEventRow): boolean {
    return !!row.id && this.expandedRowId() === row.id;
  }

  protected toggleRow(row: AuditEventRow): void {
    if (!row.id) return;
    this.expandedRowId.update((current) => (current === row.id ? null : row.id!));
  }

  protected diffOf(row: AuditEventRow) {
    return buildAuditDiff(row);
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
