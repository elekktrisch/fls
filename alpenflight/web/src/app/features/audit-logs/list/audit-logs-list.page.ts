import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfDataTableComponent } from '@ui/organisms/af-data-table';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { formatIsoDateTime } from '@shared/util/date';

import { actionBadgeClass, actionLabel } from './action-catalog';
import { AuditLogsStore } from '../audit-logs.store';

@Component({
  selector: 'af-audit-logs-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    AfButtonComponent,
    AfDataTableComponent,
    AfPageComponent,
    AfPageErrorComponent,
    AfPageHeaderComponent,
    TranslocoDirective,
  ],
  template: `
    <af-page>
      <ng-container *transloco="let t; read: 'auditLogs'">
        <af-page-header [title]="t('title')" />

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

  ngOnInit(): void {
    this.store.loadPage();
  }
}
