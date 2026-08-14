import { FormControl, FormGroup } from '@angular/forms';
import { describe, expect, it } from 'vitest';

import {
  INSTRUCTOR_OBSERVER_EXCLUSIVE,
  instructorObserverExclusiveValidator,
} from './instructor-observer-exclusive.validator';

function group(instructor: boolean, observer: boolean): FormGroup {
  return new FormGroup(
    {
      isInstructorRequired: new FormControl(instructor, { nonNullable: true }),
      isObserverPilotOrInstructorRequired: new FormControl(observer, { nonNullable: true }),
    },
    { validators: [instructorObserverExclusiveValidator] },
  );
}

describe('instructorObserverExclusiveValidator', () => {
  it('rejects both crew-requirement flags set (the legacy-CHECK (1,1) case)', () => {
    const form = group(true, true);
    expect(form.errors).toEqual({ [INSTRUCTOR_OBSERVER_EXCLUSIVE]: true });
    expect(form.invalid).toBe(true);
  });

  it('accepts each flag alone and neither', () => {
    expect(group(true, false).errors).toBeNull();
    expect(group(false, true).errors).toBeNull();
    expect(group(false, false).errors).toBeNull();
  });

  it('clears the error when one flag is unchecked again', () => {
    const form = group(true, true);
    form.controls['isObserverPilotOrInstructorRequired']?.setValue(false);
    expect(form.errors).toBeNull();
    expect(form.valid).toBe(true);
  });
});
