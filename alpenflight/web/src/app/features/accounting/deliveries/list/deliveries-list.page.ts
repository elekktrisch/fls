import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';

import type { DeliveryOverview } from '@api/generated/model';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfIconComponent } from '@ui/atoms/af-icon';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfDataTableComponent, type PageChange } from '@ui/organisms/af-data-table';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { SessionStore } from '../../../../core/session/session.store';
import { isBookedState, processStateClass, processStateKey } from '../process-state';
import { DeliveriesStore } from '../deliveries.store';

@Component({
  selector: 'af-deliveries-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    AfButtonComponent,
    AfDataTableComponent,
    AfIconComponent,
    AfPageComponent,
    AfPageErrorComponent,
    AfPageHeaderComponent,
    RouterLink,
    TranslocoDirective,
  ],
  template: `
    <af-page>
      <ng-container *transloco="let t; read: 'deliveries'">
        <af-page-header [title]="t('title')">
          @if (canWrite()) {
            <af-button
              type="primary"
              htmlType="button"
              [loading]="store.isCreating()"
              (clicked)="create()"
              data-testid="del-create-button"
            >
              <af-icon name="plus" [size]="16" class="mr-1.5 inline-flex align-middle" />
              {{ t('actions.create') }}
            </af-button>
          }
        </af-page-header>

        <af-page-error
          [message]="store.loadError()"
          (retry)="store.loadFirstPage()"
          data-testid="del-error"
        />

        @if (actionError(); as ae) {
          <div
            class="mb-3 flex items-start justify-between gap-3 border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
            role="alert"
            data-testid="del-error-toast"
          >
            <span>{{ ae }}</span>
            <button
              type="button"
              class="shrink-0 border-0 bg-transparent text-red-700 cursor-pointer hover:text-red-900"
              [attr.aria-label]="t('actions.dismissError')"
              (click)="store.clearActionErrors()"
              data-testid="del-error-dismiss"
            >
              <af-icon name="x" [size]="16" />
            </button>
          </div>
        }

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
            <div class="flex items-center gap-2">
              @if (isBooked(row.processStateId)) {
                <span
                  class="inline-block px-2 py-0.5 text-xs border border-emerald-300 bg-emerald-50 text-emerald-700"
                  data-testid="del-booked-badge"
                >
                  {{ t('state.booked') }}
                </span>
              }
              <span
                class="inline-block px-2 py-0.5 text-xs border"
                [class]="stateClass(row.processStateId)"
                [attr.data-testid]="'del-state-' + row.id"
              >
                {{ t(stateKey(row.processStateId)) }}
              </span>
              @if (canWrite()) {
                <button
                  type="button"
                  class="inline-flex h-8 w-8 items-center justify-center border-0 bg-transparent text-slate-500 cursor-pointer hover:bg-slate-100 hover:text-red-600 disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-slate-500"
                  [disabled]="isBooked(row.processStateId)"
                  [attr.aria-label]="t('actions.delete')"
                  (click)="askDelete(row)"
                  data-testid="del-delete-button"
                >
                  <af-icon name="trash-2" [size]="16" />
                </button>
              }
            </div>
          </ng-template>
        </af-data-table>

        @if (pendingDelete(); as pd) {
          <div
            class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40"
            role="dialog"
            aria-modal="true"
            data-testid="del-delete-confirm-modal"
          >
            <div class="w-[24rem] max-w-[90vw] border border-slate-200 bg-white shadow-md">
              <div class="border-b border-slate-200 px-4 py-3 font-medium text-slate-900">
                {{ t('actions.deleteTitle') }}
              </div>
              <div class="px-4 py-3 text-sm text-slate-700">
                {{ t('actions.deleteConfirm', { recipient: pd.recipientName }) }}
              </div>
              <div class="flex justify-end gap-2 border-t border-slate-200 px-4 py-3">
                <af-button
                  type="default"
                  htmlType="button"
                  (clicked)="cancelDelete()"
                  data-testid="del-delete-cancel"
                >
                  {{ t('actions.cancel') }}
                </af-button>
                <af-button
                  type="primary"
                  htmlType="button"
                  [danger]="true"
                  (clicked)="confirmDelete()"
                  data-testid="del-delete-confirm"
                >
                  {{ t('actions.delete') }}
                </af-button>
              </div>
            </div>
          </div>
        }
      </ng-container>
    </af-page>
  `,
})
export class DeliveriesListPage {
  protected readonly store = inject(DeliveriesStore);
  private readonly session = inject(SessionStore);

  protected readonly canWrite = computed(
    () => this.session.isClubAdmin() || this.session.isSystemAdmin(),
  );

  protected readonly actionError = computed(
    () => this.store.createError() ?? this.store.deleteError(),
  );

  protected readonly pendingDelete = signal<DeliveryOverview | null>(null);

  constructor() {
    this.store.loadFirstPage();
  }

  protected onPageChange(change: PageChange): void {
    this.store.loadPage((change.page - 1) * change.pageSize);
  }

  protected create(): void {
    this.store.createDeliveries();
  }

  protected askDelete(row: DeliveryOverview): void {
    this.store.clearActionErrors();
    this.pendingDelete.set(row);
  }

  protected cancelDelete(): void {
    this.pendingDelete.set(null);
  }

  protected confirmDelete(): void {
    const row = this.pendingDelete();
    if (!row) return;
    this.store.deleteDelivery(row.id);
    this.pendingDelete.set(null);
  }

  protected stateKey(processStateId: number): string {
    return processStateKey(processStateId);
  }

  protected stateClass(processStateId: number): string {
    return processStateClass(processStateId);
  }

  protected isBooked(processStateId: number): boolean {
    return isBookedState(processStateId);
  }
}
