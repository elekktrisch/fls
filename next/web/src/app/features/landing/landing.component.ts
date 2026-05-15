import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  selector: 'fls-landing',
  template: ` <h1 class="text-blue-600 text-3xl font-bold">Hello FLS</h1> `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LandingComponent {}
