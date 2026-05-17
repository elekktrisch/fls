import { InjectionToken } from '@angular/core';

import type { AppLocale } from './locale.service';

/**
 * Seam between `LocaleService` and the app's translation library.
 *
 * S-008 ships a default no-op adapter so the kit is usable before S-005
 * picks transloco / @angular/localize. S-005 replaces the provider with a
 * real implementation that wires its translation service.
 */
export interface TranslationAdapter {
  setActiveLang(locale: AppLocale): void;
}

export const TRANSLATION_ADAPTER = new InjectionToken<TranslationAdapter>('TRANSLATION_ADAPTER', {
  providedIn: 'root',
  factory: () => ({ setActiveLang: () => undefined }),
});
