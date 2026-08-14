import deLocale from '../../../../../i18n/de';
import enLocale from '../../../../../i18n/en';
import frLocale from '../../../../../i18n/fr';
import itLocale from '../../../../../i18n/it';

import { errorTranslationKey, errorsToKeys } from './field-errors';

describe('errorTranslationKey', () => {
  it('maps the canonical Angular validator names', () => {
    expect(errorTranslationKey('required')).toBe('common.errors.required');
    expect(errorTranslationKey('minlength')).toBe('common.errors.minlength');
    expect(errorTranslationKey('maxlength')).toBe('common.errors.maxlength');
    expect(errorTranslationKey('pattern')).toBe('common.errors.pattern');
    expect(errorTranslationKey('email')).toBe('common.errors.email');
    expect(errorTranslationKey('min')).toBe('common.errors.min');
    expect(errorTranslationKey('max')).toBe('common.errors.max');
  });

  it('falls back to `common.errors.<key>` for unknown validators', () => {
    expect(errorTranslationKey('arrivalBeforeDeparture')).toBe(
      'common.errors.arrivalBeforeDeparture',
    );
  });
});

describe('canonical error keys resolve in every shipped locale (J-26 T-08)', () => {
  const VALIDATOR_NAMES = [
    'required',
    'minlength',
    'maxlength',
    'pattern',
    'email',
    'min',
    'max',
    'duplicate',
  ] as const;

  const LOCALES = { de: deLocale, en: enLocale, fr: frLocale, it: itLocale } as const;

  function resolve(tree: object, dottedKey: string): unknown {
    return dottedKey
      .split('.')
      .reduce<unknown>(
        (node, part) =>
          node !== null && typeof node === 'object'
            ? (node as Record<string, unknown>)[part]
            : undefined,
        tree,
      );
  }

  for (const [locale, tree] of Object.entries(LOCALES)) {
    it(`every common.errors.* key has a non-empty ${locale} translation`, () => {
      for (const name of VALIDATOR_NAMES) {
        const key = errorTranslationKey(name);
        const value = resolve(tree, key);
        expect(value, `${locale}: ${key} must be a translated message`).toBeTypeOf('string');
        expect((value as string).length, `${locale}: ${key} must not be empty`).toBeGreaterThan(0);
      }
    });
  }
});

describe('errorsToKeys', () => {
  it('returns empty list when errors is null', () => {
    expect(errorsToKeys(null)).toEqual([]);
    expect(errorsToKeys(undefined)).toEqual([]);
  });

  it('returns translation keys in registration order', () => {
    const keys = errorsToKeys({ required: true, minlength: { requiredLength: 3 } });
    expect(keys).toEqual(['common.errors.required', 'common.errors.minlength']);
  });
});
