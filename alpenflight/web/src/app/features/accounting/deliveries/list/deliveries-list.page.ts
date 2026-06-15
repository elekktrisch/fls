import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';

import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfDataTableComponent, type PageChange } from '@ui/organisms/af-data-table';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { processStateClass, processStateKey } from '../process-state';
import { DeliveriesStore } from '../deliveries.store';

/**
 * Delivery list (`/deliveries`). The invoice-draft read surface: the club's
 * deliveries (number · recipient · batch · state), paged, tenant-scoped on the
 * server. READ-ONLY — every row links into the view; no write affordance lands
 * this iteration.
 */
@Component({
  selector: 'af-deliveries-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    AfDataTableComponent,
    AfPageComponent,
    AfPageErrorComponent,
    AfPageHeaderComponent,
    RouterLink,
    TranslocoDirective,
  ],
  template: `
    <af-page>
      <ng-container *transloco="let t; read: 'deliveries'">
        <af-page-header [title]="t('title')" />

        <af-page-error
          [message]="store.loadError()"
          (retry)="store.loadFirstPage()"
          data-testid="del-error"
        />

        <af-data-table
          data-testid="del-table"
          [items]="store.rows()"
          [loading]="store.isLoading()"
          [pageSize]="store.pageSize()"
          [total]="store.total()"
          [showPagination]="store.total() > store.pageSize()"
          (pageChange)="onPageChange($event)"
        >
          <ng-template #primary let-row>
            <a
              class="text-slate-900 font-medium no-underline hover:text-brand-700"
              [routerLink]="['/deliveries', row.id]"
              [attr.data-testid]="'del-row-' + row.id"
            >
              <span class="tabular">{{ row.deliveryNumber ?? t('list.unbooked') }}</span>
              · {{ row.recipientName }}
            </a>
          </ng-template>
          <ng-template #secondary let-row>
            {{ t('list.columns.batch') }} <span class="tabular">{{ row.batchId }}</span>
          </ng-template>
          <ng-template #meta let-row>
            <span
              class="inline-block px-2 py-0.5 text-xs border"
              [class]="stateClass(row.processStateId)"
              [attr.data-testid]="'del-state-' + row.id"
            >
              {{ t(stateKey(row.processStateId)) }}
            </span>
          </ng-template>
        </af-data-table>
      </ng-container>
    </af-page>
  `,
})
export class DeliveriesListPage {
  protected readonly store = inject(DeliveriesStore);

  constructor() {
    this.store.loadFirstPage();
  }

  protected onPageChange(change: PageChange): void {
    this.store.loadPage((change.page - 1) * change.pageSize);
  }

  protected stateKey(processStateId: number): string {
    return processStateKey(processStateId);
  }

  protected stateClass(processStateId: number): string {
    return processStateClass(processStateId);
  }
}
