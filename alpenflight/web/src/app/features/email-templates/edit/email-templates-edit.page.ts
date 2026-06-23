import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  FormBuilder,
  FormControl,
  type FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

import type { EmailTemplateListItem, EmailTemplateSaveRequest } from '@api/generated/model';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfInputComponent } from '@ui/atoms/af-input';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { liveFieldErrors } from '@shared/util/form';

import { EmailTemplatesStore } from '../email-templates.store';

type TemplateForm = FormGroup<{
  subject: FormControl<string>;
  body: FormControl<string>;
}>;

@Component({
  selector: 'af-email-templates-edit',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    ReactiveFormsModule,
    AfButtonComponent,
    AfInputComponent,
    AfFormFieldComponent,
    AfPageComponent,
    AfPageHeaderComponent,
    AfPageErrorComponent,
  ],
  template: `
    <af-page>
      <af-page-header [title]="templateKey()" [description]="locale()" />

      <af-page-error
        [message]="store.saveError()"
        [retryLabel]="null"
        data-testid="email-template-save-error"
      />

      @if (template(); as t) {
        <form
          [formGroup]="form"
          (ngSubmit)="onSubmit()"
          data-testid="email-template-edit-form"
          class="flex flex-col gap-6"
          novalidate
        >
          <af-form-field
            label="Subject"
            for="EmailSubject"
            [required]="true"
            [errors]="subjectErrors()"
          >
            <af-input inputId="EmailSubject" formControlName="subject" autocomplete="off" />
          </af-form-field>

          <af-form-field label="Body" for="EmailBody" [required]="true" [errors]="bodyErrors()">
            <textarea
              id="EmailBody"
              formControlName="body"
              rows="14"
              class="w-full px-2 py-1 text-sm font-mono border border-slate-300 focus:outline-none focus:ring-2 focus:ring-brand-500"
              data-testid="email-template-body"
            ></textarea>
          </af-form-field>

          <div class="flex gap-2 justify-end pt-4 border-t border-slate-200">
            <af-button
              htmlType="button"
              (clicked)="router.navigateByUrl('/email-templates')"
              data-testid="email-template-cancel-button"
            >
              Cancel
            </af-button>
            @if (isOverride(t)) {
              <af-button
                htmlType="button"
                [disabled]="store.isSaving()"
                (clicked)="onReset()"
                data-testid="email-template-reset-button"
              >
                Reset to default
              </af-button>
            }
            <af-button
              type="primary"
              htmlType="submit"
              [disabled]="form.invalid || store.isSaving()"
              data-testid="email-template-save-button"
            >
              Save
            </af-button>
          </div>
        </form>
      } @else {
        <div
          class="mb-4 px-3 py-2 text-sm text-slate-500 border border-slate-200 bg-slate-50"
          data-testid="email-template-loading"
          role="status"
          aria-live="polite"
        >
          Loading template…
        </div>
      }
    </af-page>
  `,
})
export class EmailTemplatesEditPage {
  protected readonly store = inject(EmailTemplatesStore);
  protected readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);

  private readonly params = toSignal(this.route.paramMap, { requireSync: true });
  protected readonly templateKey = computed(() => this.params().get('templateKey') ?? '');
  protected readonly locale = computed(() => this.params().get('languageLocale') ?? '');

  protected readonly template = computed<EmailTemplateListItem | undefined>(() =>
    this.store
      .items()
      .find((t) => t.templateKey === this.templateKey() && t.languageLocale === this.locale()),
  );

  protected readonly form: TemplateForm = this.fb.group({
    subject: this.fb.nonNullable.control('', [Validators.required, Validators.maxLength(500)]),
    body: this.fb.nonNullable.control('', [Validators.required]),
  });

  protected readonly subjectErrors = liveFieldErrors(this.form.controls.subject);
  protected readonly bodyErrors = liveFieldErrors(this.form.controls.body);

  private readonly submitted = signal(false);
  private readonly observedSaving = signal(false);

  constructor() {
    effect(() => {
      const t = this.template();
      if (!t) return;
      this.form.patchValue({ subject: t.subject ?? '', body: t.body });
    });

    effect(() => {
      if (!this.submitted()) return;
      if (this.store.isSaving()) {
        this.observedSaving.set(true);
        return;
      }
      if (!this.observedSaving() || this.store.saveError()) return;
      this.submitted.set(false);
      this.observedSaving.set(false);
      this.router.navigateByUrl('/email-templates');
    });
  }

  protected isOverride(t: EmailTemplateListItem): boolean {
    return t.source === 'CLUB_OVERRIDE';
  }

  protected onSubmit(): void {
    if (this.form.invalid || this.store.isSaving()) {
      this.form.markAllAsTouched();
      return;
    }
    const request: EmailTemplateSaveRequest = {
      subject: this.form.controls.subject.value.trim(),
      body: this.form.controls.body.value,
    };
    this.submitted.set(true);
    this.store.save({ templateKey: this.templateKey(), languageLocale: this.locale(), request });
  }

  protected onReset(): void {
    if (this.store.isSaving()) return;
    this.submitted.set(true);
    this.store.reset({ templateKey: this.templateKey(), languageLocale: this.locale() });
  }
}
