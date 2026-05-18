import type { ValidationErrors } from '@angular/forms';

const CANONICAL_KEYS: Record<string, string> = {
  required: 'common.errors.required',
  minlength: 'common.errors.minlength',
  maxlength: 'common.errors.maxlength',
  pattern: 'common.errors.pattern',
  email: 'common.errors.email',
  min: 'common.errors.min',
  max: 'common.errors.max',
  duplicate: 'common.errors.duplicate',
};

export function errorTranslationKey(key: string): string {
  return CANONICAL_KEYS[key] ?? `common.errors.${key}`;
}

/**
 * Map a `FormControl.errors` object to translation keys, in the order the
 * keys were registered (the first failing rule shown to the user).
 */
export function errorsToKeys(errors: ValidationErrors | null | undefined): readonly string[] {
  if (!errors) return [];
  return Object.keys(errors).map(errorTranslationKey);
}
