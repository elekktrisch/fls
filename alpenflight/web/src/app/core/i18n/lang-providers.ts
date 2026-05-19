import {
  type EnvironmentProviders,
  inject,
  isDevMode,
  makeEnvironmentProviders,
  provideAppInitializer,
} from '@angular/core';
import { provideTransloco } from '@jsverse/transloco';

import { LocaleService, TRANSLATION_ADAPTER } from '@shared/ui/locale';

import { AVAILABLE_LOCALES, DEFAULT_LOCALE, resolveInitialLang } from './lang-resolver';
import { TranslocoBundledLoader } from './transloco-loader';
import { TranslocoTranslationAdapter } from './transloco-translation-adapter';

/**
 * Wires transloco + binds it to the `LocaleService` seam shipped by S-008.
 * Translations live as bundled JSON chunks per locale; no server-side
 * translations endpoint, no static `/i18n/*` fetch (C15).
 */
export function provideAlpenflightI18n(): EnvironmentProviders {
  const initialLang = resolveInitialLang({
    urlSearch: typeof window !== 'undefined' ? window.location.search : null,
    navigatorLanguage: typeof navigator !== 'undefined' ? navigator.language : null,
  });

  return makeEnvironmentProviders([
    provideTransloco({
      config: {
        availableLangs: [...AVAILABLE_LOCALES],
        defaultLang: initialLang,
        fallbackLang: DEFAULT_LOCALE,
        reRenderOnLangChange: true,
        prodMode: !isDevMode(),
        missingHandler: {
          useFallbackTranslation: true,
          allowEmpty: false,
          logMissingKey: isDevMode(),
        },
      },
      loader: TranslocoBundledLoader,
    }),
    { provide: TRANSLATION_ADAPTER, useExisting: TranslocoTranslationAdapter },
    provideAppInitializer(() => {
      // Drive the LocaleService once at bootstrap so ng-zorro,
      // transloco, and `<html lang>` all reflect the resolved initial
      // lang together — the service is the single switch (see
      // shared/ui/locale/locale.service.ts).
      inject(LocaleService).set(initialLang);
    }),
  ]);
}
