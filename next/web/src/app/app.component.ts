import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { filter, map, startWith } from 'rxjs';

import { SessionStore } from './core/session/session.store';

@Component({
  selector: 'af-root',
  imports: [RouterOutlet, RouterLink],
  template: `
    <main>
      @if (showNavBar()) {
        <!--
          TODO(S-008): replace with <af-nav-bar /> once the organism
          ships. Inline shim carries the logout trigger so S-021 has a
          minimum-viable signed-in UX.
        -->
        <header
          class="flex items-center justify-end gap-4 border-b border-gray-200 bg-white px-4 py-2"
        >
          @if (session.authenticatedUser(); as user) {
            <span class="text-sm text-gray-600">{{ user.username }}</span>
          }
          <a
            routerLink="/auth/logout"
            class="text-sm text-blue-600 hover:underline"
            data-testid="logout-link"
            >Abmelden</a
          >
        </header>
      }
      <router-outlet />
    </main>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppComponent {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  protected readonly session = inject(SessionStore);

  protected readonly showNavBar = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map(() => {
        let leaf = this.route;
        while (leaf.firstChild) {
          leaf = leaf.firstChild;
        }
        return leaf.snapshot.data['showNavBar'] === true;
      }),
      startWith(false),
    ),
    { initialValue: false },
  );
}
