import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  inject,
  input,
  model,
  output,
  signal,
} from '@angular/core';
import { FormBuilder, FormControl, ReactiveFormsModule, type FormGroup } from '@angular/forms';
import { catchError, of } from 'rxjs';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfInputComponent } from '@ui/atoms/af-input';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';

import { PersonsService } from '@api/generated/persons/persons.service';
import type {
  PersonLookupMatch,
  PersonLookupRequest,
  PersonLookupResult,
} from '@api/generated/model';

type PickerForm = FormGroup<{
  email: FormControl<string>;
  firstname: FormControl<string>;
  lastname: FormControl<string>;
  birthday: FormControl<string>;
}>;

/**
 * Inline Person picker for the User invite form. Reuses S-051's exact-match
 * `POST /api/v1/persons/lookup` — never `GET /api/v1/persons?q=`
 * (enumeration). The lookup endpoint is rate-limited + audited on both hit
 * and miss; the picker is the only entry point so a free-text UUID never
 * reaches the wire.
 *
 * Two-input rule: email alone, OR the full identity triple
 * (firstname + lastname + birthday). Mixed / partial triples are rejected
 * by the backend with 400.
 */
@Component({
  selector: 'af-user-person-picker',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, AfButtonComponent, AfInputComponent, AfFormFieldComponent],
  template: `
    @if (pinned(); as p) {
      <div
        class="flex items-center justify-between gap-2 px-3 py-2 bg-slate-50 border border-slate-200"
        data-testid="person-picker-pinned"
      >
        <span class="text-sm text-slate-800">
          <span class="font-medium">{{ p.lastname }}, {{ p.firstname }}</span>
          @if (p.email) {
            <span class="text-slate-500 ml-2">{{ p.email }}</span>
          }
        </span>
        <af-button
          type="default"
          htmlType="button"
          (clicked)="onClear()"
          data-testid="person-picker-clear"
        >
          Clear
        </af-button>
      </div>
    } @else {
      <div class="flex flex-col gap-2 px-3 py-2 border border-slate-200">
        <p class="text-xs text-slate-500">
          Optional. Either email, or all three of first name, last name, birthday.
        </p>
        <form
          [formGroup]="form"
          (ngSubmit)="onSearch()"
          novalidate
          class="flex flex-col gap-2"
          data-testid="person-picker-form"
        >
          <af-form-field label="Email" for="LookupEmail">
            <af-input
              inputId="LookupEmail"
              type="email"
              formControlName="email"
              data-testid="person-picker-email"
              autocomplete="email"
            />
          </af-form-field>
          <div class="grid grid-cols-3 gap-2">
            <af-form-field label="First name" for="LookupFirstname">
              <af-input
                inputId="LookupFirstname"
                formControlName="firstname"
                data-testid="person-picker-firstname"
                autocomplete="given-name"
              />
            </af-form-field>
            <af-form-field label="Last name" for="LookupLastname">
              <af-input
                inputId="LookupLastname"
                formControlName="lastname"
                data-testid="person-picker-lastname"
                autocomplete="family-name"
              />
            </af-form-field>
            <af-form-field label="Birthday" for="LookupBirthday">
              <af-input
                inputId="LookupBirthday"
                type="date"
                formControlName="birthday"
                data-testid="person-picker-birthday"
              />
            </af-form-field>
          </div>
          <af-button
            type="default"
            htmlType="submit"
            [disabled]="busy()"
            data-testid="person-picker-search"
          >
            Search
          </af-button>
        </form>

        @if (lookupError() !== null) {
          <span class="text-xs text-rose-600" data-testid="person-picker-error">
            {{ lookupError() }}
          </span>
        }

        @if (matches().length > 0) {
          <ul
            role="list"
            class="flex flex-col gap-1 list-none p-0 m-0"
            data-testid="person-picker-matches"
          >
            @for (m of matches(); track m.id) {
              <li>
                <button
                  type="button"
                  class="w-full text-left px-3 py-2 bg-white border border-slate-200 hover:border-slate-300 cursor-pointer"
                  (click)="onPin(m)"
                  [attr.data-testid]="'person-picker-match-' + m.id"
                >
                  <span class="font-medium">{{ m.lastname }}, {{ m.firstname }}</span>
                  @if (m.email) {
                    <span class="ml-2 text-slate-500">{{ m.email }}</span>
                  }
                  @if (m.alreadyInThisClub) {
                    <span class="ml-2 text-xs text-amber-700">Already in this club</span>
                  }
                </button>
              </li>
            }
          </ul>
        } @else if (searched()) {
          <span class="text-xs text-slate-500" data-testid="person-picker-no-matches">
            No matching person found.
          </span>
        }
      </div>
    }
  `,
})
export class UserPersonPickerComponent {
  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly personsApi = inject(PersonsService);

  readonly pinned = model<PersonLookupMatch | null>(null);
  readonly disabled = input<boolean>(false);
  readonly cleared = output<void>();

  protected readonly form: PickerForm = this.fb.group({
    email: this.fb.control(''),
    firstname: this.fb.control(''),
    lastname: this.fb.control(''),
    birthday: this.fb.control(''),
  });

  protected readonly busy = signal(false);
  protected readonly searched = signal(false);
  protected readonly matches = signal<readonly PersonLookupMatch[]>([]);
  protected readonly lookupError = signal<string | null>(null);

  protected onSearch(): void {
    const v = this.form.getRawValue();
    const email = v.email.trim();
    const firstname = v.firstname.trim();
    const lastname = v.lastname.trim();
    const birthday = v.birthday.trim();

    const req: PersonLookupRequest = {};
    if (email.length > 0) {
      req.email = email;
    } else if (firstname.length > 0 && lastname.length > 0 && birthday.length > 0) {
      req.firstname = firstname;
      req.lastname = lastname;
      req.birthday = birthday;
    } else {
      this.lookupError.set('Provide email, or all three of first name, last name, and birthday.');
      return;
    }

    this.lookupError.set(null);
    this.busy.set(true);
    this.personsApi
      .lookupPerson(req)
      .pipe(
        catchError((e: HttpErrorResponse) => {
          const body = (e.error ?? null) as { detail?: string; message?: string } | null;
          this.lookupError.set(body?.detail ?? body?.message ?? 'Lookup failed.');
          return of<PersonLookupResult>({ matches: [] });
        }),
      )
      .subscribe((result) => {
        this.matches.set(result.matches ?? []);
        this.searched.set(true);
        this.busy.set(false);
      });
  }

  protected onPin(m: PersonLookupMatch): void {
    this.pinned.set(m);
    this.matches.set([]);
    this.searched.set(false);
  }

  protected onClear(): void {
    this.pinned.set(null);
    this.matches.set([]);
    this.searched.set(false);
    this.form.reset({ email: '', firstname: '', lastname: '', birthday: '' });
    this.cleared.emit();
  }
}
