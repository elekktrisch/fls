import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { OidcSecurityService } from 'angular-auth-oidc-client';

import { LocaleService } from '@shared/ui/locale';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfIconComponent } from '@ui/atoms/af-icon';

import { rememberPostLoginRedirect } from '../../core/auth/post-login-redirect';

import { postSignupLandingPath, resolveSignupIntent } from './signup-intent';
import { markSignupPending } from './signup-pending';

// Default `true`: the Keycloak realm export ships a Google IdP entry, so the
// realm renders a Google button on its own login form anyway. Flip locally
// when running against a dev Keycloak whose `${env:KEYCLOAK_GOOGLE_CLIENT_*}`
// vars are unset — clicking the realm-side Google button surfaces a 500 from
// Keycloak; hiding the SPA-side CTA keeps the demo path looking unbroken.
//
// S-041 prod cutover will hoist this into the env-driven config alongside
// the OIDC `authority` value.
const GOOGLE_SIGNUP_ENABLED = true;

@Component({
  selector: 'af-signup',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AfButtonComponent, AfIconComponent, TranslocoDirective],
  host: { class: 'block' },
  template: `
    <ng-container *transloco="let t; read: 'signup'">
      <main
        class="min-h-screen bg-white flex items-center justify-center px-6"
        data-testid="signup-page"
      >
        <section class="w-full max-w-md py-12">
          <h1
            class="m-0 mb-3 text-2xl font-medium tracking-tight text-slate-900"
            data-testid="signup-headline"
          >
            {{ t('headline') }}
          </h1>
          <p class="m-0 mb-6 text-sm text-slate-500" data-testid="signup-tagline">
            {{ t('tagline') }}
          </p>

          <div class="flex flex-col gap-3">
            <af-button
              type="primary"
              htmlType="button"
              data-testid="signup-local"
              [disabled]="busy()"
              (clicked)="signupLocal()"
            >
              <div class="flex flex-1 justify-center items-center gap-2">
                {{ t('actions.signUp') }}
                <af-icon name="arrow-right" [size]="16" />
              </div>
            </af-button>

            @if (googleEnabled) {
              <af-button
                type="default"
                htmlType="button"
                data-testid="signup-google"
                [disabled]="busy()"
                (clicked)="signupGoogle()"
              >
                {{ t('actions.continueWithGoogle') }}
              </af-button>
            }
          </div>

          <p class="mt-8 text-xs text-slate-500" data-testid="signup-already-have-account">
            {{ t('alreadyHaveAccount') }}
            <a
              class="text-brand-600 underline"
              href="#"
              data-testid="signup-sign-in-link"
              (click)="$event.preventDefault(); signIn()"
              >{{ t('actions.signIn') }}</a
            >
          </p>
        </section>
      </main>
    </ng-container>
  `,
})
export class SignupComponent {
  readonly #oidc = inject(OidcSecurityService);
  readonly #locale = inject(LocaleService);
  readonly #route = inject(ActivatedRoute);

  protected readonly busy = signal(false);
  protected readonly googleEnabled = GOOGLE_SIGNUP_ENABLED;

  protected signupLocal(): void {
    this.#prepareAndAuthorize('local', {
      ui_locales: this.#locale.current(),
      prompt: 'create',
    });
  }

  protected signupGoogle(): void {
    this.#prepareAndAuthorize('google', {
      ui_locales: this.#locale.current(),
      kc_idp_hint: 'google',
    });
  }

  protected signIn(): void {
    // Existing-user fallback — round-trips through Keycloak's login screen,
    // no signup-pending stamp (this isn't a signup attempt).
    this.#oidc.authorize(undefined, {
      customParams: { ui_locales: this.#locale.current() },
    });
  }

  #prepareAndAuthorize(idp: 'local' | 'google', customParams: Record<string, string>): void {
    this.busy.set(true);
    const intent = resolveSignupIntent(this.#route.snapshot.queryParamMap.get('intent'));
    rememberPostLoginRedirect(postSignupLandingPath(intent));
    markSignupPending(idp);
    this.#oidc.authorize(undefined, { customParams });
  }
}
