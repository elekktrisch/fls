import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';
import { NzTabsModule } from 'ng-zorro-antd/tabs';

import { AfPageComponent } from '@ui/molecules/af-page';

import { SessionStore } from '../../core/session/session.store';

import { AccountStore } from './account.store';
import { NotificationsStore } from './notifications.store';
import { PersonalStore } from './personal.store';
import { PilotStore } from './pilot.store';
import { ProfileAccountTab } from './profile-account.tab';
import { ProfileNotificationsTab } from './profile-notifications.tab';
import { ProfilePersonalTab } from './profile-personal.tab';
import { ProfilePilotTab } from './profile-pilot.tab';

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
    ProfileNotificationsTab,
  ],
  providers: [AccountStore, PersonalStore, PilotStore, NotificationsStore],
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
            <nz-tab [nzTitle]="accountTitle">
              <ng-template #accountTitle>
                <span data-testid="profile-tab-account">{{ t('tabs.account') }}</span>
              </ng-template>
              <section data-testid="profile-panel-account">
                <af-profile-account-tab />
              </section>
            </nz-tab>

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
                @if (hasPerson()) {
                  <af-profile-notifications-tab />
                } @else {
                  <p class="text-slate-500">{{ t('stub') }}</p>
                }
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

  protected readonly selectedIndex = signal(0);

  protected readonly hasPerson = computed(() => this.session.authenticatedUser()?.personId != null);
}
