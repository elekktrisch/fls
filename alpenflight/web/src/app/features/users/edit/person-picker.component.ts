import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  model,
  signal,
} from '@angular/core';
import { FormBuilder, FormControl, ReactiveFormsModule, type FormGroup } from '@angular/forms';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfInputComponent } from '@ui/atoms/af-input';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';

import type { PersonLookupMatch, PersonLookupRequest } from '@api/generated/model';

import { UsersStore } from '../users.store';

type PickerForm = FormGroup<{
  email: FormControl<string>;
  firstname: FormControl<string>;
  lastname: FormControl<string>;
  birthday: FormControl<string>;
}>;

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
      <div class="flex flex-col gap-3 px-3 py-2 border border-slate-200">
        <p class="text-xs text-slate-500">
          Optional. Either email, or all three of first name, last name, birthday.
        </p>
        <form
          [formGroup]="form"
          (ngSubmit)="onSearch()"
          novalidate
          class="flex flex-col gap-3"
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
          <div class="grid grid-cols-3 gap-2" [class.opacity-50]="emailMode()">
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
            [disabled]="!canSearch() || store.lookupBusy()"
            data-testid="person-picker-search"
          >
            Look up
          </af-button>
        </form>

        @if (store.lookupError() !== null) {
          <span class="text-xs text-red-600" data-testid="person-picker-error">
            {{ store.lookupError() }}
          </span>
        }

        @if (store.lookupMatches().length > 0) {
          <ul
            role="list"
            class="flex flex-col gap-1 list-none p-0 m-0"
            data-testid="person-picker-matches"
          >
            @for (m of store.lookupMatches(); track m.id) {
              <li>
                <button
                  type="button"
                  class="w-full text-left min-h-[44px] px-3 py-3 bg-white border border-slate-200 enabled:hover:border-slate-300 disabled:opacity-60 disabled:cursor-not-allowed flex items-center"
                  (click)="onPin(m)"
                  [disabled]="m.alreadyInThisClub"
                  [attr.data-testid]="'person-picker-match-' + m.id"
                >
                  <span>
                    <span class="font-medium">{{ m.lastname }}, {{ m.firstname }}</span>
                    @if (m.email) {
                      <span class="ml-2 text-slate-500">{{ m.email }}</span>
                    }
                    @if (m.alreadyInThisClub) {
                      <span class="ml-2 text-xs text-amber-700">Already in this club</span>
                    }
                  </span>
                </button>
              </li>
            }
          </ul>
        } @else if (store.lookupSearched()) {
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
  protected readonly store = inject(UsersStore);
  private readonly destroyRef = inject(DestroyRef);

  readonly pinned = model<PersonLookupMatch | null>(null);

  protected readonly form: PickerForm = this.fb.group({
    email: this.fb.control(''),
    firstname: this.fb.control(''),
    lastname: this.fb.control(''),
    birthday: this.fb.control(''),
  });

  private readonly formValue = signal(this.form.getRawValue());

  protected readonly emailMode = computed(() => this.formValue().email.trim().length > 0);

  protected readonly canSearch = computed(() => {
    const v = this.formValue();
    if (v.email.trim().length > 0) return true;
    return (
      v.firstname.trim().length > 0 && v.lastname.trim().length > 0 && v.birthday.trim().length > 0
    );
  });

  constructor() {
    this.form.valueChanges.pipe().subscribe(() => {
      this.formValue.set(this.form.getRawValue());
    });
    this.destroyRef.onDestroy(() => this.store.clearLookup());
  }

  protected onSearch(): void {
    if (!this.canSearch()) return;
    const v = this.form.getRawValue();
    const email = v.email.trim();
    const req: PersonLookupRequest =
      email.length > 0
        ? { email }
        : {
            firstname: v.firstname.trim(),
            lastname: v.lastname.trim(),
            birthday: v.birthday.trim(),
          };
    this.store.lookupPerson(req);
  }

  protected onPin(m: PersonLookupMatch): void {
    if (m.alreadyInThisClub) return;
    this.pinned.set(m);
    this.store.clearLookup();
  }

  protected onClear(): void {
    this.pinned.set(null);
    this.store.clearLookup();
    this.form.reset({ email: '', firstname: '', lastname: '', birthday: '' });
  }
}
