import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';

import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';

import { SessionStore } from '../../core/session/session.store';

/**
 * Authenticated landing — placeholder home / dashboard surface. Reachable
 * to every authenticated principal regardless of tenant or role. A real
 * dashboard lands in a future story; this page exists so
 * `tenantRequiredGuard` has a safe redirect target.
 */
@Component({
  selector: 'af-start',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AfPageComponent, AfPageHeaderComponent],
  template: `
    <af-page>
      <af-page-header [title]="title()" />
      <p class="text-slate-600">
        Welcome to AlpenFlight. Pick a section from the nav bar to get started.
      </p>
      <p class="text-sm text-slate-500 mt-4" data-testid="start-placeholder">
        A real dashboard surface lands in a future story.
      </p>
    </af-page>
  `,
})
export class StartPage {
  private readonly session = inject(SessionStore);

  protected readonly title = computed(() => {
    const user = this.session.authenticatedUser();
    if (!user) return 'AlpenFlight';
    const name = `${user.firstName ?? ''} ${user.lastName ?? ''}`.trim() || user.username;
    return `Welcome, ${name}`;
  });
}
