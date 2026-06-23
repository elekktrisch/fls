import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  type OnInit,
  computed,
  inject,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { OidcSecurityService } from 'angular-auth-oidc-client';

import { MeEventsService } from '@core/events';
import { formatDdMmYyyy } from '@shared/util/date';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfPageComponent } from '@ui/molecules/af-page';

import { actionForStatus, parseStatusChanged } from './join-pending.logic';
import { JoinStore } from './join.store';

const JOIN_REQUEST_STATUS_CHANGED = 'join-request.status-changed';

/**
 * Pilot `/join/pending` waiting screen (T-11). Reads the pilot's own request
 * (`GET /me/join-request`) on load and renders the requested club's public
 * projection (name / city / logo — null logo falls back to an initials avatar),
 * the submitted-at date, an optional note echo, and a Withdraw action.
 *
 * <p>While open it subscribes to the per-principal SSE channel
 * (`join-request.status-changed`, J-3 infra) and reacts to the committed
 * decision without a reload: <b>approved</b> force-refreshes the OIDC token (so
 * the new {@code clubId} claim lands) then routes to {@code /start};
 * <b>denied</b> re-reads the request to surface the deny reason + a
 * "try a different code" CTA back to {@code /join}; <b>withdrawn</b> returns to
 * {@code /join}.
 */
@Component({
  selector: 'af-join-pending',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AfButtonComponent, AfPageComponent, TranslocoDirective],
  host: { class: 'block' },
  template: `
    <af-page mode="narrow">
      <ng-container *transloco="let t; read: 'join.pending'">
        <section data-testid="join-pending-page" class="space-y-6">
          @if (denied()) {
            <header class="space-y-2">
              <h1 class="text-2xl font-medium text-slate-900">{{ t('deniedHeadline') }}</h1>
              @if (decisionReason(); as reason) {
                <p class="text-sm text-slate-600" data-testid="join-pending-deny-reason">
                  {{ reason }}
                </p>
              }
            </header>
            <af-button type="primary" data-testid="join-pending-retry" (clicked)="toJoin()">
              {{ t('tryDifferent') }}
            </af-button>
          } @else {
            <header class="space-y-1">
              <h1 class="text-2xl font-medium text-slate-900">{{ t('headline') }}</h1>
              <p class="text-sm text-slate-500">{{ t('tagline') }}</p>
            </header>

            <div class="flex items-center gap-4 border border-slate-200 p-4">
              @if (logoUrl(); as logo) {
                <img
                  [src]="logo"
                  alt=""
                  data-testid="join-pending-club-logo"
                  class="h-12 w-12 object-contain"
                />
              } @else {
                <span
                  data-testid="join-pending-club-logo-placeholder"
                  class="flex h-12 w-12 items-center justify-center bg-slate-100 text-sm font-medium text-slate-500"
                >
                  {{ initials() }}
                </span>
              }
              <div>
                <p
                  class="text-base font-medium text-slate-900"
                  data-testid="join-pending-club-name"
                >
                  {{ clubName() }}
                </p>
                @if (city(); as c) {
                  <p class="text-sm text-slate-500" data-testid="join-pending-city">{{ c }}</p>
                }
              </div>
            </div>

            @if (submittedAt(); as submitted) {
              <p class="text-sm text-slate-500 tabular" data-testid="join-pending-submitted">
                {{ t('submittedOn', { date: submitted }) }}
              </p>
            }

            @if (note(); as n) {
              <blockquote
                class="border-l-2 border-slate-200 pl-3 text-sm text-slate-600"
                data-testid="join-pending-note"
              >
                {{ n }}
              </blockquote>
            }

            <af-button type="link" data-testid="join-pending-withdraw" (clicked)="withdraw()">
              {{ t('withdraw') }}
            </af-button>
          }
        </section>
      </ng-container>
    </af-page>
  `,
})
export class JoinPendingPageComponent implements OnInit {
  protected readonly store = inject(JoinStore);
  readonly #router = inject(Router);
  readonly #events = inject(MeEventsService);
  readonly #oidc = inject(OidcSecurityService);
  readonly #destroyRef = inject(DestroyRef);

  protected readonly clubName = computed(() => this.store.request()?.clubName ?? '');
  protected readonly city = computed(() => this.store.request()?.city ?? null);
  protected readonly logoUrl = computed(() => this.store.request()?.logoUrl ?? null);
  protected readonly note = computed(() => this.store.request()?.note ?? null);
  protected readonly decisionReason = computed(() => this.store.request()?.decisionReason ?? null);
  protected readonly submittedAt = computed(() => {
    const created = this.store.request()?.createdOn;
    return created ? formatDdMmYyyy(created) : null;
  });
  protected readonly denied = computed(() => this.store.request()?.status === 'DENIED');

  // First two letters of the club name as a logo fallback (null `logoUrl`).
  protected readonly initials = computed(() => this.clubName().slice(0, 2).toUpperCase());

  ngOnInit(): void {
    this.store.loadMine();

    this.#events
      .on(JOIN_REQUEST_STATUS_CHANGED)
      .pipe(takeUntilDestroyed(this.#destroyRef))
      .subscribe((event) => this.#onStatusChanged(event.data));
  }

  protected withdraw(): void {
    const id = this.store.request()?.id;
    if (!id) return;
    // The store clears the held request on a successful withdraw — route once
    // the SSE `withdrawn` echo (or the cleared request) settles. Navigating
    // here keeps the pilot moving even if their own stream lags.
    this.store.withdraw(id);
    void this.toJoin();
  }

  protected toJoin(): void {
    void this.#router.navigateByUrl('/join');
  }

  #onStatusChanged(data: string): void {
    switch (actionForStatus(parseStatusChanged(data))) {
      case 'refresh-and-start':
        // Refresh so the new token carries the `clubId` claim before /start's
        // tenant gate evaluates; route once the refresh settles.
        this.#oidc
          .forceRefreshSession()
          .pipe(takeUntilDestroyed(this.#destroyRef))
          .subscribe(() => void this.#router.navigateByUrl('/start'));
        break;
      case 'show-denied':
        // The SSE frame carries no reason — re-read the request to surface the
        // deny reason on the page; the `denied()` computed flips the view.
        this.store.loadMine();
        break;
      case 'to-join':
        void this.toJoin();
        break;
      case 'none':
        break;
    }
  }
}
