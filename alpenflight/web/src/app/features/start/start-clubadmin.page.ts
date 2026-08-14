import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';

import { ClubDashboardStore } from './club-dashboard.store';

@Component({
  selector: 'af-start-clubadmin',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective],
  template: `
    <ng-container *transloco="let t; read: 'home'">
      <div class="grid grid-cols-1 gap-6 min-[900px]:grid-cols-2 mb-8">
        <div class="border border-slate-200 p-5" data-testid="start-tile-today-flights">
          <p class="text-slate-500">{{ t('admin.tiles.todayFlights') }}</p>
          @if (store.showCounts()) {
            <p
              class="text-3xl font-medium text-slate-900 tabular"
              data-testid="start-tile-today-flights-value"
            >
              {{ store.todaysFlights() }}
            </p>
          } @else if (store.hasError()) {
            <p class="text-red-600" data-testid="start-tile-today-flights-error">
              {{ t('admin.tiles.error') }}
            </p>
          } @else if (store.showLoading()) {
            <p class="text-slate-400">{{ t('admin.tiles.loading') }}</p>
          }
        </div>
        <div class="border border-slate-200 p-5" data-testid="start-tile-pending-validation">
          <p class="text-slate-500">{{ t('admin.tiles.pendingValidation') }}</p>
          @if (store.showCounts()) {
            <p
              class="text-3xl font-medium text-slate-900 tabular"
              data-testid="start-tile-pending-validation-value"
            >
              {{ store.pendingValidation() }}
            </p>
          } @else if (store.hasError()) {
            <p class="text-red-600" data-testid="start-tile-pending-validation-error">
              {{ t('admin.tiles.error') }}
            </p>
          } @else if (store.showLoading()) {
            <p class="text-slate-400">{{ t('admin.tiles.loading') }}</p>
          }
        </div>
      </div>
    </ng-container>
  `,
})
export class StartClubadminPage {
  protected readonly store = inject(ClubDashboardStore);
}
