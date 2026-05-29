import { Dialog } from '@angular/cdk/dialog';
import { ChangeDetectionStrategy, Component, OnDestroy, OnInit, computed, inject } from '@angular/core';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';

import { AfPageComponent } from '@ui/molecules/af-page';

import { DEFAULT_LOCALE } from '../../core/i18n/lang-resolver';
import { SessionStore } from '../../core/session/session.store';

import { emitFunnelEvent } from '../signup/funnel-telemetry';
import { consumeSignupPending } from '../signup/signup-pending';

import { MigrateHandshakeStore } from './migrate-handshake.store';
import { RegenerateConfirmDialogComponent } from './regenerate-confirm-dialog.component';

const JAR_DOWNLOAD_PLACEHOLDER_HREF =
    'https://github.com/elekktrisch/fls/releases?q=alpenflight-export-jar';

/**
 * /migrate/start page (S-140). Owns:
 *
 * <ul>
 *   <li>Mount-restore: {@code GET .../handshake/current} → 200 sets state;
 *       404 falls through to {@code POST .../handshake} per the store.</li>
 *   <li>Regenerate flow: explicit button → CDK confirm dialog → on accept
 *       fires a fresh POST (silently supersedes the prior row).</li>
 *   <li>Public-key surface: copy-friendly textarea + "Download key file"
 *       button writing {@code alpenflight-public-key-<uploadId>.pem}.</li>
 *   <li>Export-tool panel: link to the JAR download (placeholder until
 *       S-139's CI artifact URL exists).</li>
 *   <li>Funnel emission: {@code signup.completed} fires once per
 *       signup-pending session (carried over from the placeholder).</li>
 * </ul>
 */
@Component({
  selector: 'af-migrate-handshake',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AfPageComponent, TranslocoDirective],
  host: { class: 'block' },
  template: `
    <af-page mode="narrow">
      <ng-container *transloco="let t; read: 'migrateHandshake'">
        <section data-testid="migrate-handshake" class="space-y-6">
          <header>
            <h1
              class="text-2xl font-medium text-slate-900 mb-2"
              data-testid="migrate-handshake-headline"
            >
              {{ t('headline') }}
            </h1>
            <p class="text-sm text-slate-500" data-testid="migrate-handshake-tagline">
              {{ t('tagline') }}
            </p>
          </header>

          @if (store.showLoading()) {
            <p data-testid="migrate-handshake-loading" class="text-sm text-slate-500">
              {{ t('loading') }}
            </p>
          } @else if (store.showError()) {
            <p data-testid="migrate-handshake-error" class="text-sm text-red-600">
              {{ t('error') }}
            </p>
          } @else if (store.hasUpload()) {
            <div class="space-y-4">
              <label class="block text-sm font-medium text-slate-700">
                {{ t('pemLabel') }}
              </label>
              <textarea
                readonly
                data-testid="migrate-handshake-pem"
                class="w-full font-mono text-xs border border-slate-200 p-3 min-h-[12rem] tabular"
                [value]="store.upload()?.publicKeyPem ?? ''"
              ></textarea>
              <p class="text-sm text-slate-500" data-testid="migrate-handshake-expires">
                {{ t('expires', { expiresAt: formattedExpiry() }) }}
              </p>
              <div class="flex flex-wrap gap-3">
                <button
                  type="button"
                  data-testid="migrate-handshake-download"
                  class="min-h-[44px] min-w-[44px] px-4 py-2 text-sm border border-slate-300 text-slate-700"
                  (click)="download()"
                >
                  {{ t('download') }}
                </button>
                <button
                  type="button"
                  data-testid="migrate-handshake-regenerate"
                  class="min-h-[44px] min-w-[44px] px-4 py-2 text-sm border border-slate-300 text-slate-700"
                  (click)="askRegenerate()"
                >
                  {{ t('regenerate') }}
                </button>
              </div>
            </div>

            <div
              class="border border-slate-200 p-4 space-y-2"
              data-testid="migrate-handshake-jar-panel"
            >
              <h2 class="text-base font-medium text-slate-900">{{ t('jarPanel.title') }}</h2>
              <a
                [href]="jarHref"
                target="_blank"
                rel="noopener noreferrer"
                data-testid="migrate-handshake-jar-link"
                class="text-sm text-brand-500 underline"
              >
                {{ t('jarPanel.cta') }}
              </a>
            </div>
          }
        </section>
      </ng-container>
    </af-page>
  `,
})
export class MigrateHandshakePageComponent implements OnInit, OnDestroy {
  readonly store = inject(MigrateHandshakeStore);
  readonly jarHref = JAR_DOWNLOAD_PLACEHOLDER_HREF;

  readonly #dialog = inject(Dialog);
  readonly #transloco = inject(TranslocoService);
  readonly #session = inject(SessionStore);

  protected readonly formattedExpiry = computed(() => {
    const expiresAt = this.store.upload()?.expiresAt;
    if (!expiresAt) return '';
    const locale = this.#transloco.getActiveLang() ?? DEFAULT_LOCALE;
    return new Intl.DateTimeFormat(locale, {
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(new Date(expiresAt));
  });

  ngOnInit(): void {
    this.store.restoreOrIssue();
    this.fireSignupCompletedOnce();
  }

  ngOnDestroy(): void {
    this.store.reset();
  }

  askRegenerate(): void {
    const ref = this.#dialog.open<boolean>(RegenerateConfirmDialogComponent, {
      hasBackdrop: true,
      disableClose: false,
      ariaModal: true,
    });
    ref.closed.subscribe((confirmed) => {
      if (confirmed === true) {
        this.store.regenerate();
      }
    });
  }

  download(): void {
    const current = this.store.upload();
    if (!current) return;
    const blob = new Blob([current.publicKeyPem], { type: 'application/x-pem-file' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `alpenflight-public-key-${current.uploadId}.pem`;
    document.body.appendChild(anchor);
    anchor.click();
    document.body.removeChild(anchor);
    URL.revokeObjectURL(url);
  }

  /**
   * Funnel-telemetry parity with the prior placeholder component — emit
   * {@code signup.completed} exactly once per signup round-trip if the
   * pending stamp is present. PII-free per
   * {@code features/signup/funnel-telemetry.ts}.
   */
  private fireSignupCompletedOnce(): void {
    const pending = consumeSignupPending();
    if (!pending) return;
    const actorId = this.#session.authenticatedUser()?.id;
    emitFunnelEvent({
      event_id: 'signup.completed',
      ...(actorId ? { actor_id: actorId } : {}),
      timestamp: new Date().toISOString(),
      properties: {
        idp: pending.idp,
        intent: 'migrate',
      },
    });
  }
}
