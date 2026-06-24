import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';

import { SessionStore } from '@core/session/session.store';
import { formatDdMmYyyy } from '@shared/util/date';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { ApproveModalComponent } from './approve-modal.component';
import { DenyModalComponent } from './deny-modal.component';
import { JoinRequestsStore, type PendingJoinRequestItem } from './join-requests.store';

const NOTE_PREVIEW_LENGTH = 120;

@Component({
  selector: 'af-join-requests-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    AfButtonComponent,
    AfPageComponent,
    AfPageErrorComponent,
    AfPageHeaderComponent,
    ApproveModalComponent,
    DenyModalComponent,
    RouterLink,
  ],
  template: `
    <af-page data-testid="join-requests-page">
      <af-page-header
        title="Join requests"
        description="Pending requests to join your club."
      ></af-page-header>

      <af-page-error
        [message]="store.loadError()"
        (retry)="store.loadAll()"
        data-testid="join-requests-error"
      />

      @if (store.approvedToast(); as toast) {
        <div
          class="flex items-center justify-between gap-3 border border-emerald-300 bg-emerald-50 px-4 py-3 text-sm text-emerald-800"
          data-testid="join-request-success-toast"
        >
          <span>{{ toast.friendlyName }} added to {{ toast.clubName }}</span>
          <button type="button" class="text-emerald-700" (click)="store.dismissApprovedToast()">
            Dismiss
          </button>
        </div>
      }

      @if (!store.isLoading() && store.isEmpty() && !store.loadError()) {
        <div
          class="px-3 py-6 text-sm text-slate-500 border border-slate-200 bg-slate-50"
          data-testid="join-requests-empty"
        >
          No pending requests.
          @if (clubEditLink(); as link) {
            <a
              class="text-brand-700 hover:text-brand-800 ml-1"
              [routerLink]="link"
              data-testid="join-requests-empty-club-link"
            >
              Share your join code.
            </a>
          }
        </div>
      } @else {
        <ul
          class="flex flex-col divide-y divide-slate-200 border border-slate-200"
          data-testid="join-requests-list"
        >
          @for (request of store.entities(); track request.id) {
            <li
              class="flex flex-col gap-2 p-4 md:flex-row md:items-start md:justify-between md:gap-6"
              data-testid="join-request-row"
            >
              <div class="flex flex-col gap-1 min-w-0">
                <span
                  class="text-sm font-medium text-slate-900"
                  data-testid="join-request-friendly-name"
                >
                  {{ request.friendlyName }}
                </span>
                <span class="text-xs text-slate-500" data-testid="join-request-email">
                  {{ request.email }}
                </span>
                <span
                  class="text-xs text-slate-500 tabular"
                  data-testid="join-request-submitted-at"
                >
                  {{ submittedAt(request) }}
                </span>
                @if (notePreview(request); as note) {
                  <span class="text-sm text-slate-600 mt-1" data-testid="join-request-note">
                    {{ note }}
                  </span>
                }
              </div>
              <div class="flex flex-none items-center gap-2">
                <af-button
                  type="primary"
                  htmlType="button"
                  (clicked)="onApprove(request)"
                  data-testid="join-request-approve"
                >
                  Approve
                </af-button>
                <af-button
                  type="default"
                  htmlType="button"
                  (clicked)="onDeny(request)"
                  data-testid="join-request-deny"
                >
                  Deny
                </af-button>
              </div>
            </li>
          }
        </ul>
      }

      <af-approve-modal [request]="selectedRequest()" (closed)="closeApprove()" />
      <af-deny-modal [request]="selectedDenyRequest()" (closed)="closeDeny()" />
    </af-page>
  `,
})
export class JoinRequestsListPage {
  protected readonly store = inject(JoinRequestsStore);
  readonly #session = inject(SessionStore);

  protected readonly selectedRequest = signal<PendingJoinRequestItem | null>(null);
  protected readonly selectedDenyRequest = signal<PendingJoinRequestItem | null>(null);

  constructor() {
    // The decision lands asynchronously; close the open modal once its selected
    // row has dropped from the list (success). A failure keeps the row, so the
    // modal stays open showing its inline error.
    effect(() => {
      const ids = new Set(this.store.entities().map((r) => r.id));
      const approving = this.selectedRequest();
      if (approving && !ids.has(approving.id)) {
        this.selectedRequest.set(null);
      }
      const denying = this.selectedDenyRequest();
      if (denying && !ids.has(denying.id)) {
        this.selectedDenyRequest.set(null);
      }
    });
  }

  protected readonly clubEditLink = computed(() => {
    const clubId = this.#session.currentClubId();
    return clubId ? ['/clubs', clubId, 'edit'] : null;
  });

  protected submittedAt(request: PendingJoinRequestItem): string {
    return formatDdMmYyyy(request.createdOn);
  }

  protected notePreview(request: PendingJoinRequestItem): string | null {
    const note = request.note?.trim();
    if (!note) return null;
    return note.length > NOTE_PREVIEW_LENGTH ? `${note.slice(0, NOTE_PREVIEW_LENGTH)}…` : note;
  }

  protected onApprove(request: PendingJoinRequestItem): void {
    this.store.clearApproveError();
    this.selectedRequest.set(request);
  }

  protected closeApprove(): void {
    this.selectedRequest.set(null);
  }

  protected onDeny(request: PendingJoinRequestItem): void {
    this.store.clearDenyError();
    this.selectedDenyRequest.set(request);
  }

  protected closeDeny(): void {
    this.selectedDenyRequest.set(null);
  }
}
