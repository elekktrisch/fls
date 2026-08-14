import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { filter, map, startWith } from 'rxjs';

import { JoinRequestsBadgeService } from './core/join-requests-badge/join-requests-badge.service';
import { SessionStore } from './core/session/session.store';
import { AfNavBarComponent, type NavItem, type UserSummary } from '@ui/organisms/af-nav-bar';
import { navSectionsFor } from './nav-sections';

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
  private readonly joinRequestsBadge = inject(JoinRequestsBadgeService);

  protected readonly sections = computed<readonly NavItem[]>(() =>
    navSectionsFor(
      {
        isSystemAdmin: this.session.isSystemAdmin(),
        isClubAdmin: this.session.isClubAdmin(),
        clubId: this.session.currentClubId(),
      },
      { joinRequests: this.joinRequestsBadge.count },
    ),
  );

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
