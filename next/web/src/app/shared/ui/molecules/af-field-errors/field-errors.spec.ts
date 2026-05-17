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
