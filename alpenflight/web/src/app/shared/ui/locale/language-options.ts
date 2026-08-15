import type { AppLocale } from './locale.service';

export const SEEDED_LANGUAGE_ID_BY_LOCALE: Readonly<Record<AppLocale, string>> = {
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
  { id: SEEDED_LANGUAGE_ID_BY_LOCALE.de, label: 'Deutsch', locale: 'de' },
  { id: SEEDED_LANGUAGE_ID_BY_LOCALE.fr, label: 'Français', locale: 'fr' },
  { id: SEEDED_LANGUAGE_ID_BY_LOCALE.it, label: 'Italiano', locale: 'it' },
  { id: SEEDED_LANGUAGE_ID_BY_LOCALE.en, label: 'English', locale: 'en' },
];

export function localeForLanguageId(languageId: string | null | undefined): AppLocale | null {
  if (languageId == null) {
    return null;
  }
  return LANGUAGE_OPTIONS.find((o) => o.id === languageId)?.locale ?? null;
}
