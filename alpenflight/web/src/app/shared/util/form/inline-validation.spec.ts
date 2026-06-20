import { FormControl, FormGroup, Validators } from '@angular/forms';
import { Subject } from 'rxjs';
import { TestScheduler } from 'rxjs/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { liveFieldErrors$, mergeFieldErrors } from './inline-validation';
import { revalidateTree } from './revalidate';

describe('mergeFieldErrors', () => {
  it('returns null when there are no client or async errors', () => {
    expect(mergeFieldErrors(null, null)).toBeNull();
    expect(mergeFieldErrors({}, undefined)).toBeNull();
    expect(mergeFieldErrors(undefined, {})).toBeNull();
  });

  it('returns the client errors when there is no async error', () => {
    expect(mergeFieldErrors({ required: true }, null)).toEqual({ required: true });
  });

  it('returns the async error when there is no client error (the …/validate extension point)', () => {
    expect(mergeFieldErrors(null, { duplicate: true })).toEqual({ duplicate: true });
  });

  it('merges client + async into one slot, client keys first', () => {
    expect(mergeFieldErrors({ required: true }, { overlap: true })).toEqual({
      required: true,
      overlap: true,
    });
  });

  it('never lets an async key mask a client key of the same name', () => {
    const merged = mergeFieldErrors({ duplicate: 'client' }, { duplicate: 'server' });
    expect(merged).toEqual({ duplicate: 'client' });
  });
});

describe('liveFieldErrors$', () => {
  let scheduler: TestScheduler;

  beforeEach(() => {
    scheduler = new TestScheduler((actual, expected) => {
      expect(actual).toEqual(expected);
    });
  });

  it('emits the control errors after ~200ms of a value change (debounced)', () => {
    scheduler.run(({ expectObservable, cold }) => {
      const control = new FormControl('', { validators: [Validators.required] });
      // The control starts invalid (required, empty). A value typed at frame 10
      // makes it valid; the debounced stream should reflect that ~200ms later.
      cold('----------a|').subscribe(() => control.setValue('x'));

      // startWith → initial errors at frame 0 ({ required }); then nothing until
      // 200ms (200 frames) after the frame-10 change, when errors are null.
      expectObservable(liveFieldErrors$(control, { debounceMs: 200 })).toBe('a 209ms b', {
        a: { required: true },
        b: null,
      });
    });
  });

  it('clears the error (debounced) when an invalid value becomes valid', () => {
    scheduler.run(({ expectObservable, cold }) => {
      const control = new FormControl('seed', { validators: [Validators.required] });
      cold('--a|').subscribe(() => control.setValue(''));

      // Initially valid (null); after clearing at frame 2 + 200ms debounce it is
      // invalid again ({ required }).
      expectObservable(liveFieldErrors$(control, { debounceMs: 200 })).toBe('a 201ms b', {
        a: null,
        b: { required: true },
      });
    });
  });

  it('de-dupes identical consecutive error states (distinctUntilChanged)', () => {
    scheduler.run(({ expectObservable, cold }) => {
      const control = new FormControl('a', { validators: [Validators.required] });
      // Two value changes that both leave the control valid (null errors) must
      // not produce two `null` emissions after the initial one.
      cold('--a--b|').subscribe((v) => control.setValue(v === 'a' ? 'b' : 'c'));

      expectObservable(liveFieldErrors$(control, { debounceMs: 200 })).toBe('a', {
        a: null,
      });
    });
  });

  it('merges an async/server error into the same slot as it arrives', () => {
    scheduler.run(({ expectObservable, cold }) => {
      const control = new FormControl('x', { validators: [Validators.required] });
      const asyncErrors$ = new Subject<{ overlap: true } | null>();
      // Server error arrives at frame 5 (no value change, so no client debounce).
      cold('-----a|').subscribe(() => asyncErrors$.next({ overlap: true }));

      expectObservable(liveFieldErrors$(control, { debounceMs: 200, asyncErrors$ })).toBe(
        'a----b',
        {
          a: null,
          b: { overlap: true },
        },
      );
    });
  });

  it('re-reads errors after a post-hydrate revalidate that fires only the root status', () => {
    // Reopen-shows-invalid guard: a required control is built empty (errors
    // `{ required }`), the live-errors stream starts with that, then an
    // `emitEvent:false` hydrate patch + `revalidateTree` recompute validators —
    // firing the ROOT's status but neither the control's `valueChanges` nor its
    // own `statusChanges`. A value-only trigger would keep the stale
    // `{ required }`; tracking the root status clears it (debounced).
    scheduler.run(({ expectObservable, cold }) => {
      const control = new FormControl('', {
        nonNullable: true,
        validators: [Validators.required],
      });
      const group = new FormGroup({ field: control });
      cold('--a|').subscribe(() => {
        control.patchValue('populated', { emitEvent: false });
        revalidateTree(group);
      });

      expectObservable(liveFieldErrors$(control, { debounceMs: 200 })).toBe('a 201ms b', {
        a: { required: true },
        b: null,
      });
    });
  });
});
