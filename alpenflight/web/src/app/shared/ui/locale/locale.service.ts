import { Injectable, Signal, inject, signal } from '@angular/core';
import {
  NzI18nService,
  de_DE,
  en_US,
  fr_FR,
  it_IT,
  type NzI18nInterface,
} from 'ng-zorro-antd/i18n';

import { TRANSLATION_ADAPTER } from './translation-adapter';

export type AppLocale = 'de' | 'fr' | 'it' | 'en';

const APP_LOCALES: ReadonlySet<AppLocale> = new Set(['de', 'fr', 'it', 'en']);

const NZ_LOCALES: Record<AppLocale, NzI18nInterface> = {
  de: de_DE,
  fr: fr_FR,
  it: it_IT,
  en: en_US,
};

/**
 * Single switch for both ng-zorro's `NzI18nService` and the app's translation
 * library (wired via `TRANSLATION_ADAPTER` by S-005). Also sets the
 * document's `lang` attribute so screen-readers + browser tooling get the
 * right cue.
 *
 * Unknown locale tokens throw — callers must guard or coerce upstream.
 */
@Injectable({ providedIn: 'root' })
export class LocaleService {
  readonly #nzI18n = inject(NzI18nService);
  readonly #translation = inject(TRANSLATION_ADAPTER);
  readonly #current = signal<AppLocale>('de');

  readonly current: Signal<AppLocale> = this.#current.asReadonly();

  set(locale: AppLocale): void {
    if (!APP_LOCALES.has(locale)) {
      throw new Error(`LocaleService: unsupported locale "${locale}"`);
    }
    this.#nzI18n.setLocale(NZ_LOCALES[locale]);
    this.#translation.setActiveLang(locale);
    if (typeof document !== 'undefined') {
      document.documentElement.lang = locale;
    }
    this.#current.set(locale);
  }
}
