import type { AppLocale } from '@shared/ui/locale';

/**
 * Canonical Transloco locale → `language.id` (V2 seed UUID) lookup. No
 * `/api/v1/languages` endpoint exists in S-168 — the seeded set is stable
 * since the original V2 migration, and operator picked the
 * Transloco-locale-default approach over scope-expanding to a listitem
 * endpoint.
 *
 * Region-tagged variants (`de-CH`, `fr-CH`, `it-CH`, `rm`) are deferred —
 * not surfaced by the invite/edit picker.
 */
export const LANGUAGE_BY_LOCALE: Readonly<Record<AppLocale, string>> = {
  de: '019e2e15-2c00-77d0-8000-0000000007d0',
  fr: '019e2e15-2c00-77d1-8000-0000000007d1',
  it: '019e2e15-2c00-77d2-8000-0000000007d2',
  en: '019e2e15-2c00-77d3-8000-0000000007d3',
};

export interface LanguageOption {
  readonly id: string;
  readonly label: string;
  readonly locale: AppLocale;
}

export const LANGUAGE_OPTIONS: readonly LanguageOption[] = [
  { id: LANGUAGE_BY_LOCALE.de, label: 'Deutsch', locale: 'de' },
  { id: LANGUAGE_BY_LOCALE.fr, label: 'Français', locale: 'fr' },
  { id: LANGUAGE_BY_LOCALE.it, label: 'Italiano', locale: 'it' },
  { id: LANGUAGE_BY_LOCALE.en, label: 'English', locale: 'en' },
];
