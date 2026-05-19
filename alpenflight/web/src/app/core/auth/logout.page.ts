import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
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
    <div class="flex min-h-screen items-center justify-center p-8">
      <h1 class="text-lg font-normal" aria-live="polite">Abmeldung läuft…</h1>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LogoutPage implements OnInit {
  private readonly oidc = inject(OidcSecurityService);
  private readonly session = inject(SessionStore);

  ngOnInit(): void {
    this.session.logout();
    this.oidc.logoff().subscribe({
      error: () => {
        // Keycloak end_session_endpoint unreachable / CORS-blocked /
        // already-logged-out — don't strand the page. The local session
        // is already cleared above; redirect to the landing route.
        window.location.assign('/');
      },
    });
  }
}
