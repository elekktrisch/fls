import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';

/**
 * Stand-in landing for `/flights/new`, `/flights/:id/edit`, `/flights/copy/:id`.
 * Replaced by the real edit / create / copy pages when S-062c lands. Kept as a
 * thin reviewer-sanity surface so the navigation entry points wire and assert
 * end-to-end before the form mechanics ship.
 */
@Component({
  selector: 'af-flights-edit-placeholder',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, AfPageComponent, AfPageHeaderComponent],
  template: `
    <af-page>
      <af-page-header title="Flight edit" />
      <p data-testid="flights-edit-placeholder" class="text-slate-600">
        The flight edit / create / copy forms land in S-062c.
      </p>
      <p>
        <a class="text-brand-600 hover:text-brand-700" routerLink="/flights">Back to flight list</a>
      </p>
    </af-page>
  `,
})
export class FlightsEditPlaceholderPage {}
