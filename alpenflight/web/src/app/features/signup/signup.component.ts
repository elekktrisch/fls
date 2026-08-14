import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { OidcSecurityService } from 'angular-auth-oidc-client';

import { LocaleService } from '@shared/ui/locale';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfIconComponent } from '@ui/atoms/af-icon';

import { rememberPostLoginRedirect } from '../../core/auth/post-login-redirect';

import { SIGNUP_FEATURE_FLAGS } from './signup.config';
import { postSignupLandingPath, resolveSignupIntent } from './signup-intent';
import { markSignupPending } from './signup-pending';

type Pending = 'local' | 'google' | null;

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
              [disabled]="pending() !== null"
              [loading]="pending() === 'local'"
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
                [disabled]="pending() !== null"
                [loading]="pending() === 'google'"
                (clicked)="signupGoogle()"
              >
                {{ t('actions.continueWithGoogle') }}
              </af-button>
            }
          </div>

          @if (error()) {
            <p class="mt-4 text-sm text-red-600" role="alert" data-testid="signup-error">
              {{ t('errors.unreachable') }}
            </p>
          }

          <div
            class="mt-8 flex flex-wrap items-baseline gap-x-2 text-sm text-slate-500"
            data-testid="signup-already-have-account"
          >
            <span>{{ t('alreadyHaveAccount') }}</span>
            <af-button
              type="link"
              htmlType="button"
              data-testid="signup-sign-in-link"
              [disabled]="pending() !== null"
              (clicked)="signIn()"
            >
              {{ t('actions.signIn') }}
            </af-button>
          </div>
        </section>
      </main>
    </ng-container>
  `,
})
export class SignupComponent {
  readonly #oidc = inject(OidcSecurityService);
  readonly #locale = inject(LocaleService);
  readonly #route = inject(ActivatedRoute);

  protected readonly pending = signal<Pending>(null);
  protected readonly error = signal(false);
  protected readonly googleEnabled = SIGNUP_FEATURE_FLAGS.googleSignupEnabled;

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
    this.#authorizeSafely({ customParams: { ui_locales: this.#locale.current() } });
  }

  #prepareAndAuthorize(idp: 'local' | 'google', customParams: Record<string, string>): void {
    this.pending.set(idp);
    this.error.set(false);
    const intent = resolveSignupIntent(this.#route.snapshot.queryParamMap.get('intent'));
    rememberPostLoginRedirect(postSignupLandingPath(intent));
    markSignupPending(idp);
    this.#authorizeSafely({ customParams });
  }

  #authorizeSafely(args: { customParams: Record<string, string> }): void {
    try {
      this.#oidc.authorize(undefined, args);
    } catch (err) {
      console.error('[signup] authorize failed', err);
      this.pending.set(null);
      this.error.set(true);
    }
  }
}
