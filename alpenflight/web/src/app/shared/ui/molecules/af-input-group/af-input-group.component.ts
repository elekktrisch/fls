import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * Composes an input with one or more trailing buttons into a single seam-less
 * control. The input flex-fills; trailing buttons drop their left border in
 * the resting state so the input's own right border (slate-300, brand-500
 * when focused) is the visible seam — no overlap, no doubled borders. On
 * button hover/focus the left border is restored so the button's full focus
 * ring is visible.
 *
 *   <af-form-field label="SPOT tracker link" for="SpotLink">
 *     <af-input-group>
 *       <af-input inputId="SpotLink" formControlName="spotLink" />
 *       <af-button (clicked)="test()">Test link</af-button>
 *     </af-input-group>
 *   </af-form-field>
 */
@Component({
  selector: 'af-input-group',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class:
      'flex [&>af-input]:flex-1 [&>af-input]:min-w-0 [&>af-button>button]:!h-11 [&>af-button>button]:!border-l-0 [&>af-button>button:hover]:!border-l [&>af-button>button:focus]:!border-l',
  },
  template: `<ng-content />`,
})
export class AfInputGroupComponent {}
