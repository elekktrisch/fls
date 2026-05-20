import { resolveInitialLang } from './lang-resolver';

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
