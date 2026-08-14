import type { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

import type { Club } from '../clubs.store';

export interface SlugAvailableOptions {
  readonly entities: () => readonly Club[];
  readonly currentId: () => string | null;
}

const RECIPIENT_SEPARATOR = /[,;\s]+/;
const RECIPIENT_ADDRESS = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

export function emailRecipientList(): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const raw = control.value;
    if (typeof raw !== 'string') {
      return null;
    }
    const addresses = raw.split(RECIPIENT_SEPARATOR).filter((a) => a.length > 0);
    if (addresses.length === 0) {
      return null;
    }
    const allParse = addresses.every((a) => RECIPIENT_ADDRESS.test(a.toLowerCase()));
    return allParse ? null : { email: true };
  };
}

export function slugAvailable(opts: SlugAvailableOptions): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const raw = control.value;
    if (typeof raw !== 'string' || raw.length === 0) {
      return null;
    }
    const needle = raw.trim().toLowerCase();
    const myId = opts.currentId();
    const clash = opts.entities().some((c) => c.id !== myId && c.slug?.toLowerCase() === needle);
    return clash ? { duplicate: true } : null;
  };
}
