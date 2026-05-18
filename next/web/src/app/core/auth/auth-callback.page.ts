import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * Landing page for the OIDC Authorization Code redirect. Renders a small
 * "Signing in…" placeholder while `checkAuth()` finishes processing the
 * `?code=…&state=…` query string; the bridge then promotes SessionStore
 * to `'authenticated'` and the router's default route handles redirection.
 */
@Component({
  selector: 'af-auth-callback',
  template: `
    <main class="flex min-h-screen items-center justify-center p-8">
      <p class="text-lg" aria-live="polite">Anmeldung läuft…</p>
    </main>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuthCallbackPage {}
