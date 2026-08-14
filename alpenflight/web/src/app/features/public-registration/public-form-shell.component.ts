import {
  ChangeDetectionStrategy,
  Component,
  type OnDestroy,
  effect,
  input,
  output,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfWordmarkComponent } from '@ui/atoms/af-wordmark';
import { AfLangPickerComponent } from '@ui/molecules/af-lang-picker';

export type PublicFormState = 'loading' | 'ready' | 'not-found' | 'unavailable' | 'success';

export type PublicSubmitFailure = 'failed' | 'throttled';

export const SUBMIT_STALL_MS = 3000;

@Component({
  selector: 'af-public-form-shell',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    RouterLink,
    TranslocoDirective,
    AfButtonComponent,
    AfWordmarkComponent,
    AfLangPickerComponent,
  ],
  template: `
    <ng-container *transloco="let t; read: 'publicRegistration'">
      <div class="min-h-screen bg-white grid grid-rows-[auto_1fr_auto]">
        <header class="h-14 px-4 md:px-6 flex items-center gap-3 border-b border-slate-200">
          <a routerLink="/" class="inline-flex items-center gap-2 text-slate-900 no-underline">
            <af-wordmark />
          </a>
          <span class="flex-1"></span>
          <af-lang-picker [ariaLabel]="t('language')" />
        </header>

        <main class="w-full max-w-[40rem] mx-auto px-4 py-8 md:px-6">
          @if (clubName(); as club) {
            <p
              class="m-0 mb-2 text-sm font-medium text-slate-500"
              data-testid="public-registration-club-name"
            >
              {{ club }}
            </p>
          }
          <h1 class="m-0 mb-2 text-2xl font-medium tracking-tight text-slate-900">
            {{ title() }}
          </h1>
          @if (intro()) {
            <p class="m-0 mb-6 text-base leading-normal text-slate-500">{{ intro() }}</p>
          }

          @switch (state()) {
            @case ('loading') {
              <p class="text-sm text-slate-500" data-testid="public-registration-loading">
                {{ t('loading') }}
              </p>
            }
            @case ('not-found') {
              <section
                class="border border-slate-200 p-4"
                data-testid="public-registration-not-found"
              >
                <h2 class="m-0 mb-1 text-base font-medium text-slate-900">
                  {{ t('notFound.title') }}
                </h2>
                <p class="m-0 text-sm text-slate-500">{{ t('notFound.body') }}</p>
              </section>
            }
            @case ('unavailable') {
              <section
                class="border border-slate-200 p-4"
                data-testid="public-registration-unavailable"
              >
                <h2 class="m-0 mb-1 text-base font-medium text-slate-900">
                  {{ t('unavailable.title') }}
                </h2>
                <p class="m-0 text-sm text-slate-500">{{ t('unavailable.body') }}</p>
              </section>
            }
            @case ('success') {
              <section
                class="border border-slate-200 p-4"
                data-testid="public-registration-success"
              >
                <h2 class="m-0 mb-1 text-base font-medium text-slate-900">
                  {{ t('success.title') }}
                </h2>
                <p class="m-0 text-sm text-slate-500">{{ t('success.body') }}</p>
                <ng-content select="[successDetail]" />
              </section>
            }
            @default {
              <form
                novalidate
                data-testid="public-registration-form"
                class="flex flex-col"
                (submit)="onSubmit($event)"
              >
                <ng-content />

                @if (failure() === 'throttled') {
                  <p
                    class="mb-4 px-4 py-3 text-sm text-red-700 bg-red-50 border border-red-600"
                    role="alert"
                    data-testid="public-registration-throttled"
                  >
                    {{ t('errors.throttled', { seconds: retryAfterSeconds() }) }}
                  </p>
                } @else if (failure() === 'failed') {
                  <p
                    class="mb-4 px-4 py-3 text-sm text-red-700 bg-red-50 border border-red-600"
                    role="alert"
                    data-testid="public-registration-error"
                  >
                    {{ t('errors.failed') }}
                  </p>
                }

                @if (stalled()) {
                  <p
                    class="mb-4 text-sm text-amber-700"
                    role="alert"
                    data-testid="public-registration-slow"
                  >
                    {{ t('errors.slow') }}
                  </p>
                }

                <af-button
                  type="primary"
                  htmlType="submit"
                  data-testid="public-registration-submit"
                  [loading]="submitting()"
                  [disabled]="submitting() || submitDisabled()"
                >
                  {{ t('submit') }}
                </af-button>
              </form>
            }
          }

          <a
            routerLink="/"
            class="mt-6 inline-flex items-center min-h-11 text-sm text-brand-600 hover:text-brand-700 no-underline"
            data-testid="public-registration-back"
          >
            {{ t('back') }}
          </a>
        </main>

        <footer class="border-t border-slate-200 px-4 md:px-6 py-4 text-xs text-slate-500">
          © {{ year }} AlpenFlight
        </footer>
      </div>
    </ng-container>
  `,
})
export class PublicFormShellComponent implements OnDestroy {
  readonly title = input.required<string>();
  readonly intro = input<string>('');
  readonly clubName = input<string | null>(null);
  readonly state = input<PublicFormState>('ready');
  readonly submitting = input<boolean>(false);
  readonly submitDisabled = input<boolean>(false);
  readonly failure = input<PublicSubmitFailure | null>(null);
  readonly retryAfterSeconds = input<number>(0);

  readonly submitted = output<void>();

  protected readonly year = new Date().getFullYear();
  protected readonly stalled = signal(false);

  #stallTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    effect(() => {
      this.#clearStallTimer();
      if (!this.submitting()) {
        this.stalled.set(false);
        return;
      }
      this.#stallTimer = setTimeout(() => this.stalled.set(true), SUBMIT_STALL_MS);
    });
  }

  protected onSubmit(event: Event): void {
    event.preventDefault();
    if (this.submitting() || this.submitDisabled()) return;
    this.submitted.emit();
  }

  #clearStallTimer(): void {
    if (this.#stallTimer !== null) {
      clearTimeout(this.#stallTimer);
      this.#stallTimer = null;
    }
  }

  ngOnDestroy(): void {
    this.#clearStallTimer();
  }
}
