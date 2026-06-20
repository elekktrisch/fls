import { type AbstractControl, FormArray, FormGroup } from '@angular/forms';

/**
 * Re-run every control's own validators against its current value, bottom-up,
 * then emit once on the root.
 *
 * Two cases need this that a plain `root.updateValueAndValidity()` doesn't
 * cover, because that only recomputes the root from its children's CACHED
 * status (it never descends):
 *  - validators added AFTER construction (`addValidators` registers but does
 *    not run the rule);
 *  - a group validator that reads a value off a PARENT (e.g. a child group
 *    whose requirement depends on a sibling on the root) — it doesn't re-run
 *    when that parent value changes via an emitEvent:false patch.
 *
 * Walking children first means each parent recomputes from fresh child status;
 * the trailing root emit re-renders a status-tracking signal (the Save gate).
 * Pristine / touched state is untouched.
 */
export function revalidateTree(root: AbstractControl): void {
  recompute(root);
  root.updateValueAndValidity({ onlySelf: false, emitEvent: true });
}

function recompute(control: AbstractControl): void {
  if (control instanceof FormGroup) {
    for (const child of Object.values(control.controls)) recompute(child);
  } else if (control instanceof FormArray) {
    for (const child of control.controls) recompute(child);
  }
  control.updateValueAndValidity({ onlySelf: true, emitEvent: false });
}
