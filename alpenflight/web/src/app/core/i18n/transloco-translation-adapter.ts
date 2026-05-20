import { Injectable, inject } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';

import type { AppLocale, TranslationAdapter } from '@shared/ui/locale';

/**
 * Bridges `LocaleService` (S-008 seam) to transloco's `setActiveLang`.
 * Bound at app bootstrap by `provideAlpenflightI18n()`.
 */
@Injectable({ providedIn: 'root' })
export class TranslocoTranslationAdapter implements TranslationAdapter {
  readonly #transloco = inject(TranslocoService);

  setActiveLang(locale: AppLocale): void {
    this.#transloco.setActiveLang(locale);
  }
}
