import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { filter, map, startWith } from 'rxjs';

import { SessionStore } from './core/session/session.store';
import { AfNavBarComponent, type NavItem, type UserSummary } from '@ui/organisms/af-nav-bar';

// Tenant-scoped nav (require a managing club; hidden for sysadmin).
const TENANT_SECTIONS: readonly NavItem[] = [
  { path: '/flights', label: 'Flights', icon: 'plane' },
  { path: '/aircraft', label: 'Aircraft', icon: 'plane' },
  { path: '/locations', label: 'Locations', icon: 'map-pin' },
  { path: '/persons', label: 'Persons', icon: 'users' },
  // Future sections (Flights, Reservations, Reports, Settings) land here as
  // their feature stories ship — kept inline so the nav-bar's input surface
  // stays a pure data shape.
];

// Cross-cutting nav (available to every authenticated principal).
const CROSS_CUTTING_SECTIONS: readonly NavItem[] = [
  { path: '/clubs', label: 'Clubs', icon: 'plane' },
];

@Component({
  selector: 'af-root',
  imports: [RouterOutlet, AfNavBarComponent],
  template: `
    @if (showNavBar()) {
      <af-nav-bar [items]="sections()" [user]="userSummary()" brandHref="/start" />
    }
    <router-outlet />
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppComponent {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  protected readonly session = inject(SessionStore);

  // Per S-159: sysadmin has no managing tenant (no clubId claim), so the
  // tenant-scoped pages render empty. Hide those nav entries entirely for
  // sysadmin; the remaining cross-cutting entries (Clubs today, sysadmin
  // user mgmt later) are what they can actually act on.
  protected readonly sections = computed<readonly NavItem[]>(() => {
    if (this.session.isSystemAdmin()) {
      return CROSS_CUTTING_SECTIONS;
    }
    return [...CROSS_CUTTING_SECTIONS, ...TENANT_SECTIONS];
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
