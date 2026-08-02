import type { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

import type { Club } from '../clubs.store';

/**
 * Reactive-forms reference convention: validators live in form-definition
 * files, not in components. See `shared/ui/README.md` § Reactive Forms.
 *
 * `slugAvailable` is the canonical async-style validator example for S-007.
 * It runs purely against the in-memory `ClubsStore` entity list — no HTTP
 * — so it returns synchronously and registers as a `ValidatorFn`, not an
 * `AsyncValidatorFn`. Authoritative duplicate detection still lives on the
 * server (409 → mapped to the same `{ duplicate: true }` key in the page's
 * saveError effect, so `<af-field-errors>` renders one consistent message).
 *
 * On first paint before `ClubsStore.loadAll()` resolves, `entities()` is
 * empty and the validator returns `null`. The server 409 still catches the
 * duplicate at submit time.
 */
export interface SlugAvailableOptions {
  /** Signal-like accessor over the loaded club list. */
  readonly entities: () => readonly Club[];
  /** Id of the row currently being edited (excluded from the duplicate scan); `null` on create. */
  readonly currentId: () => string | null;
}

// Mirrors `Club.normalizeEmailList` / `Club.EMAIL_PATTERN` on the aggregate, so
// a recipient list the server would reject is flagged inline instead of coming
// back as a 400 on save.
const RECIPIENT_SEPARATOR = /[,;\s]+/;
const RECIPIENT_ADDRESS = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

/**
 * Validates an organiser-notification recipient list: addresses separated by
 * comma, semicolon or whitespace, each of which must parse. A blank list is
 * valid — it means the club takes no organiser mail, and the registration
 * still succeeds. Reported under the `email` key so the shared error map
 * renders the existing translated message.
 */
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
