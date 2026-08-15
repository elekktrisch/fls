import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import type { PersonLookupMatch, UserUpdateRequestRolesItem } from '@api/generated/model';

import { AfDialogComponent } from '@ui/organisms/af-dialog';

import { formatDdMmYyyy } from '@shared/util/date';

import { UserPersonPickerComponent } from '../users/edit/person-picker.component';
import { CLUB_ADMIN_GRANTABLE_ROLES, roleLabel } from '../users/role-catalog';

import { JoinRequestsStore, type PendingJoinRequestItem } from './join-requests.store';

const DEFAULT_ROLES: ReadonlySet<UserUpdateRequestRolesItem> = new Set(['PILOT']);

@Component({
  selector: 'af-approve-modal',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AfDialogComponent, UserPersonPickerComponent],
  template: `
    <af-dialog
      [visible]="request() !== null"
      [wide]="true"
      title="Approve join request"
      confirmLabel="Approve"
      dismissLabel="Cancel"
      confirmTestid="approve-submit"
      [confirmDisabled]="!canSubmit()"
      (confirm)="onSubmit()"
      (dismiss)="onCancel()"
    >
      @if (request(); as r) {
        <div data-testid="approve-modal" class="flex flex-col gap-4">
          <section
            class="flex flex-col gap-1 bg-slate-50 border border-slate-200 px-3 py-2"
            data-testid="approve-request-info"
          >
            <span class="text-sm font-medium text-slate-900">{{ r.friendlyName }}</span>
            <span class="text-xs text-slate-500">{{ r.email }}</span>
            <span class="text-xs text-slate-500 tabular">{{ submittedAt(r) }}</span>
            @if (note(r); as n) {
              <span class="text-sm text-slate-600 mt-1">{{ n }}</span>
            }
          </section>

          <fieldset class="border border-slate-200 p-3 flex flex-col gap-2">
            <legend class="text-sm text-slate-600 px-1">Roles</legend>
            @for (role of grantableRoles; track role) {
              <label class="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  [checked]="isChecked(role)"
                  (change)="toggleRole(role)"
                  data-testid="approve-role-checkbox"
                />
                {{ roleLabel(role) }}
              </label>
            }
          </fieldset>

          <div class="flex flex-col gap-2" data-testid="approve-person-picker">
            <span class="text-sm text-slate-600">Link an existing person (optional)</span>
            <af-user-person-picker [(pinned)]="pickedPerson" />
          </div>

          @if (store.approveError(); as err) {
            <p class="text-sm text-red-600" data-testid="approve-error">{{ err }}</p>
          }
        </div>
      }
    </af-dialog>
  `,
})
export class ApproveModalComponent {
  protected readonly store = inject(JoinRequestsStore);

  readonly request = input<PendingJoinRequestItem | null>(null);
  readonly closed = output<void>();

  protected readonly grantableRoles = CLUB_ADMIN_GRANTABLE_ROLES;
  protected readonly roleLabel = roleLabel;

  protected readonly pickedPerson = signal<PersonLookupMatch | null>(null);
  private readonly checkedRoles = signal<ReadonlySet<UserUpdateRequestRolesItem>>(DEFAULT_ROLES);

  protected readonly canSubmit = computed(() => this.checkedRoles().size > 0);

  protected isChecked(role: UserUpdateRequestRolesItem): boolean {
    return this.checkedRoles().has(role);
  }

  protected toggleRole(role: UserUpdateRequestRolesItem): void {
    const next = new Set(this.checkedRoles());
    if (next.has(role)) {
      next.delete(role);
    } else {
      next.add(role);
    }
    this.checkedRoles.set(next);
  }

  protected submittedAt(r: PendingJoinRequestItem): string {
    return formatDdMmYyyy(r.createdOn);
  }

  protected note(r: PendingJoinRequestItem): string | null {
    return r.note?.trim() || null;
  }

  protected onSubmit(): void {
    const r = this.request();
    if (!r || !this.canSubmit()) return;
    const personId = this.pickedPerson()?.id;
    this.store.approve({
      id: r.id,
      friendlyName: r.friendlyName ?? r.email ?? '',
      roles: Array.from(this.checkedRoles()),
      ...(personId ? { personId } : {}),
    });
  }

  protected onCancel(): void {
    this.store.clearApproveError();
    this.reset();
    this.closed.emit();
  }

  reset(): void {
    this.checkedRoles.set(DEFAULT_ROLES);
    this.pickedPerson.set(null);
  }
}
