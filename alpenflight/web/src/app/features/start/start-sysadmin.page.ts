import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';

import { SystemDashboardStore } from './system-dashboard.store';

@Component({
  selector: 'af-start-sysadmin',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective],
  template: `
    <ng-container *transloco="let t; read: 'home'">
      <div class="grid grid-cols-1 gap-6 min-[900px]:grid-cols-3 mb-8">
        <div class="border border-slate-200 p-5" data-testid="start-tile-total-clubs">
          <p class="text-slate-500">{{ t('sysadmin.tiles.totalClubs') }}</p>
          @if (store.showTotals()) {
            <p
              class="text-3xl font-medium text-slate-900 tabular"
              data-testid="start-tile-total-clubs-value"
            >
              {{ store.totalClubs() }}
            </p>
          } @else if (store.hasError()) {
            <p class="text-red-600" data-testid="start-tile-total-clubs-error">
              {{ t('sysadmin.tiles.error') }}
            </p>
          } @else if (store.showLoading()) {
            <p class="text-slate-400">{{ t('sysadmin.tiles.loading') }}</p>
          }
        </div>
        <div class="border border-slate-200 p-5" data-testid="start-tile-total-users">
          <p class="text-slate-500">{{ t('sysadmin.tiles.totalUsers') }}</p>
          @if (store.showTotals()) {
            <p
              class="text-3xl font-medium text-slate-900 tabular"
              data-testid="start-tile-total-users-value"
            >
              {{ store.totalUsers() }}
            </p>
          } @else if (store.hasError()) {
            <p class="text-red-600" data-testid="start-tile-total-users-error">
              {{ t('sysadmin.tiles.error') }}
            </p>
          } @else if (store.showLoading()) {
            <p class="text-slate-400">{{ t('sysadmin.tiles.loading') }}</p>
          }
        </div>
        <div class="border border-slate-200 p-5" data-testid="start-tile-total-flights">
          <p class="text-slate-500">{{ t('sysadmin.tiles.totalFlights') }}</p>
          @if (store.showTotals()) {
            <p
              class="text-3xl font-medium text-slate-900 tabular"
              data-testid="start-tile-total-flights-value"
            >
              {{ store.totalFlights() }}
            </p>
          } @else if (store.hasError()) {
            <p class="text-red-600" data-testid="start-tile-total-flights-error">
              {{ t('sysadmin.tiles.error') }}
            </p>
          } @else if (store.showLoading()) {
            <p class="text-slate-400">{{ t('sysadmin.tiles.loading') }}</p>
          }
        </div>
      </div>
      <button
        type="button"
        class="inline-flex items-center justify-center px-4 py-2 min-h-[44px] border border-slate-300 text-slate-800 hover:border-slate-500 focus-visible:outline-2 focus-visible:outline-brand-500 focus-visible:outline-offset-2"
        data-testid="start-tenant-enter"
        (click)="enterTenantViaClubList()"
      >
        {{ t('sysadmin.tenantEnter') }}
      </button>
    </ng-container>
  `,
})
export class StartSysadminPage {
  protected readonly store = inject(SystemDashboardStore);
  private readonly router = inject(Router);

  protected enterTenantViaClubList(): void {
    void this.router.navigate(['/clubs']);
  }
}
