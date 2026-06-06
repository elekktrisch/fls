import { ChangeDetectionStrategy, Component } from '@angular/core';

import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';

/**
 * Reservation create/edit form — PLACEHOLDER (T-08).
 *
 * T-08 ships the `/reservations` LIST seam only. This stub exists so the
 * `new` + `:id/edit` routes the list's "New" button and kebab-edit navigate to
 * resolve at build time. T-09 replaces it with the real form (aircraft / pilot /
 * location / start / end / type / all-day / second-crew / remarks, conflict-409
 * inline error, end>start) built via the extracted shared form↔request helper.
 */
@Component({
  selector: 'af-reservation-edit',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [AfPageComponent, AfPageHeaderComponent],
  template: `
    <af-page>
      <af-page-header title="Reservation" />
      <p class="text-sm text-slate-500" data-testid="reservation-edit-placeholder">
        The reservation form lands in T-09.
      </p>
    </af-page>
  `,
})
export class ReservationEditPage {}
