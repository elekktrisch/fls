import type { AppLocale } from './locale.service';

/**
 * Canonical Transloco locale ↔ `language.id` (V2 seed UUID) lookup. No
 * `/api/v1/languages` endpoint exists — the seeded set is stable since the
 * original V2 migration, and the operator picked the Transloco-locale-default
 * approach over scope-expanding to a listitem endpoint.
 *
 * <p>Lives in `shared/ui/locale` (next to {@link LocaleService}) because more
 * than one feature needs the mapping: the admin user invite/edit picker
 * (S-168) AND the `/profile` Account self-edit language selector (J-4). The
 * profile tab also uses {@link localeForLanguageId} to flip the SPA's active
 * locale when a saved language change lands.
 *
 * Region-tagged variants (`de-CH`, `fr-CH`, `it-CH`, `rm`) are deferred —
 * not surfaced by any picker yet.
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

/**
 * Resolve a `language.id` (V2 seed UUID) back to its app locale, or `null`
 * when the id is not one of the four SPA-supported locales (e.g. a migrated
 * user on `de-CH` / `rm`). The `/profile` Account tab uses this to drive
 * {@link LocaleService.set} after a saved language change.
 */
export function localeForLanguageId(languageId: string | null | undefined): AppLocale | null {
  if (languageId == null) {
    return null;
  }
  return LANGUAGE_OPTIONS.find((o) => o.id === languageId)?.locale ?? null;
}
