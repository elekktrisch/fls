import { type AbstractControl, FormArray, FormGroup } from '@angular/forms';

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
