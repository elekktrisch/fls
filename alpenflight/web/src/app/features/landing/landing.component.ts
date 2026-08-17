import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { OidcSecurityService } from 'angular-auth-oidc-client';

import { LocaleService } from '@shared/ui/locale';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfIconComponent } from '@ui/atoms/af-icon';
import { AfWordmarkComponent } from '@ui/atoms/af-wordmark';
import { AfLangPickerComponent } from '@ui/molecules/af-lang-picker';

import { emitFunnelEvent } from '../signup/funnel-telemetry';

const DEFAULT_SPLASH_IMAGE = 'splash.jpg';

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
          <a
            routerLink="/lostpassword"
            data-testid="landing-topbar-lost-password"
            class="inline-flex items-center min-h-11 px-1 text-sm font-medium text-brand-600
              hover:text-brand-700 no-underline hover:underline"
          >
            {{ t('actions.lostPassword') }}
          </a>
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

            <div class="flex flex-col md:flex-row md:flex-wrap gap-3">
              <a
                routerLink="/signup"
                [queryParams]="{ intent: 'migrate' }"
                data-testid="landing-cta-migrate"
                data-variant="primary"
                data-size="lg"
                class="inline-flex items-center justify-center gap-2 h-11 px-5 no-underline
                  bg-brand-500 text-white font-medium hover:bg-brand-600"
                (click)="emitCtaClick('migrate')"
              >
                {{ t('actions.migrateFromLegacy') }}
                <af-icon name="arrow-right" [size]="16" />
              </a>
              <a
                routerLink="/demo"
                data-testid="landing-cta-demo"
                data-size="lg"
                class="inline-flex items-center justify-center gap-2 h-11 px-5 no-underline
                  border border-slate-300 text-slate-900 font-medium hover:bg-slate-50"
                (click)="emitCtaClick('demo')"
              >
                {{ t('actions.tryDemo') }}
              </a>
            </div>

            <a
              routerLink="/signup"
              data-testid="landing-cta-request-access"
              class="mt-4 inline-flex text-sm font-medium text-brand-600 hover:text-brand-700 underline"
            >
              {{ t('actions.requestAccess') }}
            </a>

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
  protected readonly effectiveSplashUrl = computed(() => this.splashUrl() ?? DEFAULT_SPLASH_IMAGE);
  protected readonly year = new Date().getFullYear();

  protected signIn(): void {
    this.#oidc.authorize(undefined, {
      // ext: OIDC ui_locales param (Keycloak login UI language)
      customParams: { ui_locales: this.#localeService.current() },
    });
  }

  protected emitCtaClick(ctaId: 'migrate' | 'demo'): void {
    emitFunnelEvent({
      event_id: 'landing.cta_click',
      timestamp: new Date().toISOString(),
      properties: { cta_id: ctaId },
    });
  }
}
