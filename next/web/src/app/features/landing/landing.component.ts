import { ChangeDetectionStrategy, Component } from '@angular/core';

// TODO(S-097): scaffold placeholder. Replace with the ported landing page
// (i18n-keyed German content, brand colors from @theme tokens) when S-097 lands.
@Component({
  selector: 'af-landing',
  template: ` <h1 class="text-blue-600 text-3xl font-bold">Hello AlpenFlight</h1> `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LandingComponent {}
