import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { filter, map, startWith } from 'rxjs';

import { SessionStore } from './core/session/session.store';
import { AfNavBarComponent, type NavItem, type UserSummary } from '@ui/organisms/af-nav-bar';

const BASE_SECTIONS: readonly NavItem[] = [
  { path: '/clubs', label: 'Clubs', icon: 'plane' },
  { path: '/aircraft', label: 'Aircraft', icon: 'plane' },
  // Future sections (Flights, Reservations, Members, Reports, Settings) land
  // here as their feature stories ship — kept inline so the nav-bar's input
  // surface stays a pure data shape.
];

@Component({
  selector: 'af-root',
  imports: [RouterOutlet, AfNavBarComponent],
  template: `
    @if (showNavBar()) {
      <af-nav-bar [items]="sections()" [user]="userSummary()" />
    }
    <router-outlet />
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppComponent {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  protected readonly session = inject(SessionStore);

  // Sysadmin gets an extra entry pointing at the cross-tenant admin surface.
  // Gated on `session.isSystemAdmin` so the entry is hidden for everyone else
  // (server also gates the route via `sysadminGuard` + `@PreAuthorize`). The
  // label stays literal English to match the rest of `BASE_SECTIONS` — nav-bar
  // i18n is tracked separately and lands when the rest of the sections are
  // translated.
  protected readonly sections = computed<readonly NavItem[]>(() => {
    if (!this.session.isSystemAdmin()) {
      return BASE_SECTIONS;
    }
    return [
      ...BASE_SECTIONS,
      { path: '/admin/locations', label: 'Locations admin', icon: 'shield' },
    ];
  });

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
