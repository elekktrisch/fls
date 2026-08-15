import { ChangeDetectionStrategy, Component } from '@angular/core';

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
