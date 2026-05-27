import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';

import { SessionStore } from '../../core/session/session.store';

import { emitFunnelEvent } from './funnel-telemetry';
import { consumeSignupPending } from './signup-pending';

// `/migrate/start` placeholder (S-141 replaces with the JAR-download workflow).
// Two concerns owned here:
//   - fire `signup.completed` once per signup round-trip (per S-134 design;
//     S-147 will swap the emitter for the structured pipeline).
//   - render a holding page until S-141 lands the real flow.

@Component({
  selector: 'af-migrate-start',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective],
  host: { class: 'block' },
  template: `
    <ng-container *transloco="let t; read: 'signup.postLanding'">
      <main
        class="min-h-screen bg-white flex items-center justify-center px-6"
        data-testid="migrate-start"
      >
        <section class="w-full max-w-md py-12 text-center">
          <h1
            class="m-0 mb-3 text-2xl font-medium tracking-tight text-slate-900"
            data-testid="migrate-start-headline"
          >
            {{ t('headline') }}
          </h1>
          <p class="m-0 text-sm text-slate-500" data-testid="migrate-start-body">
            {{ t('body') }}
          </p>
        </section>
      </main>
    </ng-container>
  `,
})
export class MigrateStartComponent implements OnInit {
  readonly #session = inject(SessionStore);

  ngOnInit(): void {
    const pending = consumeSignupPending();
    if (!pending) return;
    // authenticatedUser().id is the Keycloak `sub` (UUID) per mapClaimsToUser.
    // Opaque; not the email — verified in the e2e PII assertion.
    const actorId = this.#session.authenticatedUser()?.id;
    emitFunnelEvent({
      event_id: 'signup.completed',
      ...(actorId ? { actor_id: actorId } : {}),
      timestamp: new Date().toISOString(),
      properties: {
        idp: pending.idp,
        intent: 'migrate',
      },
    });
  }
}
