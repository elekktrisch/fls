import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { OidcSecurityService } from 'angular-auth-oidc-client';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfIconComponent } from '@ui/atoms/af-icon';

type Locale = 'de' | 'fr' | 'it' | 'en';

const LOCALE_LABEL: Record<Locale, string> = {
  de: 'DE',
  fr: 'FR',
  it: 'IT',
  en: 'EN',
};

const TAGLINE: Record<Locale, string> = {
  de: 'Flugbuch, Reservationen und Mitglieder — für Schweizer Vereine.',
  fr: 'Carnet de vol, réservations et membres — pour les clubs suisses.',
  it: 'Diario di volo, prenotazioni e soci — per i club svizzeri.',
  en: 'Flight logging, reservations, members — for Swiss clubs.',
};

const LABELS = {
  de: { signIn: 'Anmelden', tryDemo: 'Demo ausprobieren' },
  fr: { signIn: 'Se connecter', tryDemo: 'Essayer la démo' },
  it: { signIn: 'Accedi', tryDemo: 'Prova la demo' },
  en: { signIn: 'Sign in', tryDemo: 'Try the demo' },
} as const satisfies Record<Locale, { signIn: string; tryDemo: string }>;

const FOOTER_LABELS = {
  de: { privacy: 'Datenschutz', imprint: 'Impressum' },
  fr: { privacy: 'Confidentialité', imprint: 'Mentions légales' },
  it: { privacy: 'Privacy', imprint: 'Note legali' },
  en: { privacy: 'Privacy', imprint: 'Imprint' },
} as const satisfies Record<Locale, { privacy: string; imprint: string }>;

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
  imports: [AfButtonComponent, AfIconComponent],
  host: { class: 'block' },
  template: `
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
          <h1 id="af-landing-title" class="text-2xl font-medium tracking-tight text-slate-900 m-0">
            AlpenFlight
          </h1>
        </div>

        <p class="m-0 text-center text-base leading-normal text-slate-500 max-w-[22rem]">
          {{ tagline() }}
        </p>

        <div class="flex flex-col items-stretch gap-3 w-full max-w-[18rem]">
          <af-button
            type="primary"
            htmlType="button"
            data-testid="landing-sign-in"
            (clicked)="signIn()"
          >
            {{ labels().signIn }}
          </af-button>
          <button
            type="button"
            class="bg-transparent border-0 p-2 text-brand-700 underline underline-offset-2 cursor-pointer text-sm hover:text-brand-500"
            (click)="tryDemo()"
          >
            {{ labels().tryDemo }}
          </button>
        </div>

        <nav class="flex gap-1 mt-2" aria-label="Language">
          @for (loc of locales; track loc) {
            <button
              type="button"
              class="bg-transparent border px-2.5 py-1 text-sm tracking-wide cursor-pointer"
              [class.border-transparent]="loc !== locale()"
              [class.text-slate-500]="loc !== locale()"
              [class.hover:text-slate-900]="loc !== locale()"
              [class.text-slate-900]="loc === locale()"
              [class.border-slate-200]="loc === locale()"
              [attr.aria-pressed]="loc === locale()"
              (click)="setLocale(loc)"
            >
              {{ localeLabel(loc) }}
            </button>
          }
        </nav>
      </section>

      <footer
        class="inline-flex flex-wrap gap-2 py-6 px-4 text-sm text-slate-500 text-center justify-center"
      >
        <span>© AlpenFlight</span>
        <span aria-hidden="true">·</span>
        <a
          href="/legal/privacy"
          class="text-inherit no-underline hover:text-slate-900 hover:underline"
          >{{ footerLabels().privacy }}</a
        >
        <span aria-hidden="true">·</span>
        <a
          href="/legal/imprint"
          class="text-inherit no-underline hover:text-slate-900 hover:underline"
          >{{ footerLabels().imprint }}</a
        >
      </footer>
    </main>
  `,
})
export class LandingComponent {
  readonly #oidc = inject(OidcSecurityService);

  protected readonly locale = signal<Locale>('de');
  protected readonly locales: readonly Locale[] = ['de', 'fr', 'it', 'en'];

  protected localeLabel(loc: Locale): string {
    return LOCALE_LABEL[loc];
  }
  protected tagline(): string {
    return TAGLINE[this.locale()];
  }
  protected labels() {
    return LABELS[this.locale()];
  }
  protected footerLabels() {
    return FOOTER_LABELS[this.locale()];
  }
  protected setLocale(loc: Locale): void {
    this.locale.set(loc);
  }

  protected signIn(): void {
    // Keycloak hosts the credentials form. ui_locales hints Keycloak's UI
    // language; the server picks it up via the OIDC ui_locales parameter.
    this.#oidc.authorize(undefined, { customParams: { ui_locales: this.locale() } });
  }

  protected tryDemo(): void {
    // TODO(demo-mode): trigger a demo Keycloak realm or pre-baked guest
    // session per vision §8. Stubbed for v1 — currently routes to the
    // standard sign-in flow with a demo hint that's ignored until the
    // demo realm lands.
    this.#oidc.authorize(undefined, {
      customParams: { ui_locales: this.locale(), login_hint: 'demo' },
    });
  }
}
