import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';
import { NzTabsModule } from 'ng-zorro-antd/tabs';

import { AfPageComponent } from '@ui/molecules/af-page';

import { SessionStore } from '../../core/session/session.store';

import { AccountStore } from './account.store';
import { PersonalStore } from './personal.store';
import { PilotStore } from './pilot.store';
import { ProfileAccountTab } from './profile-account.tab';
import { ProfilePersonalTab } from './profile-personal.tab';
import { ProfilePilotTab } from './profile-pilot.tab';

/**
 * `/profile` self-edit shell (T-03, J-4 / S-182). Renders the 4-tab scaffold —
 * Account / Personal / Pilot / Notifications — that the per-tab tasks build on.
 * Entry is the nav-bar avatar dropdown (wired at the app shell: `app.component.ts`
 * `userSummary()` feeds `<af-nav-bar [user]>`, whose dropdown carries the
 * `Profile` → `/profile` menuitem + `Sign out` → `/auth/logout`).
 *
 * Tab bodies are STUBS here — the real forms + caller-scoped `PATCH /api/v1/me/*`
 * calls land in T-05 (Account), T-07 (Personal), T-09 (Pilot), T-11
 * (Notifications). This task commits the shell, the tab routing, and the
 * `data-testid` contract from the T-01 spec stub.
 *
 * **No-Person gating (S-182 AC "No-Person state").** The caller's linked-Person
 * status comes off the session: `SessionStore.authenticatedUser().personId` is
 * `null` for a sysadmin / federated user with no `t_person` row (populated by
 * `loadMe()` from the `/api/v1/me` projection — no new `/me` field needed). When
 * unlinked, the {@link hasPerson} computed is false: the
 * `profile-no-person-banner` renders and the Personal / Pilot / Notifications
 * tabs are disabled (`[nzDisabled]`). Account stays editable (it targets the
 * `t_user` row, which every authenticated principal has).
 */
@Component({
  selector: 'af-profile-shell',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslocoDirective,
    NzTabsModule,
    AfPageComponent,
    ProfileAccountTab,
    ProfilePersonalTab,
    ProfilePilotTab,
  ],
  // Account / Personal / Pilot stores are feature-scoped to /profile — provided
  // here so their lifetime is the shell, and a fresh load runs on every visit
  // (T-05 Account, T-07 Personal, T-09 Pilot).
  providers: [AccountStore, PersonalStore, PilotStore],
  template: `
    <ng-container *transloco="let t; read: 'profile'">
      <af-page>
        <div data-testid="profile-page">
          <h1 class="mb-6 text-2xl font-medium text-slate-900">{{ t('title') }}</h1>

          @if (!hasPerson()) {
            <div
              class="mb-6 border border-slate-200 bg-slate-50 px-4 py-3 text-[15px] text-slate-700"
              role="status"
              data-testid="profile-no-person-banner"
            >
              {{ t('noPersonBanner') }}
            </div>
          }

          <nz-tabs [(nzSelectedIndex)]="selectedIndex">
            <!-- Account: always enabled (targets the t_user row). T-05 fills it. -->
            <nz-tab [nzTitle]="accountTitle">
              <ng-template #accountTitle>
                <span data-testid="profile-tab-account">{{ t('tabs.account') }}</span>
              </ng-template>
              <section data-testid="profile-panel-account">
                <af-profile-account-tab />
              </section>
            </nz-tab>

            <!-- Personal / Pilot / Notifications: disabled until a Person is linked. -->
            <nz-tab [nzTitle]="personalTitle" [nzDisabled]="!hasPerson()">
              <ng-template #personalTitle>
                <span data-testid="profile-tab-personal">{{ t('tabs.personal') }}</span>
              </ng-template>
              <section data-testid="profile-panel-personal">
                @if (hasPerson()) {
                  <af-profile-personal-tab />
                } @else {
                  <p class="text-slate-500">{{ t('stub') }}</p>
                }
              </section>
            </nz-tab>

            <nz-tab [nzTitle]="pilotTitle" [nzDisabled]="!hasPerson()">
              <ng-template #pilotTitle>
                <span data-testid="profile-tab-pilot">{{ t('tabs.pilot') }}</span>
              </ng-template>
              <section data-testid="profile-panel-pilot">
                @if (hasPerson()) {
                  <af-profile-pilot-tab />
                } @else {
                  <p class="text-slate-500">{{ t('stub') }}</p>
                }
              </section>
            </nz-tab>

            <nz-tab [nzTitle]="notificationsTitle" [nzDisabled]="!hasPerson()">
              <ng-template #notificationsTitle>
                <span data-testid="profile-tab-notifications">{{ t('tabs.notifications') }}</span>
              </ng-template>
              <section data-testid="profile-panel-notifications">
                <p class="text-slate-500">{{ t('stub') }}</p>
              </section>
            </nz-tab>
          </nz-tabs>
        </div>
      </af-page>
    </ng-container>
  `,
})
export class ProfileShellPage {
  private readonly session = inject(SessionStore);

  /** Account is the default-active tab (index 0). */
  protected readonly selectedIndex = signal(0);

  /**
   * True when the caller has a linked Person. Drives the no-Person banner +
   * which tabs are enabled. Sourced from the `/me`-populated session — see the
   * class doc.
   */
  protected readonly hasPerson = computed(() => this.session.authenticatedUser()?.personId != null);
}
