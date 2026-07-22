import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { OidcSecurityService } from 'angular-auth-oidc-client';

import { LocaleService } from '@shared/ui/locale';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfIconComponent } from '@ui/atoms/af-icon';
import { AfWordmarkComponent } from '@ui/atoms/af-wordmark';
import { AfLangPickerComponent } from '@ui/molecules/af-lang-picker';

// Splash placeholder — slate-tinted diagonal-stripes pattern with a
// brand-500 tint, 1600×1200 aspect. The `splashUrl` input accepts a
// per-club override once the whitelabel store lands (AC-DIR-3 follow-up).
const SPLASH_DEFAULT_SVG = 'splash.jpg';

@Component({
  selector: 'af-landing',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AfButtonComponent,
    AfIconComponent,
    AfWordmarkComponent,
    AfLangPickerComponent,
    RouterLink,
    TranslocoDirective,
  ],
  host: { class: 'block' },
  template: `
    <ng-container *transloco="let t; read: 'landing'">
      <div class="min-h-screen bg-white grid grid-rows-[auto_1fr_auto]">
        <header
          class="h-14 px-6 flex items-center gap-3 border-b border-slate-200"
          data-testid="landing-topbar"
        >
          <a routerLink="/" class="inline-flex items-center gap-2 text-slate-900 no-underline">
            <af-wordmark />
          </a>
          <span class="flex-1"></span>
          <af-button
            type="default"
            htmlType="button"
            data-testid="landing-topbar-sign-in"
            (clicked)="signIn()"
          >
            {{ t('actions.signIn') }}
          </af-button>
        </header>

        <section
          class="grid grid-cols-1 md:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]"
          data-testid="landing"
        >
          <div
            class="flex flex-col justify-center px-6 py-16 md:py-20 md:px-8 max-w-xl md:m-auto w-full"
          >
            <p class="m-0 mb-4 text-sm font-medium text-slate-500" data-testid="landing-eyebrow">
              {{ t('eyebrow') }}
            </p>
            <h1
              class="m-0 mb-4 text-3xl md:text-4xl font-medium tracking-tight leading-[1.15] text-slate-900 text-pretty"
              data-testid="landing-headline"
            >
              {{ t('headline') }}
            </h1>
            <p
              class="m-0 mb-6 max-w-[30rem] text-base leading-normal text-slate-500"
              data-testid="landing-tagline"
            >
              {{ t('tagline') }}
            </p>

            <div class="flex flex-wrap gap-3">
              <af-button
                class="flex-1 justify-center"
                type="primary"
                htmlType="button"
                data-testid="landing-sign-in"
                (clicked)="signIn()"
              >
                <div class="flex flex-1 justify-center flex-nowrap items-center gap-2">
                  {{ t('actions.signIn') }}
                  <af-icon name="arrow-right" [size]="16" />
                </div>
              </af-button>
              <af-button
                class="flex-1"
                type="default"
                htmlType="button"
                data-testid="landing-request-access"
                (clicked)="requestAccess()"
              >
                {{ t('actions.requestAccess') }}
              </af-button>
            </div>

            <!-- AC-DIR-5: inline picker below is the sole landing picker;
              landing.routes.ts opts out of the nav-bar (showNavBar false). -->
            <af-button
              type="link"
              htmlType="button"
              data-testid="landing-try-demo"
              (clicked)="tryDemo()"
            >
              {{ t('actions.tryDemo') }}
            </af-button>

            <div
              class="mt-10 pt-5 border-t border-slate-200 grid grid-cols-3 gap-5"
              data-testid="landing-stats"
            >
              <div>
                <div class="tabular text-xl font-medium text-slate-900">34</div>
                <div class="text-xs text-slate-500">{{ t('stats.clubs') }}</div>
              </div>
              <div>
                <div class="tabular text-xl font-medium text-slate-900">1 870</div>
                <div class="text-xs text-slate-500">{{ t('stats.pilots') }}</div>
              </div>
              <div>
                <div class="tabular text-xl font-medium text-slate-900">184 002</div>
                <div class="text-xs text-slate-500">{{ t('stats.flights') }}</div>
              </div>
            </div>

            <af-lang-picker class="mt-8" [ariaLabel]="t('language')" />
          </div>

          <div class="hidden md:block relative border-l border-slate-200 bg-slate-50 min-h-[30rem]">
            <img
              [src]="effectiveSplashUrl()"
              [alt]="t('splashLabel')"
              data-testid="landing-splash"
              class="absolute inset-0 w-full h-full object-cover object-[center_35%]"
            />
          </div>
        </section>

        <footer
          class="border-t border-slate-200 px-6 py-4 flex flex-wrap justify-between gap-3 text-xs text-slate-500"
        >
          <span>© {{ year }} AlpenFlight</span>
          <span class="inline-flex flex-wrap gap-4">
            <a
              href="/status"
              class="text-inherit no-underline hover:text-slate-900 hover:underline"
              >{{ t('footer.status') }}</a
            >
            <a
              href="/docs"
              class="text-inherit no-underline hover:text-slate-900 hover:underline"
              >{{ t('footer.documentation') }}</a
            >
            <a
              href="/legal/imprint"
              class="text-inherit no-underline hover:text-slate-900 hover:underline"
              >{{ t('footer.imprint') }}</a
            >
            <a
              href="/legal/privacy"
              class="text-inherit no-underline hover:text-slate-900 hover:underline"
              >{{ t('footer.privacy') }}</a
            >
          </span>
        </footer>
      </div>
    </ng-container>
  `,
})
export class LandingComponent {
  readonly #oidc = inject(OidcSecurityService);
  readonly #localeService = inject(LocaleService);

  readonly splashUrl = input<string | null>(null);
  protected readonly effectiveSplashUrl = computed(() => this.splashUrl() ?? SPLASH_DEFAULT_SVG);
  protected readonly year = new Date().getFullYear();

  protected signIn(): void {
    // Keycloak hosts the credentials form. ui_locales hints Keycloak's UI
    // language; the server picks it up via the OIDC ui_locales parameter.
    this.#oidc.authorize(undefined, {
      customParams: { ui_locales: this.#localeService.current() },
    });
  }

  protected requestAccess(): void {
    // SaaS self-service signup flow (vision §8 — separate track). Wired
    // to the same OIDC `authorize()` for now; the dedicated signup route
    // lands when the self-service onboarding story does. A `prompt=create`
    // hint nudges a Keycloak realm that supports it; absent that, the
    // standard login screen surfaces a "Register" link.
    this.#oidc.authorize(undefined, {
      customParams: { ui_locales: this.#localeService.current(), prompt: 'create' },
    });
  }

  protected tryDemo(): void {
    // Demo-mode (vision §8) — stubbed until the demo realm lands. Routes
    // to the standard sign-in flow with a demo hint that's ignored until
    // the demo realm exists.
    this.#oidc.authorize(undefined, {
      customParams: { ui_locales: this.#localeService.current(), login_hint: 'demo' },
    });
  }
}
