import { FormBuilder, Validators } from '@angular/forms';
import { describe, expect, it } from 'vitest';

import { revalidateTree } from './revalidate';

const fb = new FormBuilder().nonNullable;

describe('revalidateTree', () => {
  it('re-runs validators added AFTER construction so the group reflects them', () => {
    const group = fb.group({
      name: fb.control<string | null>(null),
    });
    group.controls.name.addValidators(Validators.required);
    expect(group.valid).toBe(true);

    revalidateTree(group);
    expect(group.controls.name.hasError('required')).toBe(true);
    expect(group.invalid).toBe(true);
  });

  it('recomputes nested groups and the root', () => {
    const form = fb.group({
      top: fb.control<string | null>(null),
      nested: fb.group({
        inner: fb.control<string | null>(null),
      }),
    });
    form.controls.top.addValidators(Validators.required);
    form.controls.nested.controls.inner.addValidators(Validators.required);

    revalidateTree(form);
    expect(form.controls.top.hasError('required')).toBe(true);
    expect(form.controls.nested.controls.inner.hasError('required')).toBe(true);
    expect(form.invalid).toBe(true);
  });

  it('flips a populated control back to invalid when its value is cleared', () => {
    const form = fb.group({
      name: fb.control<string | null>('x', Validators.required),
    });
    revalidateTree(form);
    expect(form.valid).toBe(true);

    form.patchValue({ name: null }, { emitEvent: false });
    revalidateTree(form);
    expect(form.controls.name.hasError('required')).toBe(true);
    expect(form.invalid).toBe(true);
  });

  it('preserves pristine state across the revalidate', () => {
    const form = fb.group({
      name: fb.control<string | null>(null, Validators.required),
    });
    form.patchValue({ name: 'set' }, { emitEvent: false });
    form.markAsPristine();

    revalidateTree(form);
    expect(form.pristine).toBe(true);
  });
});
