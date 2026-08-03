import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { map, startWith } from 'rxjs';

import { formatIsoDateDdMmYyyy } from '@shared/util/date';

import { DiscoveryFlightStore } from './discovery-flight.store';
import { PublicFormShellComponent } from './public-form-shell.component';
import { RegistrantFieldsetComponent } from './registrant-fieldset.component';
import { REGISTRANT_FORM, provideRegistrantForm, toRegistrantDetails } from './registrant-form';

/**
 * The anonymous discovery-flight (Schnupperflug) registration form for the club
 * its URL names.
 *
 * The day is a page-level signal rather than a control of the shared registrant
 * form: the scenic flow posts that same form with no day, so a day control on it
 * would have to be conditionally disabled per flow.
 */
@Component({
  selector: 'af-discovery-flight-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block', 'data-testid': 'discovery-flight-page' },
  providers: [DiscoveryFlightStore, provideRegistrantForm()],
  imports: [TranslocoDirective, PublicFormShellComponent, RegistrantFieldsetComponent],
  template: `
    <ng-container *transloco="let t; read: 'publicRegistration'">
      <af-public-form-shell
        [title]="t('discovery.title')"
        [intro]="store.formState() === 'ready' ? t('discovery.intro') : ''"
        [clubName]="store.clubHeading()"
        [state]="store.formState()"
        [submitting]="store.submitting()"
        [submitDisabled]="submitDisabled()"
        [failure]="store.failure()"
        [retryAfterSeconds]="store.retryAfterSeconds()"
        (submitted)="onSubmit()"
      >
        <fieldset class="border border-slate-200 p-3 mb-4" data-testid="discovery-day-select">
          <legend class="text-sm text-slate-600 px-1">{{ t('day.legend') }}</legend>
          @if (store.days().length === 0) {
            <p class="m-0 text-sm text-slate-500" data-testid="discovery-day-empty">
              {{ t('day.none') }}
            </p>
          } @else {
            @for (day of store.days(); track day) {
              <label class="flex items-center gap-2 min-h-11 text-sm text-slate-700">
                <input
                  type="radio"
                  name="discoveryDay"
                  class="w-5 h-5"
                  [attr.data-testid]="'discovery-day-option-' + day"
                  [value]="day"
                  [checked]="selectedDay() === day"
                  (change)="selectedDay.set(day)"
                />
                <span class="tabular">{{ dayLabel(day) }}</span>
              </label>
            }
          }
        </fieldset>

        <af-registrant-fieldset />

        <p
          successDetail
          class="m-0 mt-2 text-sm text-slate-500"
          data-testid="discovery-flight-success-day"
        >
          {{ t('discovery.successDay', { date: dayLabel(registeredDay()) }) }}
        </p>
      </af-public-form-shell>
    </ng-container>
  `,
})
export class DiscoveryFlightPageComponent {
  protected readonly store = inject(DiscoveryFlightStore);

  readonly #form = inject(REGISTRANT_FORM);
  readonly #route = inject(ActivatedRoute);

  protected readonly selectedDay = signal<string | null>(null);

  readonly #formInvalid = toSignal(
    this.#form.statusChanges.pipe(
      startWith(this.#form.status),
      map(() => this.#form.invalid),
    ),
    { requireSync: true },
  );

  protected readonly submitDisabled = computed(
    () => this.#formInvalid() || this.selectedDay() === null,
  );

  protected readonly registeredDay = computed(() => this.store.registration()?.selectedDay ?? '');

  constructor() {
    this.store.loadClub(this.#route.paramMap.pipe(map((params) => params.get('clubSlug') ?? '')));
  }

  protected dayLabel(isoDate: string): string {
    return formatIsoDateDdMmYyyy(isoDate);
  }

  protected onSubmit(): void {
    const day = this.selectedDay();
    if (day === null || this.#form.invalid) return;
    this.store.submit(toRegistrantDetails(this.#form.getRawValue()), day);
  }
}
