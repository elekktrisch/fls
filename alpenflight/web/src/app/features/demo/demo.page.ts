import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  computed,
  inject,
  signal,
} from '@angular/core';
import { DestroyRef } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';

import { DemoSessionService } from '@api/generated/demo-session/demo-session.service';
import type { DemoSessionResponse } from '@api/generated/model';
import { applyClaimsToSession } from '@core/auth/oidc-session-bridge';
import {
  DemoSeatSession,
  claimsOfAccessToken,
  seatBusyProblemOf,
} from '@core/session/demo-seat.session';
import { SessionStore } from '@core/session/session.store';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfIconComponent } from '@ui/atoms/af-icon';

const PATH_THE_LEASED_SEAT_OPENS_ON = '/start';

const MILLISECONDS_A_LEASE_MAY_TAKE_BEFORE_THE_VISITOR_NEEDS_A_PROGRESS_NOTICE = 300;

type DemoEntryState = 'idle' | 'leasing' | 'seatBusy' | 'unreachable';

@Component({
  selector: 'af-demo',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AfButtonComponent, AfIconComponent, RouterLink, TranslocoDirective],
  host: { class: 'block' },
  template: `
    <ng-container *transloco="let t; read: 'demo'">
      <main
        class="min-h-screen flex items-center justify-center px-6 py-12 bg-white"
        data-testid="demo-page"
      >
        <section class="w-full max-w-md flex flex-col">
          <p class="m-0 mb-2 text-sm font-medium text-slate-500">{{ t('eyebrow') }}</p>
          <h1 class="m-0 mb-3 text-2xl font-medium tracking-tight text-slate-900">
            {{ t('headline') }}
          </h1>
          <p class="m-0 mb-6 text-base leading-normal text-slate-500">{{ t('body') }}</p>

          <af-button
            type="primary"
            htmlType="button"
            data-testid="demo-start"
            [disabled]="leaseIsUnderway()"
            [loading]="leaseIsUnderway()"
            (clicked)="startTheDemo()"
          >
            <div class="flex flex-1 justify-center items-center gap-2">
              {{ t('actions.start') }}
              <af-icon name="arrow-right" [size]="16" />
            </div>
          </af-button>

          @if (progressNoticeIsVisible()) {
            <p class="m-0 mt-4 text-sm text-slate-500" role="status" data-testid="demo-preparing">
              {{ t('preparing') }}
            </p>
          }

          @if (state() === 'seatBusy') {
            <div
              class="mt-6 border border-slate-200 p-4 flex flex-col gap-2"
              role="alert"
              data-testid="demo-seat-busy"
            >
              <p class="m-0 flex items-center gap-2 text-sm font-medium text-slate-900">
                <af-icon name="alert-triangle" [size]="16" />
                {{ t('seatBusy.title') }}
              </p>
              <p class="m-0 text-sm text-slate-500" data-testid="demo-seat-busy-reason">
                {{ seatBusyReason() || t('seatBusy.reasonFallback') }}
              </p>
              <p class="m-0 text-sm text-slate-500">{{ t('seatBusy.retry') }}</p>
            </div>
          }

          @if (state() === 'unreachable') {
            <p class="m-0 mt-6 text-sm text-red-600" role="alert" data-testid="demo-unreachable">
              {{ t('unreachable') }}
            </p>
          }

          <a
            routerLink="/"
            class="mt-8 inline-flex items-center gap-2 text-brand-700 hover:text-brand-500 no-underline focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand-500 focus-visible:outline-offset-2"
            data-testid="demo-back"
          >
            <af-icon name="arrow-left" [size]="16" />
            <span>{{ t('actions.back') }}</span>
          </a>
        </section>
      </main>
    </ng-container>
  `,
})
export class DemoPage implements OnDestroy {
  readonly #demoSessions = inject(DemoSessionService);
  readonly #demoSeat = inject(DemoSeatSession);
  readonly #session = inject(SessionStore);
  readonly #router = inject(Router);
  readonly #destroyRef = inject(DestroyRef);

  protected readonly state = signal<DemoEntryState>('idle');
  protected readonly seatBusyReason = signal('');
  protected readonly progressNoticeIsVisible = signal(false);
  protected readonly leaseIsUnderway = computed(() => this.state() === 'leasing');

  #progressNoticeTimer: ReturnType<typeof setTimeout> | null = null;

  ngOnDestroy(): void {
    this.#cancelTheProgressNotice();
  }

  protected startTheDemo(): void {
    if (this.leaseIsUnderway()) {
      return;
    }
    this.state.set('leasing');
    this.seatBusyReason.set('');
    this.#showTheProgressNoticeOnlyIfTheLeaseOutlastsAGlance();
    this.#demoSessions
      .startDemoSession()
      .pipe(takeUntilDestroyed(this.#destroyRef))
      .subscribe({
        next: (leased) => this.#openTheLeasedSeat(leased),
        error: (failure: unknown) => this.#tellTheVisitorWhyTheDemoCannotStart(failure),
      });
  }

  #openTheLeasedSeat(leased: DemoSessionResponse): void {
    this.#cancelTheProgressNotice();
    if (leased.accessToken === undefined) {
      this.state.set('unreachable');
      return;
    }
    this.#demoSeat.hold(leased.accessToken);
    applyClaimsToSession(claimsOfAccessToken(leased.accessToken), this.#session);
    void this.#router.navigateByUrl(PATH_THE_LEASED_SEAT_OPENS_ON);
  }

  #tellTheVisitorWhyTheDemoCannotStart(failure: unknown): void {
    this.#cancelTheProgressNotice();
    const seatBusy = seatBusyProblemOf(failure);
    if (seatBusy === null) {
      this.state.set('unreachable');
      return;
    }
    this.seatBusyReason.set(seatBusy.detail ?? '');
    this.state.set('seatBusy');
  }

  #showTheProgressNoticeOnlyIfTheLeaseOutlastsAGlance(): void {
    this.#cancelTheProgressNotice();
    this.#progressNoticeTimer = setTimeout(
      () => this.progressNoticeIsVisible.set(true),
      MILLISECONDS_A_LEASE_MAY_TAKE_BEFORE_THE_VISITOR_NEEDS_A_PROGRESS_NOTICE,
    );
  }

  #cancelTheProgressNotice(): void {
    if (this.#progressNoticeTimer !== null) {
      clearTimeout(this.#progressNoticeTimer);
      this.#progressNoticeTimer = null;
    }
    this.progressNoticeIsVisible.set(false);
  }
}
