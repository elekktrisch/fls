import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';

import { AfPageComponent } from '@ui/molecules/af-page';

import { SessionStore } from '../../core/session/session.store';

import { StartClubadminPage } from './start-clubadmin.page';
import { StartPage } from './start.page';
import { StartSysadminPage } from './start-sysadmin.page';
import { effectiveVariant, isAdminVariant } from './start-variant';

/**
 * `/start` role-switch shell (T-07, J-3). Selects which dashboard variant the
 * route renders off the session roles — sysadmin > clubadmin > pilot (the same
 * precedence + membership checks as {@link SessionStore.isSystemAdmin} /
 * {@link SessionStore.isClubAdmin}). The pilot variant is the shipped S-165
 * dashboard ({@link StartPage}, `<af-start>`), rendered UNCHANGED inside the
 * `start-variant-pilot` container — this shell only wraps + routes, it doesn't
 * touch the pilot body.
 *
 * The clubadmin / sysadmin variant BODIES are placeholders here: T-07 commits
 * the container + the tile/control testids the spec stub anchors on, so the
 * shell + routing + toggle are real and testable now; T-09 (club-admin tiles)
 * and T-11 (sysadmin tiles + tenant-enter) fill the values.
 *
 * "Pilot view" toggle: an admin (club-admin or sysadmin) gets a
 * `start-pilot-view-toggle` control that flips a local override signal to
 * render the pilot variant without leaving `/start`. The override is inert for
 * a pilot, so it can never hide a pilot's own dashboard. Default view = the
 * role's own variant.
 */
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

  /** Admin opted into the pilot dashboard via the "Pilot view" toggle. */
  private readonly pilotViewOverride = signal(false);

  protected readonly variant = computed(() =>
    effectiveVariant(this.session.authenticatedUser()?.roles ?? [], this.pilotViewOverride()),
  );

  /**
   * Offer the toggle only to an admin who is NOT currently viewing the pilot
   * variant — once toggled into the pilot view, the pilot variant has no toggle
   * (return is via normal navigation / reload, matching the MVP shape).
   */
  protected readonly showPilotViewToggle = computed(
    () =>
      isAdminVariant(this.session.authenticatedUser()?.roles ?? []) && !this.pilotViewOverride(),
  );

  protected enterPilotView(): void {
    this.pilotViewOverride.set(true);
  }
}
