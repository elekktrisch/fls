import { DestroyRef, Injectable, type Signal, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import type { Subscription } from 'rxjs';

import { JoinRequestsService } from '@api/generated/join-requests/join-requests.service';

import { MeEventsService } from '../events';
import { MUTATION_BUS } from '../mutation-bus/mutation-bus';
import { SessionStore } from '../session/session.store';

import { decrement, isPendingSubmit } from './join-requests-badge.logic';

const JOIN_REQUEST_STATUS_CHANGED = 'join-request.status-changed';

/**
 * Live own-club pending join-request count for the nav badge. Constructed by the
 * app shell so the badge tracks on EVERY page, not just `/join-requests`.
 *
 * Seed + deltas:
 * - seed via `listPending` once the principal resolves to a club admin (the
 *   call is admin-only — gating avoids a non-admin 403);
 * - `+1` on each `join-request.status-changed` SSE frame (the admin channel
 *   only receives PENDING submits, S-178 `JoinRequestSseListener`);
 * - `-1` on a `join-request.decided` bus event (the admin's own approve/deny —
 *   not echoed back over their SSE channel);
 * - reset + re-seed on logout / tenant-switch.
 *
 * Reuses J-3's session-scoped {@link MeEventsService} (the same `/api/v1/me/events`
 * stream the pilot join-pending page consumes). The stream's open/close is owned
 * by the session lifecycle; this service only subscribes/unsubscribes.
 */
@Injectable({ providedIn: 'root' })
export class JoinRequestsBadgeService {
  private readonly api = inject(JoinRequestsService);
  private readonly events = inject(MeEventsService);
  private readonly bus = inject(MUTATION_BUS);
  private readonly session = inject(SessionStore);
  private readonly destroyRef = inject(DestroyRef);

  private readonly _count = signal(0);
  /** Pending own-club join requests; rendered as the nav badge count. */
  readonly count: Signal<number> = this._count.asReadonly();

  private sseSub: Subscription | null = null;

  constructor() {
    this.bus.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((evt) => {
      if (evt.kind === 'join-request.decided') {
        this._count.update(decrement);
      } else if (evt.kind === 'session.logout' || evt.kind === 'session.tenantSwitch') {
        this._count.set(0);
        this.stop();
      }
    });

    effect(() => {
      if (this.session.isClubAdmin()) {
        this.start();
      } else {
        this._count.set(0);
        this.stop();
      }
    });
    this.destroyRef.onDestroy(() => this.stop());
  }

  private start(): void {
    if (this.sseSub) {
      return;
    }
    this.api
      .listPending({ status: 'pending' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (items) => this._count.set(items.length),
        // A failed seed leaves the count at zero; an SSE frame still bumps it.
        error: () => undefined,
      });
    this.sseSub = this.events
      .on(JOIN_REQUEST_STATUS_CHANGED)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((event) => {
        if (isPendingSubmit(event.data)) {
          this._count.update((c) => c + 1);
        }
      });
  }

  private stop(): void {
    this.sseSub?.unsubscribe();
    this.sseSub = null;
  }
}
