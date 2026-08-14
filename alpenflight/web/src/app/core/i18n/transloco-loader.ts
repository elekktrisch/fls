import { Injectable } from '@angular/core';
import type { Translation, TranslocoLoader } from '@jsverse/transloco';

import type { AppLocale } from '@shared/ui/locale';

import de from '../../../i18n/de';
import en from '../../../i18n/en';
import fr from '../../../i18n/fr';
import it from '../../../i18n/it';

const translations: Record<AppLocale, Translation> = { de, en, fr, it };

@Injectable({ providedIn: 'root' })
export class TranslocoBundledLoader implements TranslocoLoader {
  getTranslation(lang: string): Promise<Translation> {
    return Promise.resolve(translations[lang as AppLocale] ?? {});
  }
}
