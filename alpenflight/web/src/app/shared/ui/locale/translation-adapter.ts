import { InjectionToken } from '@angular/core';

import type { AppLocale } from './locale.service';

export interface TranslationAdapter {
  setActiveLang(locale: AppLocale): void;
}

export const TRANSLATION_ADAPTER = new InjectionToken<TranslationAdapter>('TRANSLATION_ADAPTER', {
  providedIn: 'root',
  factory: () => ({ setActiveLang: () => undefined }),
});
