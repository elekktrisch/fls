import {
  ChangeDetectionStrategy,
  Component,
  type OnDestroy,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfInputComponent } from '@ui/atoms/af-input';
import { AfFormFieldComponent } from '@ui/molecules/af-form-field';
import { AfPageComponent } from '@ui/molecules/af-page';

import {
  JOIN_CODE_LENGTH,
  JOIN_NOTE_MAX,
  countdownRemaining,
  errorView,
  noteRemaining,
  sanitizeJoinCode,
  type RateLimitWindow,
} from './join-page.logic';
import { JoinStore } from './join.store';

/**
 * Pilot self-serve `/join` screen (T-10). An authenticated principal without a
 * `t_user` enters a club join code (+ optional note) and files a join request.
 * On 201 the store holds the new request and we route to `/join/pending`
 * (T-11). 404 / 409 render inline; a 429 shows a `Retry-After` countdown that
 * disables submit until it elapses.
 *
 * No reactive form: the code field needs sanitize-on-input (uppercase + alphabet
 * filter) so the model is the single source of truth, and the note is a plain
 * length-capped textarea — a `FormGroup` would add ceremony without a payoff.
 */
@Component({
  selector: 'af-join',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AfButtonComponent,
    AfInputComponent,
    AfFormFieldComponent,
    AfPageComponent,
    RouterLink,
    TranslocoDirective,
  ],
  host: { class: 'block' },
  template: `
    <af-page mode="narrow">
      <ng-container *transloco="let t; read: 'join'">
        <section data-testid="join-page" class="space-y-6">
          <header>
            <h1 class="text-2xl font-medium text-slate-900 mb-2" data-testid="join-headline">
              {{ t('headline') }}
            </h1>
            <p class="text-sm text-slate-500" data-testid="join-tagline">{{ t('tagline') }}</p>
          </header>

          <form class="space-y-4" (submit)="submit($event)">
            <af-form-field [label]="t('codeLabel')" for="join-code">
              <af-input
                inputId="join-code"
                data-testid="join-code-input"
                autocomplete="off"
                [placeholder]="t('codePlaceholder')"
                [value]="code()"
                (valueChange)="onCode($event)"
                class="font-mono tracking-widest uppercase"
              />
              @if (view().showInline) {
                <p class="mt-1 text-sm text-red-600" role="alert" data-testid="join-error">
                  {{ view().message }}
                </p>
              }
            </af-form-field>

            <af-form-field [label]="t('noteLabel')" for="join-note">
              <textarea
                id="join-note"
                data-testid="join-note-input"
                rows="4"
                [attr.maxlength]="noteMax"
                [value]="note()"
                (input)="onNote($event)"
                class="w-full px-3 py-2 text-sm bg-white border border-slate-300 text-slate-900 placeholder:text-slate-400 focus:border-brand-500 focus:outline-hidden"
                [placeholder]="t('notePlaceholder')"
              ></textarea>
              <p class="mt-1 text-xs text-slate-400 tabular" data-testid="join-note-counter">
                {{ t('noteRemaining', { count: noteLeft() }) }}
              </p>
            </af-form-field>

            @if (view().showCountdown) {
              <p class="text-sm text-amber-700" role="alert" data-testid="join-countdown">
                {{ t('errors.rateLimited', { seconds: view().countdownSeconds }) }}
              </p>
            }

            <af-button
              type="primary"
              htmlType="submit"
              data-testid="join-submit"
              [loading]="store.isSubmitting()"
              [disabled]="submitDisabled()"
            >
              {{ t('submit') }}
            </af-button>
          </form>

          <a
            routerLink="/migrate/start"
            class="inline-block text-sm text-brand-500 hover:underline"
            data-testid="join-migrate-link"
          >
            {{ t('migrateHint') }}
          </a>
        </section>
      </ng-container>
    </af-page>
  `,
})
export class JoinPageComponent implements OnDestroy {
  protected readonly store = inject(JoinStore);
  readonly #router = inject(Router);

  protected readonly noteMax = JOIN_NOTE_MAX;

  protected readonly code = signal('');
  protected readonly note = signal('');
  protected readonly noteLeft = computed(() => noteRemaining(this.note()));

  // Drives the 429 countdown: a tick signal re-evaluates `view()` each second.
  readonly #now = signal(Date.now());
  #timer: ReturnType<typeof setInterval> | null = null;

  // The live rate-limit window, set when a 429 lands and cleared once elapsed.
  readonly #window = signal<RateLimitWindow | null>(null);

  protected readonly countdown = computed(() => {
    const w = this.#window();
    return w ? countdownRemaining(w, this.#now()) : 0;
  });

  protected readonly view = computed(() => errorView(this.store.submitError(), this.countdown()));
  protected readonly submitDisabled = computed(
    () => this.store.isSubmitting() || this.view().submitDisabled,
  );

  constructor() {
    // A fresh 429 opens a countdown window from its Retry-After value; the
    // ticking `#now` signal closes it once the seconds run out.
    effect(() => {
      const err = this.store.submitError();
      if (err?.kind === 'rate-limited' && err.retryAfterSeconds) {
        this.#window.set({ startedAtMs: Date.now(), retryAfterSeconds: err.retryAfterSeconds });
        this.#now.set(Date.now());
        this.#startTimer();
      }
    });

    // On a 201 the store swaps `submitError` for a held PENDING request — route
    // to the pending page (T-11 owns that screen + the public-club projection).
    // Gate on PENDING: a terminal request the store still holds (a DENIED /
    // WITHDRAWN request the pilot came back from to re-apply) must NOT bounce
    // `/join` straight to `/join/pending` — the pilot is here to enter a new
    // code.
    effect(() => {
      if (this.store.request()?.status === 'PENDING') {
        void this.#router.navigateByUrl('/join/pending');
      }
    });
  }

  protected onCode(raw: string): void {
    this.code.set(sanitizeJoinCode(raw));
  }

  protected onNote(event: Event): void {
    this.note.set((event.target as HTMLTextAreaElement).value);
  }

  protected submit(event: Event): void {
    event.preventDefault();
    if (this.submitDisabled() || this.code().length !== JOIN_CODE_LENGTH) return;
    const note = this.note().trim();
    this.store.submit(this.code(), note.length > 0 ? note : undefined);
  }

  #startTimer(): void {
    this.#timer ??= setInterval(() => {
      this.#now.set(Date.now());
      if (this.countdown() === 0) this.#stopTimer();
    }, 1000);
  }

  #stopTimer(): void {
    if (this.#timer !== null) {
      clearInterval(this.#timer);
      this.#timer = null;
    }
  }

  ngOnDestroy(): void {
    this.#stopTimer();
  }
}
