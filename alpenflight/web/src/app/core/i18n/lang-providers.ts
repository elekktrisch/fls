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
      inject(LocaleService).set(initialLang);
    }),
  ]);
}
