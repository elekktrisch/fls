import { DestroyRef, Injectable, type Signal, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import type { Subscription } from 'rxjs';

import { JoinRequestsService } from '@api/generated/join-requests/join-requests.service';

import { MeEventsService } from '../events';
import { MUTATION_BUS } from '../mutation-bus/mutation-bus';
import { SessionStore } from '../session/session.store';

import { decrement, isPendingSubmit } from './join-requests-badge.logic';

// ext: server SSE `event:` name
const JOIN_REQUEST_STATUS_CHANGED = 'join-request.status-changed';

@Injectable({ providedIn: 'root' })
export class JoinRequestsBadgeService {
  private readonly api = inject(JoinRequestsService);
  private readonly events = inject(MeEventsService);
  private readonly bus = inject(MUTATION_BUS);
  private readonly session = inject(SessionStore);
  private readonly destroyRef = inject(DestroyRef);

  private readonly _count = signal(0);
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
