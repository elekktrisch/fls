import { ChangeDetectionStrategy, Component, effect, inject } from '@angular/core';
import {
  FormBuilder,
  FormControl,
  FormsModule,
  ReactiveFormsModule,
  type FormGroup,
} from '@angular/forms';
import { TranslocoDirective } from '@jsverse/transloco';

import { AfButtonComponent } from '@ui/atoms/af-button';

import type { MeNotificationPrefsUpdateRequest } from '@api/generated/model';

import { NotificationsStore } from './notifications.store';

type NotificationsForm = FormGroup<{
  // The 3 per-club notification prefs. Form-field names mirror the T-01 testid
  // tokens; the PATCH maps them to the T-10 DTO booleans (see {@link onSubmit}).
  flightReports: FormControl<boolean>;
  reservations: FormControl<boolean>;
  clubNews: FormControl<boolean>;
}>;

/**
 * Notifications tab of `/profile` (J-4 T-11). Edits the caller-tenant PersonClub's
 * 3 notification preferences — the simplest self-edit tab — via
 * {@code PATCH /api/v1/me/club-membership/notification-prefs} (orval
 * `updateMyNotificationPrefs`). The tab hydrates from
 * {@code GET /api/v1/me/club-membership/notification-prefs} (`getMyNotificationPrefs`).
 *
 * testid contract (T-01 spec) ↔ T-10 DTO field mapping:
 *   `profile-notifications-pref-flightReports` → `receiveFlightReports`;
 *   `profile-notifications-pref-reservations`  → `receiveAircraftReservationNotifications`;
 *   `profile-notifications-pref-clubNews`      → `receivePlanningDayRoleReminder`.
 *
 * The shell gates this tab on a linked Person (`[nzDisabled]` + no-Person banner)
 * and only renders the body `@if (hasPerson())`, so the store's GET never fires
 * for a person-less principal.
 */
@Component({
  selector: 'af-profile-notifications-tab',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, ReactiveFormsModule, TranslocoDirective, AfButtonComponent],
  template: `
    <ng-container *transloco="let t; read: 'profile.notifications'">
      <form
        [formGroup]="form"
        (ngSubmit)="onSubmit()"
        novalidate
        data-testid="profile-notifications-form"
        class="max-w-2xl flex flex-col gap-6"
      >
        <fieldset class="flex flex-col gap-3">
          <legend class="mb-2 text-sm font-medium text-slate-900">{{ t('prefsGroup') }}</legend>

          <label class="flex items-center gap-2 text-sm text-slate-700">
            <input
              type="checkbox"
              formControlName="flightReports"
              data-testid="profile-notifications-pref-flightReports"
            />
            {{ t('flightReports') }}
          </label>

          <label class="flex items-center gap-2 text-sm text-slate-700">
            <input
              type="checkbox"
              formControlName="reservations"
              data-testid="profile-notifications-pref-reservations"
            />
            {{ t('reservations') }}
          </label>

          <label class="flex items-center gap-2 text-sm text-slate-700">
            <input
              type="checkbox"
              formControlName="clubNews"
              data-testid="profile-notifications-pref-clubNews"
            />
            {{ t('clubNews') }}
          </label>
        </fieldset>

        @if (store.hasError()) {
          <div class="text-sm text-red-600" role="alert" data-testid="profile-notifications-error">
            {{ t('saveError') }}
          </div>
        } @else if (store.savedOnce() && !store.isSaving()) {
          <div
            class="text-sm text-slate-600"
            role="status"
            data-testid="profile-notifications-saved"
          >
            {{ t('saved') }}
          </div>
        }

        <div class="flex justify-end pt-4 border-t border-slate-200">
          <af-button
            type="primary"
            htmlType="submit"
            [disabled]="!store.canSave()"
            data-testid="profile-notifications-save"
          >
            {{ t('save') }}
          </af-button>
        </div>
      </form>
    </ng-container>
  `,
})
export class ProfileNotificationsTab {
  protected readonly store = inject(NotificationsStore);
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly form: NotificationsForm = this.fb.group({
    flightReports: this.fb.control(false),
    reservations: this.fb.control(false),
    clubNews: this.fb.control(false),
  });

  constructor() {
    // Hydrate the form whenever the store's view lands (initial GET + after a save
    // reflects the persisted projection).
    effect(() => {
      const view = this.store.view();
      if (view !== null) {
        this.form.patchValue(
          {
            flightReports: view.receiveFlightReports,
            reservations: view.receiveAircraftReservationNotifications,
            clubNews: view.receivePlanningDayRoleReminder,
          },
          { emitEvent: false },
        );
      }
    });

    this.store.load();
  }

  protected onSubmit(): void {
    const v = this.form.getRawValue();
    // The 3 toggles always send (a cleared checkbox is a real false). Field-name
    // → DTO-field mapping per the class doc.
    const req: MeNotificationPrefsUpdateRequest = {
      receiveFlightReports: v.flightReports,
      receiveAircraftReservationNotifications: v.reservations,
      receivePlanningDayRoleReminder: v.clubNews,
    };
    this.store.save(req);
  }
}
