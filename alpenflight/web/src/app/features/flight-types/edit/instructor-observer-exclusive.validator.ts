import type { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

export const INSTRUCTOR_OBSERVER_EXCLUSIVE = 'instructorObserverExclusive';

export const instructorObserverExclusiveValidator: ValidatorFn = (
  group: AbstractControl,
): ValidationErrors | null => {
  const instructor = group.get('isInstructorRequired')?.value === true;
  const observer = group.get('isObserverPilotOrInstructorRequired')?.value === true;
  return instructor && observer ? { [INSTRUCTOR_OBSERVER_EXCLUSIVE]: true } : null;
};
