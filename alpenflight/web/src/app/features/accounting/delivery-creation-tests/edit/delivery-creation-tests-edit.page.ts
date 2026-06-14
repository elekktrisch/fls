import { ChangeDetectionStrategy, Component } from '@angular/core';

import { AfPageComponent } from '@ui/molecules/af-page';

/**
 * Placeholder edit shell so `/deliverycreationtests/new` and `/:id/edit`
 * resolve. T-17 fills it: Flight picker → "Create test delivery" (dry-run) →
 * expected-item set → save/round-trip → "Run test" (Success/Failure) +
 * matched-rule links; T-18 adds the failure diff UI.
 */
@Component({
  selector: 'af-delivery-creation-tests-edit',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [AfPageComponent],
  template: `<af-page><div data-testid="dct-edit-placeholder"></div></af-page>`,
})
export class DeliveryCreationTestsEditPage {}
