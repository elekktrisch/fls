import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfDataTableComponent } from '@ui/organisms/af-data-table';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { UsersStore } from '../users.store';

@Component({
  selector: 'af-users-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    AfButtonComponent,
    AfDataTableComponent,
    AfPageComponent,
    AfPageErrorComponent,
    AfPageHeaderComponent,
    RouterLink,
  ],
  template: `
    <af-page>
      <af-page-header title="Users">
        <af-button
          type="primary"
          htmlType="button"
          (clicked)="router.navigateByUrl('/users/new')"
          data-testid="users-new-button"
        >
          New user
        </af-button>
      </af-page-header>

      <af-page-error
        [message]="store.loadError()"
        (retry)="store.loadAll()"
        data-testid="users-error"
      />

      <af-data-table
        data-testid="users-table"
        [items]="store.entities()"
        [loading]="store.isLoading()"
      >
        <ng-template #primary let-u>
          <a
            class="text-slate-900 font-medium no-underline hover:text-brand-700"
            [routerLink]="['/users', u.id, 'edit']"
            [attr.data-testid]="'user-row-' + u.id"
          >
            <span>{{ u.friendlyName }}</span>
          </a>
        </ng-template>
        <ng-template #secondary let-u>
          <span class="text-slate-600 tabular">{{ u.username }}</span>
          @for (role of u.roles; track role) {
            <span
              class="inline-block ml-1 text-xs px-2 py-0.5 bg-slate-50 text-slate-600"
              [attr.data-testid]="'user-role-' + u.id + '-' + role"
            >
              {{ role }}
            </span>
          }
        </ng-template>
        <ng-template #meta let-u>
          @if (u.invitePending) {
            <span
              class="inline-block text-xs px-2 py-0.5 bg-amber-50 text-amber-700"
              data-testid="user-invite-pending-badge"
            >
              Invite pending
            </span>
            <af-button
              type="default"
              htmlType="button"
              [disabled]="store.isResendingInvite()"
              (clicked)="onResend(u.id)"
              data-testid="user-resend-invite-button"
            >
              Resend invite
            </af-button>
          }
          @if (!u.enabled) {
            <span class="text-xs text-slate-500 ml-2">Inactive</span>
          }
        </ng-template>
      </af-data-table>
    </af-page>
  `,
})
export class UsersListPage {
  protected readonly store = inject(UsersStore);
  protected readonly router = inject(Router);

  protected onResend(id: string): void {
    this.store.resendInvite(id);
  }
}
