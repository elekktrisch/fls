import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { filter, map, startWith } from 'rxjs';

import { SessionStore } from './core/session/session.store';
import { AfNavBarComponent, type Locale, type NavItem, type UserSummary } from '@ui/organisms/af-nav-bar';

const SECTIONS: readonly NavItem[] = [
  { path: '/clubs', label: 'Clubs', icon: 'plane' },
  // Future sections (Flights, Reservations, Members, Reports, Settings) land
  // here as their feature stories ship — kept inline so the nav-bar's input
  // surface stays a pure data shape.
];

@Component({
  selector: 'af-root',
  imports: [RouterOutlet, AfNavBarComponent],
  template: `
    @if (showNavBar()) {
      <af-nav-bar
        [items]="sections"
        [user]="userSummary()"
        [locale]="locale()"
        (localeChange)="locale.set($event)"
      />
    }
    <router-outlet />
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppComponent {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  protected readonly session = inject(SessionStore);

  protected readonly sections = SECTIONS;
  protected readonly locale = signal<Locale>('de');

  protected readonly userSummary = computed<UserSummary | null>(() => {
    const u = this.session.authenticatedUser();
    if (!u) return null;
    const initials = `${u.firstName?.[0] ?? ''}${u.lastName?.[0] ?? ''}`.toUpperCase() || '·';
    const displayName = `${u.firstName ?? ''} ${u.lastName ?? ''}`.trim() || u.username;
    return { displayName, initials };
  });

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
