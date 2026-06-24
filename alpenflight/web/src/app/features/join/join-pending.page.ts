import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  Injector,
  type OnInit,
  computed,
  inject,
} from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { filter, switchMap, take } from 'rxjs';

import { MeEventsService } from '@core/events';
import { SessionStore } from '@core/session/session.store';
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
  readonly #session = inject(SessionStore);
  readonly #injector = inject(Injector);
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

  // Set when the pilot withdraws from THIS page: the eager `toJoin()` below
  // already routes them away, so the SSE `withdrawn` echo that lands a moment
  // later must NOT fire a second `/join` navigation — by then a re-submit may
  // already be navigating to `/join/pending`, and the colliding nav logs
  // `AbortError: Transition was skipped`.
  #withdrawHandledLocally = false;

  protected withdraw(): void {
    const id = this.store.request()?.id;
    if (!id) return;
    // The store clears the held request on a successful withdraw. Navigate
    // eagerly so the pilot moves even if their SSE stream lags; the echo's
    // redundant nav is suppressed by the flag.
    this.#withdrawHandledLocally = true;
    this.store.withdraw(id);
    void this.toJoin();
  }

  protected toJoin(): void {
    if (this.#router.url.split('?')[0] === '/join') return;
    void this.#router.navigateByUrl('/join');
  }

  #onStatusChanged(data: string): void {
    switch (actionForStatus(parseStatusChanged(data))) {
      case 'refresh-and-start':
        // Refresh so the new token carries the `clubId` claim, then re-read /me
        // so the SessionStore's `currentClubId` reflects the now-created t_user.
        // Navigate to /start only ONCE `currentClubId` is populated — the
        // refresh-token grant resolves before the `userData()` effect propagates
        // the claim into the store, so navigating eagerly hits /start's tenant
        // gate with a still-null clubId and bounces back to /join (the request
        // is no longer PENDING). Waiting for the tenant to settle closes that
        // graduation race.
        this.#oidc
          .forceRefreshSession()
          .pipe(
            switchMap(() => {
              this.#session.loadMe();
              return toObservable(this.#session.currentClubId, {
                injector: this.#injector,
              }).pipe(
                filter((clubId) => clubId !== null),
                take(1),
              );
            }),
            takeUntilDestroyed(this.#destroyRef),
          )
          .subscribe(() => void this.#router.navigateByUrl('/start'));
        break;
      case 'show-denied':
        // The SSE frame carries no reason — re-read the request to surface the
        // deny reason on the page; the `denied()` computed flips the view.
        this.store.loadMine();
        break;
      case 'to-join':
        // A local withdraw already routed to `/join`; ignore the echo so it
        // can't collide with a re-submit's `/join/pending` navigation.
        if (this.#withdrawHandledLocally) break;
        void this.toJoin();
        break;
      case 'none':
        break;
    }
  }
}
