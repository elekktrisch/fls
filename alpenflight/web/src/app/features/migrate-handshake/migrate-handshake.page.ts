import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfDialogComponent } from '@ui/organisms/af-dialog';

import { DEFAULT_LOCALE } from '@core/i18n/lang-resolver';
import { SessionStore } from '@core/session/session.store';

import { emitFunnelEvent } from '../signup/funnel-telemetry';
import { consumeSignupPending } from '../signup/signup-pending';

import { MigrateHandshakeStore } from './migrate-handshake.store';

/**
 * Placeholder until S-139 publishes the export tool. Centralising the
 * URL behind a named export keeps the swap to the real release URL a
 * one-line edit.
 */
const JAR_DOWNLOAD_PLACEHOLDER_HREF =
  'https://github.com/elekktrisch/fls/releases?q=alpenflight-export-jar';

/**
 * /migrate/start page (S-140). Owns:
 *
 * <ul>
 *   <li>Mount-restore: {@code GET .../handshake/current} → 200 sets state;
 *       404 falls through to {@code POST .../handshake}.</li>
 *   <li>Regenerate flow: explicit button → {@code <af-dialog>} confirm →
 *       on accept fires a fresh POST (silently supersedes the prior row).</li>
 *   <li>Public-key surface: copy-friendly textarea + Copy + Download (writes
 *       {@code alpenflight-public-key-<uploadId>.pem}).</li>
 *   <li>Export-tool panel: link to the JAR download (placeholder URL).</li>
 *   <li>Funnel emission: {@code signup.completed} fires once per
 *       signup-pending session (carried over from the placeholder).</li>
 * </ul>
 *
 * <p>The {@code email_verified} guard is enforced server-side (controller
 * {@code @PreAuthorize}); a SPA-side redirect to {@code /verify-email-pending}
 * is a UX follow-up that needs the destination page first.
 */
@Component({
  selector: 'af-migrate-handshake',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AfButtonComponent, AfDialogComponent, AfPageComponent, TranslocoDirective],
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
            <div class="space-y-3">
              <p data-testid="migrate-handshake-error" class="text-sm text-red-600">
                {{ t('error') }}
              </p>
              <af-button data-testid="migrate-handshake-retry" (clicked)="retry()">
                {{ t('retry') }}
              </af-button>
            </div>
          } @else if (store.hasUpload()) {
            <div class="space-y-4">
              <label for="migrate-handshake-pem" class="block text-sm font-medium text-slate-700">
                {{ t('pemLabel') }}
              </label>
              <!-- min-h-[12rem] is large enough to show ~10 lines of
                   wrapped PEM at the project's mono type scale; no
                   semantic token applies. -->
              <textarea
                id="migrate-handshake-pem"
                readonly
                data-testid="migrate-handshake-pem"
                class="w-full font-mono text-xs border border-slate-200 p-3 min-h-[12rem] tabular"
                [value]="store.upload()?.publicKeyPem ?? ''"
              ></textarea>
              <p class="text-sm text-slate-500" data-testid="migrate-handshake-expires">
                {{ t('expires', { expiresAt: formattedExpiry() }) }}
              </p>
              @if (copyConfirmed()) {
                <p class="text-sm text-emerald-600" data-testid="migrate-handshake-copied">
                  {{ t('copied') }}
                </p>
              }
              @if (regenerateConfirmed()) {
                <p class="text-sm text-emerald-600" data-testid="migrate-handshake-regenerated">
                  {{ t('regenerated') }}
                </p>
              }
              <div class="flex flex-wrap gap-3">
                <af-button
                  type="primary"
                  data-testid="migrate-handshake-download"
                  (clicked)="download()"
                >
                  {{ t('download') }}
                </af-button>
                <af-button data-testid="migrate-handshake-copy" (clicked)="copy()">
                  {{ t('copy') }}
                </af-button>
                <af-button
                  data-testid="migrate-handshake-regenerate"
                  (clicked)="dialogOpen.set(true)"
                >
                  {{ t('regenerate') }}
                </af-button>
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

          <af-dialog
            [visible]="dialogOpen()"
            [title]="t('regenerateConfirm.title')"
            [message]="t('regenerateConfirm.message')"
            [confirmLabel]="t('regenerateConfirm.confirm')"
            [dismissLabel]="t('regenerateConfirm.cancel')"
            (confirm)="confirmRegenerate()"
            (dismiss)="dialogOpen.set(false)"
          />
        </section>
      </ng-container>
    </af-page>
  `,
})
export class MigrateHandshakePageComponent implements OnInit, OnDestroy {
  readonly store = inject(MigrateHandshakeStore);
  readonly jarHref = JAR_DOWNLOAD_PLACEHOLDER_HREF;

  protected readonly dialogOpen = signal(false);
  protected readonly copyConfirmed = signal(false);
  protected readonly regenerateConfirmed = signal(false);

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

  retry(): void {
    this.store.restoreOrIssue();
  }

  confirmRegenerate(): void {
    this.dialogOpen.set(false);
    this.store.regenerate();
    this.regenerateConfirmed.set(true);
  }

  copy(): void {
    const current = this.store.upload();
    if (!current) return;
    navigator.clipboard.writeText(current.publicKeyPem).then(
      () => this.copyConfirmed.set(true),
      () => this.copyConfirmed.set(false),
    );
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
