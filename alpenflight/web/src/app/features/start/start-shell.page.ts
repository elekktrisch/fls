import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';

import { AfPageComponent } from '@ui/molecules/af-page';

import { SessionStore } from '../../core/session/session.store';

import { StartClubadminPage } from './start-clubadmin.page';
import { StartPage } from './start.page';
import { StartSysadminPage } from './start-sysadmin.page';
import { effectiveVariant, isAdminVariant } from './start-variant';

@Component({
  selector: 'af-start-shell',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective, AfPageComponent, StartPage, StartClubadminPage, StartSysadminPage],
  template: `
    <ng-container *transloco="let t; read: 'home'">
      @switch (variant()) {
        @case ('sysadmin') {
          <af-page>
            <div data-testid="start-variant-sysadmin">
              <header class="mb-8 flex items-baseline justify-between gap-3">
                <h1 class="text-2xl font-medium text-slate-900">{{ t('sysadmin.heading') }}</h1>
                @if (showPilotViewToggle()) {
                  <button
                    type="button"
                    class="inline-flex items-center justify-center px-3 py-1.5 min-h-[44px] border border-slate-300 text-slate-800 hover:border-slate-500 focus-visible:outline-2 focus-visible:outline-brand-500 focus-visible:outline-offset-2"
                    data-testid="start-pilot-view-toggle"
                    (click)="enterPilotView()"
                  >
                    {{ t('pilotView.toggle') }}
                  </button>
                }
              </header>
              <!-- Cross-tenant tiles fed by GET /api/v1/me/system-dashboard +
                   the tenant-enter control (T-11). -->
              <af-start-sysadmin />
            </div>
          </af-page>
        }
        @case ('clubadmin') {
          <af-page>
            <div data-testid="start-variant-clubadmin">
              <header class="mb-8 flex items-baseline justify-between gap-3">
                <h1 class="text-2xl font-medium text-slate-900">{{ t('admin.heading') }}</h1>
                @if (showPilotViewToggle()) {
                  <button
                    type="button"
                    class="inline-flex items-center justify-center px-3 py-1.5 min-h-[44px] border border-slate-300 text-slate-800 hover:border-slate-500 focus-visible:outline-2 focus-visible:outline-brand-500 focus-visible:outline-offset-2"
                    data-testid="start-pilot-view-toggle"
                    (click)="enterPilotView()"
                  >
                    {{ t('pilotView.toggle') }}
                  </button>
                }
              </header>
              <!-- Tiles fed by GET /api/v1/me/club-dashboard, live-updated on a
                   flight.created SSE push (T-09). -->
              <af-start-clubadmin />
            </div>
          </af-page>
        }
        @default {
          <!-- Pilot variant = the shipped S-165 dashboard, rendered unchanged
               inside its container. The shell wraps; it does not rewrite. -->
          <div data-testid="start-variant-pilot">
            <af-start />
          </div>
        }
      }
    </ng-container>
  `,
})
export class StartShellPage {
  private readonly session = inject(SessionStore);

  private readonly pilotViewOverride = signal(false);

  protected readonly variant = computed(() =>
    effectiveVariant(this.session.authenticatedUser()?.roles ?? [], this.pilotViewOverride()),
  );

  protected readonly showPilotViewToggle = computed(
    () =>
      isAdminVariant(this.session.authenticatedUser()?.roles ?? []) && !this.pilotViewOverride(),
  );

  protected enterPilotView(): void {
    this.pilotViewOverride.set(true);
  }
}
