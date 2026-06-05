import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';

import { SystemDashboardStore } from './system-dashboard.store';

/**
 * Sysadmin dashboard variant body (J-3 T-11): three cross-tenant tiles fed by
 * {@code GET /api/v1/me/system-dashboard} (T-10) via {@link SystemDashboardStore}
 * — total clubs / users / flights across ALL tenants (deliberately
 * tenant-unscoped, SYSTEM_ADMINISTRATOR-gated server-side) — plus a
 * `start-tenant-enter` control that takes the sysadmin into a tenant context.
 *
 * <p><b>Load-on-init, no SSE.</b> Unlike the club-admin tiles, the aggregates
 * load once via a normal GET — cross-tenant totals don't track a single
 * principal's `flight.created` events (journey carve: SSE is a per-tenant change
 * overlay, not a deployment-wide counter), so there's no live re-fetch here.
 *
 * <p><b>Tenant entry point.</b> No standalone tenant-switch picker exists yet in
 * the SPA (`session.tenantSwitch` is a bus event that domain stores clear on,
 * but nothing <em>produces</em> it via UI). The thinnest real affordance is the
 * existing cross-club {@code /clubs} list — the sysadmin's natural entry surface
 * for picking a club to act in — so `start-tenant-enter` navigates there rather
 * than building a tenant-switching subsystem (out of journey scope).
 *
 * <p>This component owns ONLY the variant body (tiles + the enter control). The
 * {@code start-variant-sysadmin} container, the heading, and the
 * `start-pilot-view-toggle` are owned by {@code StartShellPage} (T-07) and are
 * not duplicated here. Visual stance per ADR 0024: flat 1px-border tiles, no
 * shadows, matching the club-admin / pilot dashboard cards.
 */
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
        (click)="enterTenant()"
      >
        {{ t('sysadmin.tenantEnter') }}
      </button>
    </ng-container>
  `,
})
export class StartSysadminPage {
  protected readonly store = inject(SystemDashboardStore);
  private readonly router = inject(Router);

  /** Enter a tenant context via the existing cross-club {@code /clubs} list. */
  protected enterTenant(): void {
    void this.router.navigate(['/clubs']);
  }
}
