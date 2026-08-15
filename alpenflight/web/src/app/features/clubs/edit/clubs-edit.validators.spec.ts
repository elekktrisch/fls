import { FormControl } from '@angular/forms';

import type { Club } from '../clubs.store';
import { emailRecipientList, slugAvailable } from './clubs-edit.validators';

const seed = (slugs: string[]): readonly Club[] =>
  slugs.map((slug, i) => ({
    id: `clb-019e30c3-2c00-7001-8000-${String(i).padStart(12, '0')}`,
    name: `Club ${slug}`,
    slug,
    clubKey: `KEY${i}`,
    publicRegistrationEnabled: false,
    countryId: '019e2e15-2c00-74be-8000-0000000004be',
    clubStateId: '019e2e15-2c00-7bb8-8000-000000000bb8',
  }));

describe('slugAvailable', () => {
  it('returns null on empty input — required-validator owns empty state', () => {
    const validator = slugAvailable({
      entities: () => seed(['alpha', 'beta']),
      currentId: () => null,
    });
    const ctl = new FormControl('');
    expect(validator(ctl)).toBeNull();
  });

  it('returns null when the slug is unique', () => {
    const validator = slugAvailable({
      entities: () => seed(['alpha', 'beta']),
      currentId: () => null,
    });
    const ctl = new FormControl('gamma');
    expect(validator(ctl)).toBeNull();
  });

  it('returns { duplicate: true } when the slug matches an existing entity', () => {
    const validator = slugAvailable({
      entities: () => seed(['alpha', 'beta']),
      currentId: () => null,
    });
    const ctl = new FormControl('beta');
    expect(validator(ctl)).toEqual({ duplicate: true });
  });

  it('excludes the currently-edited entity from the duplicate check', () => {
    const entities = seed(['alpha', 'beta']);
    const validator = slugAvailable({
      entities: () => entities,
      currentId: () => entities[0]!.id,
    });
    const ctl = new FormControl('alpha');
    expect(validator(ctl)).toBeNull();
  });

  it('comparison is case-insensitive on the normalized form', () => {
    const validator = slugAvailable({
      entities: () => seed(['alpha']),
      currentId: () => null,
    });
    const ctl = new FormControl('ALPHA');
    expect(validator(ctl)).toEqual({ duplicate: true });
  });
});

describe('emailRecipientList', () => {
  const validate = (value: string) => emailRecipientList()(new FormControl(value));

  it('accepts a blank list — no organiser mail is a valid club state', () => {
    expect(validate('')).toBeNull();
    expect(validate('   ')).toBeNull();
  });

  it('accepts one address', () => {
    expect(validate('schnupper@seed.example')).toBeNull();
  });

  it.each([
    ['comma', 'a@seed.example,b@seed.example'],
    ['semicolon', 'a@seed.example;b@seed.example'],
    ['whitespace', 'a@seed.example b@seed.example'],
    ['mixed with padding', ' a@seed.example ;  b@seed.example , c@seed.example '],
  ])('accepts a %s-separated list', (_label, value) => {
    expect(validate(value)).toBeNull();
  });

  it('rejects the list when any single address does not parse', () => {
    expect(validate('a@seed.example, not-an-address')).toEqual({ email: true });
    expect(validate('a@seed.example, missing@dot')).toEqual({ email: true });
  });

  it('rejects a migrated free-text value that was never an address', () => {
    expect(validate('bitte Adresse eintragen')).toEqual({ email: true });
  });
});
