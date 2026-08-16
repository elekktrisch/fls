import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { OidcSecurityService } from 'angular-auth-oidc-client';

import { LocaleService } from '@shared/ui/locale';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfIconComponent } from '@ui/atoms/af-icon';
import { AfWordmarkComponent } from '@ui/atoms/af-wordmark';
import { AfLangPickerComponent } from '@ui/molecules/af-lang-picker';

import { handOffToTheKeycloakLoginPageWhoseThemeCarriesTheForgotPasswordLink } from './keycloak-recovery-handoff';

type PendingHandoff = 'start-the-reset' | 'sign-in' | null;

@Component({
  selector: 'af-lostpassword',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    RouterLink,
    TranslocoDirective,
    AfButtonComponent,
    AfIconComponent,
    AfWordmarkComponent,
    AfLangPickerComponent,
  ],
  template: `
    <ng-container *transloco="let t; read: 'lostpassword'">
      <div
        class="min-h-screen bg-white grid grid-rows-[auto_1fr_auto]"
        data-testid="lostpassword-page"
      >
        <header class="h-14 px-4 md:px-6 flex items-center gap-3 border-b border-slate-200">
          <a routerLink="/" class="inline-flex items-center gap-2 text-slate-900 no-underline">
            <af-wordmark />
          </a>
          <span class="flex-1"></span>
          <af-lang-picker [ariaLabel]="t('language')" />
        </header>

        <main class="w-full max-w-[40rem] mx-auto px-4 py-8 md:px-6">
          <h1
            class="m-0 mb-2 text-2xl font-medium tracking-tight text-slate-900"
            data-testid="lostpassword-headline"
          >
            {{ t('headline') }}
          </h1>
          <p
            class="m-0 mb-6 text-base leading-normal text-slate-500"
            data-testid="lostpassword-tagline"
          >
            {{ t('tagline') }}
          </p>

          <p
            class="m-0 mb-6 px-4 py-3 text-sm text-amber-700 bg-amber-50 border border-amber-600"
            data-testid="lostpassword-caution"
          >
            {{ t('caution') }}
          </p>

          <ol
            class="m-0 mb-8 pl-5 list-decimal flex flex-col gap-2 text-sm text-slate-700"
            data-testid="lostpassword-steps"
          >
            <li>{{ t('steps.selectForgotPassword') }}</li>
            <li>{{ t('steps.typeTheAddress') }}</li>
            <li>{{ t('steps.openTheMail') }}</li>
            <li>{{ t('steps.setTheNewPassword') }}</li>
          </ol>

          <div class="flex flex-col gap-3">
            <af-button
              type="primary"
              htmlType="button"
              data-testid="lostpassword-start"
              [disabled]="pending() !== null"
              [loading]="pending() === 'start-the-reset'"
              (clicked)="startTheReset()"
            >
              <div class="flex flex-1 justify-center items-center gap-2">
                {{ t('actions.start') }}
                <af-icon name="arrow-right" [size]="16" />
              </div>
            </af-button>
          </div>

          @if (error()) {
            <p class="mt-4 text-sm text-red-600" role="alert" data-testid="lostpassword-error">
              {{ t('errors.unreachable') }}
            </p>
          }

          <div
            class="mt-8 flex flex-wrap items-baseline gap-x-2 text-sm text-slate-500"
            data-testid="lostpassword-know-the-password"
          >
            <span>{{ t('knowThePassword') }}</span>
            <af-button
              type="link"
              htmlType="button"
              data-testid="lostpassword-sign-in-link"
              [disabled]="pending() !== null"
              [loading]="pending() === 'sign-in'"
              (clicked)="signIn()"
            >
              {{ t('actions.signIn') }}
            </af-button>
          </div>
        </main>

        <footer class="border-t border-slate-200 px-4 md:px-6 py-4 text-xs text-slate-500">
          © {{ year }} AlpenFlight
        </footer>
      </div>
    </ng-container>
  `,
})
export class LostpasswordComponent {
  readonly #oidc = inject(OidcSecurityService);
  readonly #locale = inject(LocaleService);

  protected readonly pending = signal<PendingHandoff>(null);
  protected readonly error = signal(false);
  protected readonly year = new Date().getFullYear();

  protected startTheReset(): void {
    this.#moveTheBrowserToKeycloak('start-the-reset');
  }

  protected signIn(): void {
    this.#moveTheBrowserToKeycloak('sign-in');
  }

  #moveTheBrowserToKeycloak(handoff: Exclude<PendingHandoff, null>): void {
    this.pending.set(handoff);
    this.error.set(false);
    try {
      handOffToTheKeycloakLoginPageWhoseThemeCarriesTheForgotPasswordLink(
        this.#oidc,
        this.#locale.current(),
      );
    } catch (cause) {
      console.error('[lostpassword] authorize failed', cause);
      this.pending.set(null);
      this.error.set(true);
    }
  }
}
