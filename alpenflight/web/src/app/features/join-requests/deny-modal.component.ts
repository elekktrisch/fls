import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';

import { AfDialogComponent } from '@ui/organisms/af-dialog';

import { JoinRequestsStore, type PendingJoinRequestItem } from './join-requests.store';

/** Server caps the denial reason at 500 chars (DenyJoinRequest.reason maxLength). */
const REASON_MAX = 500;

@Component({
  selector: 'af-deny-modal',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AfDialogComponent],
  template: `
    <af-dialog
      [visible]="request() !== null"
      title="Deny join request"
      confirmLabel="Deny"
      dismissLabel="Cancel"
      confirmTestid="deny-submit"
      (confirm)="onSubmit()"
      (dismiss)="onCancel()"
    >
      @if (request(); as r) {
        <div data-testid="deny-modal" class="flex flex-col gap-4">
          <p class="text-sm text-slate-600">Deny {{ r.friendlyName }}'s request to join.</p>

          <label class="flex flex-col gap-1 text-sm">
            <span class="text-slate-600">Reason (optional)</span>
            <textarea
              class="border border-slate-200 px-3 py-2 text-sm"
              rows="3"
              [attr.maxlength]="reasonMax"
              [value]="reason()"
              (input)="onReasonInput($event)"
              data-testid="deny-reason"
            ></textarea>
            <span class="flex items-center justify-between text-xs text-slate-500">
              <span>This will be emailed to the requester.</span>
              <span class="tabular" data-testid="deny-reason-counter">
                {{ reason().length }}/{{ reasonMax }}
              </span>
            </span>
          </label>

          @if (store.denyError(); as err) {
            <p class="text-sm text-red-600" data-testid="deny-error">{{ err }}</p>
          }
        </div>
      }
    </af-dialog>
  `,
})
export class DenyModalComponent {
  protected readonly store = inject(JoinRequestsStore);

  readonly request = input<PendingJoinRequestItem | null>(null);
  readonly closed = output<void>();

  protected readonly reasonMax = REASON_MAX;
  protected readonly reason = signal('');

  protected onReasonInput(event: Event): void {
    const next = (event.target as HTMLTextAreaElement).value.slice(0, REASON_MAX);
    this.reason.set(next);
  }

  protected onSubmit(): void {
    const r = this.request();
    if (!r) return;
    // Fire-and-stay: the host closes the modal once the denial lands (the row
    // drops); on failure the modal stays open so `deny-error` renders the cause
    // inline. A blank reason is omitted from the body by the store.
    this.store.deny({ id: r.id, reason: this.reason() });
  }

  protected onCancel(): void {
    this.store.clearDenyError();
    this.reset();
    this.closed.emit();
  }

  reset(): void {
    this.reason.set('');
  }
}
