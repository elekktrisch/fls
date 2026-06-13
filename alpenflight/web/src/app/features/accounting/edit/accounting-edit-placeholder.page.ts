import { ChangeDetectionStrategy, Component } from '@angular/core';

import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';

/**
 * Placeholder for the `/accountingrules/new` + `/accountingrules/:id/edit`
 * routes. T-11 lands only the list + nav; the real edit form (core fields,
 * filter-type-driven conditional sections, the J-6b `liveFieldErrors` bar) is
 * T-12, and the match-list sub-component is T-13. This stub lets the routes
 * resolve so the list page's "new" button + row navigation have somewhere to
 * land before the form exists. Replaced wholesale by `AccountingEditPage` in
 * T-12.
 */
@Component({
  selector: 'af-accounting-edit-placeholder',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [AfPageComponent, AfPageHeaderComponent],
  template: `
    <af-page>
      <af-page-header title="Accounting rule" description="Coming soon." />
    </af-page>
  `,
})
export class AccountingEditPlaceholderPage {}
