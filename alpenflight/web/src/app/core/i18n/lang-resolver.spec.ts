import {
  hasExplicitLangOverride,
  localeForLanguageCode,
  resolveInitialLang,
} from './lang-resolver';

describe('resolveInitialLang', () => {
  it('honors a valid ?lang= query param over everything else', () => {
    expect(
      resolveInitialLang({
        urlSearch: '?lang=fr',
        navigatorLanguage: 'de-CH',
      }),
    ).toBe('fr');
  });

  it('lowercases the query param before matching', () => {
    expect(resolveInitialLang({ urlSearch: '?lang=IT' })).toBe('it');
  });

  it('falls through an unsupported query-param value to navigator.language', () => {
    expect(
      resolveInitialLang({
        urlSearch: '?lang=ja',
        navigatorLanguage: 'fr-CH',
      }),
    ).toBe('fr');
  });

  it('matches exact navigator.language when supported', () => {
    expect(resolveInitialLang({ navigatorLanguage: 'en' })).toBe('en');
  });

  it('falls back to base lang when only the region differs (de-CH → de)', () => {
    expect(resolveInitialLang({ navigatorLanguage: 'de-CH' })).toBe('de');
  });

  it('returns the default when nothing else resolves', () => {
    expect(resolveInitialLang({ navigatorLanguage: 'ja-JP' })).toBe('de');
  });

  it('returns the default when no inputs are given', () => {
    expect(resolveInitialLang()).toBe('de');
  });

  it('handles a malformed query string gracefully', () => {
    expect(resolveInitialLang({ urlSearch: '%%not-a-query%%' })).toBe('de');
  });

  it('respects an overridden defaultLang', () => {
    expect(
      resolveInitialLang({
        defaultLang: 'en',
        navigatorLanguage: 'ja',
      }),
    ).toBe('en');
  });

  it('respects an overridden availableLangs', () => {
    expect(
      resolveInitialLang({
        availableLangs: ['de', 'fr'],
        navigatorLanguage: 'it-CH',
      }),
    ).toBe('de');
  });
});

describe('hasExplicitLangOverride', () => {
  it('is true for a supported ?lang= value', () => {
    expect(hasExplicitLangOverride('?lang=fr')).toBe(true);
  });

  it('is true regardless of case', () => {
    expect(hasExplicitLangOverride('?lang=EN')).toBe(true);
  });

  it('is false for an unsupported ?lang= value', () => {
    expect(hasExplicitLangOverride('?lang=ja')).toBe(false);
  });

  it('is false when no ?lang= is present', () => {
    expect(hasExplicitLangOverride('?foo=bar')).toBe(false);
  });

  it('is false for an empty / null search', () => {
    expect(hasExplicitLangOverride('')).toBe(false);
    expect(hasExplicitLangOverride(null)).toBe(false);
    expect(hasExplicitLangOverride(undefined)).toBe(false);
  });

  it('honors an overridden availableLangs', () => {
    expect(hasExplicitLangOverride('?lang=it', ['de', 'fr'])).toBe(false);
  });
});

describe('localeForLanguageCode', () => {
  it('maps an exact supported code', () => {
    expect(localeForLanguageCode('fr')).toBe('fr');
  });

  it('lowercases before matching', () => {
    expect(localeForLanguageCode('EN')).toBe('en');
  });

  it('falls back to the base lang for a region-tagged code (de-CH → de)', () => {
    expect(localeForLanguageCode('de-CH')).toBe('de');
  });

  it('returns null for an unsupported code (rm)', () => {
    expect(localeForLanguageCode('rm')).toBeNull();
  });

  it('returns null for null / undefined / empty', () => {
    expect(localeForLanguageCode(null)).toBeNull();
    expect(localeForLanguageCode(undefined)).toBeNull();
    expect(localeForLanguageCode('')).toBeNull();
  });
});
