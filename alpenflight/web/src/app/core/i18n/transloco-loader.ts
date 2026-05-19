import { Injectable } from '@angular/core';
import type { Translation, TranslocoLoader } from '@jsverse/transloco';

import type { AppLocale } from '@shared/ui/locale';

type LocaleLoader = () => Promise<{ default: Translation }>;

const loaders: Record<AppLocale, LocaleLoader> = {
  de: () => import('../../../i18n/de.json'),
  fr: () => import('../../../i18n/fr.json'),
  it: () => import('../../../i18n/it.json'),
  en: () => import('../../../i18n/en.json'),
};

/**
 * Bundled per-locale JSON loader. Each locale is a dynamic-import chunk;
 * esbuild splits one chunk per call site, the PWA service worker
 * (ADR 0015) pre-caches them alongside the rest of the JS bundle. No
 * runtime HTTP fetch — translations ride the deploy artifact.
 */
@Injectable({ providedIn: 'root' })
export class TranslocoBundledLoader implements TranslocoLoader {
  getTranslation(lang: string): Promise<Translation> {
    const load = loaders[lang as AppLocale];
    return load ? load().then((m) => m.default) : Promise.resolve({});
  }
}
