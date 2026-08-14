import type { AppLocale } from '@shared/ui/locale';

export const AVAILABLE_LOCALES: readonly AppLocale[] = ['de', 'fr', 'it', 'en'];

export const DEFAULT_LOCALE: AppLocale = 'de';

export interface LangResolveInput {
  readonly availableLangs?: readonly AppLocale[];
  readonly defaultLang?: AppLocale;
  readonly urlSearch?: string | null;
  readonly navigatorLanguage?: string | null;
}

const isLocale = (langs: readonly AppLocale[], value: string): value is AppLocale =>
  (langs as readonly string[]).includes(value);

export function resolveInitialLang(input: LangResolveInput = {}): AppLocale {
  const availableLangs = input.availableLangs ?? AVAILABLE_LOCALES;
  const defaultLang = input.defaultLang ?? DEFAULT_LOCALE;

  if (input.urlSearch) {
    const fromParam = readQueryParam(input.urlSearch);
    if (fromParam && isLocale(availableLangs, fromParam)) {
      return fromParam;
    }
  }

  if (input.navigatorLanguage) {
    const lower = input.navigatorLanguage.toLowerCase();
    if (isLocale(availableLangs, lower)) {
      return lower;
    }
    const base = lower.split('-')[0];
    if (base && isLocale(availableLangs, base)) {
      return base;
    }
  }

  return defaultLang;
}

function readQueryParam(search: string): string | null {
  try {
    const raw = new URLSearchParams(search).get('lang');
    return raw ? raw.toLowerCase() : null;
  } catch {
    return null;
  }
}

export function hasExplicitLangOverride(
  urlSearch: string | null | undefined,
  availableLangs: readonly AppLocale[] = AVAILABLE_LOCALES,
): boolean {
  if (!urlSearch) {
    return false;
  }
  const fromParam = readQueryParam(urlSearch);
  return fromParam !== null && isLocale(availableLangs, fromParam);
}

export function localeForLanguageCode(
  languageCode: string | null | undefined,
  availableLangs: readonly AppLocale[] = AVAILABLE_LOCALES,
): AppLocale | null {
  if (!languageCode) {
    return null;
  }
  const lower = languageCode.toLowerCase();
  if (isLocale(availableLangs, lower)) {
    return lower;
  }
  const base = lower.split('-')[0];
  if (base && isLocale(availableLangs, base)) {
    return base;
  }
  return null;
}
