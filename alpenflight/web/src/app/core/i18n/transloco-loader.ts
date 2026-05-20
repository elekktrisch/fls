import { Injectable } from '@angular/core';
import type { Translation, TranslocoLoader } from '@jsverse/transloco';

import type { AppLocale } from '@shared/ui/locale';

import de from '../../../i18n/de';
import en from '../../../i18n/en';
import fr from '../../../i18n/fr';
import it from '../../../i18n/it';

const translations: Record<AppLocale, Translation> = { de, en, fr, it };

/**
 * Eager-bundled translations loader. Each locale lives as a TypeScript
 * module whose shape is forced by the `Translations` type derived from
 * `de.ts` — adding a key to `de.ts` makes the other locales a compile
 * error until they mirror it. Translations ride the main JS bundle (all
 * four locales together are KB-scale today; eager is cheaper than the
 * dev-mode plumbing for code-splitting them). No HTTP fetch, no server
 * `/api/v1/translations` (C15).
 */
@Injectable({ providedIn: 'root' })
export class TranslocoBundledLoader implements TranslocoLoader {
  getTranslation(lang: string): Promise<Translation> {
    return Promise.resolve(translations[lang as AppLocale] ?? {});
  }
}
