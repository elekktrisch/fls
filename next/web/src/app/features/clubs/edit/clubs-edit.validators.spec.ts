import { FormControl } from '@angular/forms';

import type { Club } from '../clubs.store';
import { slugAvailable } from './clubs-edit.validators';

const seed = (slugs: string[]): readonly Club[] =>
  slugs.map((slug, i) => ({
    id: `clb-019e30c3-2c00-7001-8000-${String(i).padStart(12, '0')}`,
    name: `Club ${slug}`,
    slug,
    clubKey: `KEY${i}`,
    publicRegistrationEnabled: false,
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
    // Editing the row that already owns 'alpha' should NOT flag it as duplicate.
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
