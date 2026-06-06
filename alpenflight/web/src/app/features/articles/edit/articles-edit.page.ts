import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import {
  FormBuilder,
  FormControl,
  ReactiveFormsModule,
  Validators,
  type FormGroup,
} from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

import type {
  ArticleCreateRequest,
  ArticleDetail,
  ArticleUpdateRequest,
} from '@api/generated/model';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfInputComponent } from '@ui/atoms/af-input';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { MUTATION_BUS } from '../../../core/mutation-bus/mutation-bus';
import { SessionStore } from '../../../core/session/session.store';
import { ArticlesStore } from '../articles.store';

type ArticleForm = FormGroup<{
  articleNumber: FormControl<string>;
  articleName: FormControl<string>;
  articleInfo: FormControl<string>;
  description: FormControl<string>;
  isActive: FormControl<boolean>;
}>;

@Component({
  selector: 'af-articles-edit',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    ReactiveFormsModule,
    AfFormFieldComponent,
    AfInputComponent,
    AfButtonComponent,
    AfPageComponent,
    AfPageHeaderComponent,
    AfPageErrorComponent,
  ],
  template: `
    <af-page>
      <af-page-header [title]="isCreate() ? 'New article' : 'Edit article'" />

      <af-page-error
        [message]="store.saveError()"
        [retryLabel]="null"
        data-testid="articles-save-error"
      />

      @if (store.isLoadingDetail() && !isCreate()) {
        <div
          class="mb-4 px-3 py-2 text-sm text-slate-500 border border-slate-200 bg-slate-50"
          data-testid="articles-loading"
          role="status"
          aria-live="polite"
        >
          Loading article…
        </div>
      } @else {
        <form
          [formGroup]="form"
          (ngSubmit)="onSubmit()"
          data-testid="articles-edit-form"
          class="flex flex-col gap-6"
          novalidate
        >
          <section class="flex flex-col gap-2" data-testid="articles-section-master">
            <h2
              class="text-xs font-medium text-slate-600 uppercase tracking-wide border-b border-slate-200 pb-1"
            >
              Master data
            </h2>
            <div class="grid grid-cols-1 sm:grid-cols-2 gap-2">
              <af-form-field
                label="Article number"
                for="ArticleNumber"
                [required]="true"
                [errors]="
                  form.controls.articleNumber.touched ? form.controls.articleNumber.errors : null
                "
              >
                <af-input
                  inputId="ArticleNumber"
                  formControlName="articleNumber"
                  autocomplete="off"
                  placeholder="A-100"
                />
              </af-form-field>
              <af-form-field
                label="Name"
                for="ArticleName"
                [required]="true"
                [errors]="
                  form.controls.articleName.touched ? form.controls.articleName.errors : null
                "
              >
                <af-input
                  inputId="ArticleName"
                  formControlName="articleName"
                  autocomplete="off"
                  placeholder="Glider hour"
                />
              </af-form-field>
            </div>
            <af-form-field label="Short info" for="ArticleInfo">
              <af-input
                inputId="ArticleInfo"
                formControlName="articleInfo"
                autocomplete="off"
                placeholder="per flight"
              />
            </af-form-field>
            <af-form-field label="Description" for="ArticleDescription">
              <textarea
                id="ArticleDescription"
                formControlName="description"
                rows="4"
                class="w-full px-2 py-1 text-sm border border-slate-300 focus:outline-none focus:ring-2 focus:ring-brand-500"
                placeholder="Long-form notes (optional)"
              ></textarea>
            </af-form-field>
            <label class="flex items-center gap-2 cursor-pointer select-none">
              <input
                type="checkbox"
                formControlName="isActive"
                class="w-4 h-4 accent-brand-500 cursor-pointer"
                data-testid="articles-flag-active"
              />
              <span>Active</span>
            </label>
            <p class="text-xs text-slate-500 -mt-1">
              Inactive articles are hidden from delivery pickers.
            </p>
          </section>

          <div class="flex gap-2 justify-end pt-4 border-t border-slate-200">
            <af-button
              htmlType="button"
              (clicked)="router.navigateByUrl('/articles')"
              data-testid="articles-cancel-button"
            >
              Cancel
            </af-button>
            @if (canMutate()) {
              <af-button
                type="primary"
                htmlType="submit"
                [disabled]="form.invalid || saveSubmitted()"
                data-testid="articles-save-button"
              >
                Save
              </af-button>
            }
          </div>
        </form>
      }
    </af-page>
  `,
})
export class ArticlesEditPage {
  protected readonly store = inject(ArticlesStore);
  protected readonly session = inject(SessionStore);
  protected readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);
  private readonly bus = inject(MUTATION_BUS);

  private readonly routeId = toSignal(this.route.paramMap, { requireSync: true });
  protected readonly articleId = computed<string | null>(() => this.routeId().get('id'));
  protected readonly isCreate = computed(() => this.articleId() === null);
  protected readonly canMutate = this.session.isClubAdmin;

  protected readonly form: ArticleForm = this.fb.group({
    articleNumber: this.fb.nonNullable.control('', [Validators.required, Validators.maxLength(50)]),
    articleName: this.fb.nonNullable.control('', [Validators.required, Validators.maxLength(250)]),
    articleInfo: this.fb.nonNullable.control('', [Validators.maxLength(250)]),
    description: this.fb.nonNullable.control(''),
    isActive: this.fb.nonNullable.control(true),
  });

  protected readonly saveSubmitted = signal(false);

  constructor() {
    effect(() => {
      const id = this.articleId();
      if (id === null) {
        this.store.select(null);
        return;
      }
      this.store.select(id);
      this.store.loadOne(id);
    });

    effect(() => {
      const detail = this.store.selectedArticle();
      if (!detail) return;
      this.form.patchValue(detailToFormValue(detail));
      if (!this.canMutate()) {
        this.form.disable({ emitEvent: false });
      }
    });

    effect(() => {
      const err = this.store.saveError();
      if (!err) return;
      this.saveSubmitted.set(false);
      if (this.store.saveErrorKind() === 'number-duplicate') {
        this.form.controls.articleNumber.setErrors({
          ...(this.form.controls.articleNumber.errors ?? {}),
          duplicate: true,
        });
        this.form.controls.articleNumber.markAsTouched();
      }
    });

    const destroyRef = inject(DestroyRef);
    this.form.controls.articleNumber.valueChanges
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe(() => {
        if (this.form.controls.articleNumber.hasError('duplicate')) {
          const errs = { ...this.form.controls.articleNumber.errors };
          delete errs['duplicate'];
          this.form.controls.articleNumber.setErrors(Object.keys(errs).length ? errs : null);
        }
        if (this.store.saveErrorKind() === 'number-duplicate') {
          this.store.clearSaveError();
        }
      });

    this.bus.pipe(takeUntilDestroyed(destroyRef)).subscribe((evt) => {
      if (!this.saveSubmitted()) return;
      if (evt.kind === 'article.created' || evt.kind === 'article.updated') {
        this.saveSubmitted.set(false);
        this.router.navigateByUrl('/articles');
      }
    });
  }

  protected onSubmit(): void {
    if (!this.canMutate()) return;
    if (this.form.invalid || this.saveSubmitted()) {
      this.form.markAllAsTouched();
      return;
    }
    this.saveSubmitted.set(true);
    const id = this.articleId();
    if (id === null) {
      this.store.create(formToCreateRequest(this.form));
    } else {
      this.store.update({ id, req: formToUpdateRequest(this.form) });
    }
  }
}

function detailToFormValue(d: ArticleDetail): Partial<{
  articleNumber: string;
  articleName: string;
  articleInfo: string;
  description: string;
  isActive: boolean;
}> {
  return {
    articleNumber: d.articleNumber,
    articleName: d.articleName,
    articleInfo: d.articleInfo ?? '',
    description: d.description ?? '',
    isActive: d.isActive,
  };
}

/**
 * Build the wire payload from the form. {@code ArticleCreateRequest} and
 * {@code ArticleUpdateRequest} are structurally identical today; if they
 * diverge, narrow each caller back to its own builder rather than casting.
 */
function formToWirePayload(form: ArticleForm): {
  articleNumber: string;
  articleName: string;
  articleInfo?: string;
  description?: string;
  isActive: boolean;
} {
  const v = form.getRawValue();
  const payload: {
    articleNumber: string;
    articleName: string;
    articleInfo?: string;
    description?: string;
    isActive: boolean;
  } = {
    articleNumber: v.articleNumber.trim(),
    articleName: v.articleName.trim(),
    isActive: v.isActive,
  };
  const info = v.articleInfo.trim();
  if (info !== '') payload.articleInfo = info;
  const desc = v.description.trim();
  if (desc !== '') payload.description = desc;
  return payload;
}

function formToCreateRequest(form: ArticleForm): ArticleCreateRequest {
  return formToWirePayload(form) satisfies ArticleCreateRequest;
}

function formToUpdateRequest(form: ArticleForm): ArticleUpdateRequest {
  return formToWirePayload(form) satisfies ArticleUpdateRequest;
}
