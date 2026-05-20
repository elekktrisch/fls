import { Injectable } from '@angular/core';
import type { Translation, TranslocoLoader } from '@jsverse/transloco';

import type { AppLocale } from '@shared/ui/locale';

type LocaleLoader = () => Promise<{ default: Translation }>;

const loaders: Record<AppLocale, LocaleLoader> = {
  de: () => import('../../../i18n/de').then((m) => ({ default: m.default })),
  fr: () => import('../../../i18n/fr').then((m) => ({ default: m.default })),
  it: () => import('../../../i18n/it').then((m) => ({ default: m.default })),
  en: () => import('../../../i18n/en').then((m) => ({ default: m.default })),
};

/**
 * Bundled per-locale loader. Each locale lives in a TypeScript module
 * whose shape is forced by the `Translations` type derived from `de` —
 * adding a key to `de.ts` makes the other locales a compile error
 * until they mirror it. Dynamic import gives esbuild one chunk per
 * locale; the PWA service worker (ADR 0015) pre-caches them alongside
 * the rest of the JS bundle. No runtime HTTP fetch.
 */
@Injectable({ providedIn: 'root' })
export class TranslocoBundledLoader implements TranslocoLoader {
  getTranslation(lang: string): Promise<Translation> {
    const load = loaders[lang as AppLocale];
    return load ? load().then((m) => m.default) : Promise.resolve({});
  }
}
