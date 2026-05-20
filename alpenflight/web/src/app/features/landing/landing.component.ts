import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';
import { OidcSecurityService } from 'angular-auth-oidc-client';

import { LocaleService } from '@shared/ui/locale';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfIconComponent } from '@ui/atoms/af-icon';
import { AfLangPickerComponent } from '@ui/molecules/af-lang-picker';

/**
 * Public landing — sign-in CTA + brand identity. The actual credentials
 * form is hosted by Keycloak; clicking "Sign in" calls
 * `OidcSecurityService.authorize()` which redirects out to Keycloak's
 * hosted UI. See `project-login-in-keycloak.md` (auto-memory).
 */
@Component({
  selector: 'af-landing',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AfButtonComponent, AfIconComponent, AfLangPickerComponent, TranslocoDirective],
  host: { class: 'block' },
  template: `
    <ng-container *transloco="let t; read: 'landing'">
      <main
        class="min-h-screen bg-gradient-to-b from-slate-50 to-white grid grid-rows-[1fr_auto] place-items-center px-4"
        data-testid="landing"
      >
        <section
          class="w-full max-w-sm flex flex-col items-center gap-6 pt-12 pb-8 px-6"
          aria-labelledby="af-landing-title"
        >
          <div class="inline-flex items-center gap-2.5">
            <af-icon name="plane" [size]="40" class="text-brand-500" />
            <h1
              id="af-landing-title"
              class="text-2xl font-medium tracking-tight text-slate-900 m-0"
            >
              AlpenFlight
            </h1>
          </div>

          <p class="m-0 text-center text-base leading-normal text-slate-500 max-w-[22rem]">
            {{ t('tagline') }}
          </p>

          <div class="flex flex-col items-stretch gap-3 w-full max-w-[18rem]">
            <af-button
              type="primary"
              htmlType="button"
              data-testid="landing-sign-in"
              (clicked)="signIn()"
            >
              {{ t('actions.signIn') }}
            </af-button>
            <button
              type="button"
              class="bg-transparent border-0 p-2 text-brand-700 underline underline-offset-2 cursor-pointer text-sm hover:text-brand-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand-500 focus-visible:outline-offset-2"
              (click)="tryDemo()"
            >
              {{ t('actions.tryDemo') }}
            </button>
          </div>

          <af-lang-picker class="mt-2" [ariaLabel]="t('language')" />
        </section>

        <footer
          class="inline-flex flex-wrap gap-2 py-6 px-4 text-sm text-slate-500 text-center justify-center"
        >
          <span>© AlpenFlight</span>
          <span aria-hidden="true">·</span>
          <a
            href="/legal/privacy"
            class="text-inherit no-underline hover:text-slate-900 hover:underline"
            >{{ t('footer.privacy') }}</a
          >
          <span aria-hidden="true">·</span>
          <a
            href="/legal/imprint"
            class="text-inherit no-underline hover:text-slate-900 hover:underline"
            >{{ t('footer.imprint') }}</a
          >
        </footer>
      </main>
    </ng-container>
  `,
})
export class LandingComponent {
  readonly #oidc = inject(OidcSecurityService);
  readonly #localeService = inject(LocaleService);

  protected signIn(): void {
    // Keycloak hosts the credentials form. ui_locales hints Keycloak's UI
    // language; the server picks it up via the OIDC ui_locales parameter.
    this.#oidc.authorize(undefined, {
      customParams: { ui_locales: this.#localeService.current() },
    });
  }

  protected tryDemo(): void {
    // TODO(demo-mode): trigger a demo Keycloak realm or pre-baked guest
    // session per vision §8. Stubbed for v1 — currently routes to the
    // standard sign-in flow with a demo hint that's ignored until the
    // demo realm lands.
    this.#oidc.authorize(undefined, {
      customParams: { ui_locales: this.#localeService.current(), login_hint: 'demo' },
    });
  }
}
