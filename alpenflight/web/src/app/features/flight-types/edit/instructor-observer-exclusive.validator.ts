import type { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

/** Group-level error key set when both crew-requirement flags are checked. */
export const INSTRUCTOR_OBSERVER_EXCLUSIVE = 'instructorObserverExclusive';

/**
 * Cross-field validator for the flight-type form: "Instructor required" and
 * "Observer pilot or instructor required" are mutually exclusive — the
 * observer flag is the weaker requirement, so pairing it with the strict
 * instructor flag is contradictory. Mirrors the domain guard in
 * FlightType.updateFlags (the legacy DB CHECK forbade (1,1)); the client
 * validator blocks the submit before the server's 400 ever fires.
 */
export const instructorObserverExclusiveValidator: ValidatorFn = (
  group: AbstractControl,
): ValidationErrors | null => {
  const instructor = group.get('isInstructorRequired')?.value === true;
  const observer = group.get('isObserverPilotOrInstructorRequired')?.value === true;
  return instructor && observer ? { [INSTRUCTOR_OBSERVER_EXCLUSIVE]: true } : null;
};
