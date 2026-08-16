import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { OidcSecurityService } from 'angular-auth-oidc-client';

import { LocaleService } from '@shared/ui/locale';
import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfIconComponent } from '@ui/atoms/af-icon';
import { AfWordmarkComponent } from '@ui/atoms/af-wordmark';
import { AfLangPickerComponent } from '@ui/molecules/af-lang-picker';

import { readTheOutcomeTheKeycloakEmailActionEndedIn } from './keycloak-email-action-outcome';

const THE_PAGE_THAT_STARTS_THE_PASSWORD_RESET = '/lostpassword';

@Component({
  selector: 'af-confirm',
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
    <ng-container *transloco="let t; read: 'confirm'">
      <div class="min-h-screen bg-white grid grid-rows-[auto_1fr_auto]" data-testid="confirm-page">
        <header class="h-14 px-4 md:px-6 flex items-center gap-3 border-b border-slate-200">
          <a routerLink="/" class="inline-flex items-center gap-2 text-slate-900 no-underline">
            <af-wordmark />
          </a>
          <span class="flex-1"></span>
          <af-lang-picker [ariaLabel]="t('language')" />
        </header>

        <main class="w-full max-w-[40rem] mx-auto px-4 py-8 md:px-6">
          @if (outcome() === 'verified') {
            <section data-testid="confirm-outcome-verified">
              <div class="mb-2 flex items-center gap-2 text-emerald-700">
                <af-icon name="check" [size]="20" />
                <h1
                  class="m-0 text-2xl font-medium tracking-tight text-slate-900"
                  data-testid="confirm-verified-headline"
                >
                  {{ t('verified.headline') }}
                </h1>
              </div>
              <p
                class="m-0 mb-8 text-base leading-normal text-slate-500"
                data-testid="confirm-verified-body"
              >
                {{ t('verified.body') }}
              </p>

              <div class="flex flex-col gap-3">
                <af-button
                  type="primary"
                  htmlType="button"
                  data-testid="confirm-sign-in"
                  [disabled]="handingOffToKeycloak()"
                  [loading]="handingOffToKeycloak()"
                  (clicked)="signIn()"
                >
                  <div class="flex flex-1 justify-center items-center gap-2">
                    {{ t('actions.signIn') }}
                    <af-icon name="arrow-right" [size]="16" />
                  </div>
                </af-button>
              </div>

              @if (keycloakUnreachable()) {
                <p class="mt-4 text-sm text-red-600" role="alert" data-testid="confirm-error">
                  {{ t('errors.unreachable') }}
                </p>
              }
            </section>
          } @else {
            <section data-testid="confirm-outcome-expired">
              <div class="mb-2 flex items-center gap-2 text-amber-700">
                <af-icon name="alert-triangle" [size]="20" />
                <h1
                  class="m-0 text-2xl font-medium tracking-tight text-slate-900"
                  data-testid="confirm-expired-headline"
                >
                  {{ t('expired.headline') }}
                </h1>
              </div>
              <p
                class="m-0 mb-8 text-base leading-normal text-slate-500"
                data-testid="confirm-expired-body"
              >
                {{ t('expired.body') }}
              </p>

              <div class="flex flex-col gap-3">
                <af-button
                  type="primary"
                  htmlType="button"
                  data-testid="confirm-restart-recovery"
                  (clicked)="startTheResetAgain()"
                >
                  <div class="flex flex-1 justify-center items-center gap-2">
                    {{ t('actions.startAgain') }}
                    <af-icon name="arrow-right" [size]="16" />
                  </div>
                </af-button>
              </div>
            </section>
          }
        </main>

        <footer class="border-t border-slate-200 px-4 md:px-6 py-4 text-xs text-slate-500">
          © {{ year }} AlpenFlight
        </footer>
      </div>
    </ng-container>
  `,
})
export class ConfirmComponent {
  readonly #oidc = inject(OidcSecurityService);
  readonly #locale = inject(LocaleService);
  readonly #router = inject(Router);
  readonly #route = inject(ActivatedRoute);

  readonly #queryParameters = toSignal(this.#route.queryParamMap, {
    initialValue: this.#route.snapshot.queryParamMap,
  });

  protected readonly outcome = computed(() =>
    readTheOutcomeTheKeycloakEmailActionEndedIn(this.#queryParameters()),
  );
  protected readonly handingOffToKeycloak = signal(false);
  protected readonly keycloakUnreachable = signal(false);
  protected readonly year = new Date().getFullYear();

  protected signIn(): void {
    this.handingOffToKeycloak.set(true);
    this.keycloakUnreachable.set(false);
    try {
      this.#oidc.authorize(undefined, {
        customParams: { ui_locales: this.#locale.current() },
      });
    } catch (cause) {
      console.error('[confirm] authorize failed', cause);
      this.handingOffToKeycloak.set(false);
      this.keycloakUnreachable.set(true);
    }
  }

  protected startTheResetAgain(): void {
    void this.#router.navigateByUrl(THE_PAGE_THAT_STARTS_THE_PASSWORD_RESET);
  }
}
