import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { map } from 'rxjs';

import { PublicFormShellComponent } from './public-form-shell.component';
import { RegistrantFieldsetComponent } from './registrant-fieldset.component';
import {
  REGISTRANT_FORM,
  provideRegistrantForm,
  registrantFormInvalid,
  toRegistrantDetails,
} from './registrant-form';
import { ScenicFlightStore } from './scenic-flight.store';

/**
 * The anonymous scenic-flight (Mitflug) registration form for the club its URL
 * names — the discovery form minus the day: this flow books no reservation, so
 * there is no day to pick and none is sent.
 */
@Component({
  selector: 'af-scenic-flight-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block', 'data-testid': 'scenic-flight-page' },
  providers: [ScenicFlightStore, provideRegistrantForm()],
  imports: [TranslocoDirective, PublicFormShellComponent, RegistrantFieldsetComponent],
  template: `
    <ng-container *transloco="let t; read: 'publicRegistration'">
      <af-public-form-shell
        [title]="t('scenic.title')"
        [intro]="store.formState() === 'ready' ? t('scenic.intro') : ''"
        [clubName]="store.clubHeading()"
        [state]="store.formState()"
        [submitting]="store.submitting()"
        [submitDisabled]="formInvalid()"
        [failure]="store.failure()"
        [retryAfterSeconds]="store.retryAfterSeconds()"
        (submitted)="onSubmit()"
      >
        <af-registrant-fieldset />
      </af-public-form-shell>
    </ng-container>
  `,
})
export class ScenicFlightPageComponent {
  protected readonly store = inject(ScenicFlightStore);

  readonly #form = inject(REGISTRANT_FORM);
  readonly #route = inject(ActivatedRoute);

  protected readonly formInvalid = registrantFormInvalid(this.#form);

  constructor() {
    this.store.loadClub(this.#route.paramMap.pipe(map((params) => params.get('clubSlug') ?? '')));
  }

  protected onSubmit(): void {
    if (this.#form.invalid) return;
    this.store.submit(toRegistrantDetails(this.#form.getRawValue()));
  }
}
