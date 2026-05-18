import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { OidcSecurityService } from 'angular-auth-oidc-client';

import { SessionStore } from '../session/session.store';

/**
 * RP-initiated logout. Clears the SessionStore synchronously first
 * (prevents the route guard re-redirecting through a stale "still
 * authenticated" path mid-logout), then asks the OIDC library to hit
 * Keycloak's `end_session_endpoint` with `id_token_hint`. `logoff()` is
 * idempotent — double-invocation is a no-op once the SSO session is gone.
 */
@Component({
  selector: 'af-logout',
  template: `
    <main class="flex min-h-screen items-center justify-center p-8">
      <p class="text-lg" aria-live="polite">Abmeldung läuft…</p>
    </main>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LogoutPage {
  private readonly oidc = inject(OidcSecurityService);
  private readonly session = inject(SessionStore);

  constructor() {
    this.session.logout();
    this.oidc.logoff().subscribe();
  }
}
