import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { OidcSecurityService } from 'angular-auth-oidc-client';

import { SessionStore } from '../session/session.store';

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
        window.location.assign('/');
      },
    });
  }
}
