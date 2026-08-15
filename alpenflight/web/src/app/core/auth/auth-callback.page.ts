import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  selector: 'af-auth-callback',
  template: `
    <div class="flex min-h-screen items-center justify-center p-8">
      <h1 class="text-lg font-normal" aria-live="polite">Anmeldung läuft…</h1>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuthCallbackPage {}
