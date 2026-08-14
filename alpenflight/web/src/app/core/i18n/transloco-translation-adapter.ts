import { Injectable, inject } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';

import type { AppLocale, TranslationAdapter } from '@shared/ui/locale';

@Injectable({ providedIn: 'root' })
export class TranslocoTranslationAdapter implements TranslationAdapter {
  readonly #transloco = inject(TranslocoService);

  setActiveLang(locale: AppLocale): void {
    this.#transloco.setActiveLang(locale);
  }
}
