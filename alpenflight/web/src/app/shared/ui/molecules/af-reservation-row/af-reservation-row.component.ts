import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import type { AircraftReservationListItem } from '@api/generated/model';
import { reservationTimeLabel } from '@shared/util/reservation';

/**
 * One reservation rendered as a flat list row: time · aircraft · type, with an
 * open-link to the reservation editor.
 *
 *   <af-reservation-row
 *     [reservation]="r"
 *     [aircraftLabel]="immat(r.aircraftId)"
 *     [openLink]="['/reservations', r.id, 'edit']"
 *     [openLabel]="t('reservations.open')"
 *     testIdPrefix="planning-reservation"
 *   />
 *
 * Feature-agnostic: it is driven by the `AircraftReservationListItem` shape but
 * knows nothing about a store. The consuming feature resolves the aircraft
 * immatriculation (it owns the label map) and passes it in; the row owns only
 * the consistent presentation (time-label rule, layout, type chip, open-link)
 * so the planning-day panel and any other flat reservation list render one UI,
 * not a hand-rolled copy each. Extracted from the planning-edit page (J-6 T-08b)
 * de-duplicating its bespoke inline markup + `timeLabel`/`isoTime` helpers.
 *
 * The J-5 reservations *calendar* renders positioned hour-blocks, not list rows,
 * so it deliberately does NOT render through this row — a list-row component is
 * the wrong shape for a time-placed grid block.
 */
@Component({
  selector: 'af-reservation-row',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [RouterLink],
  template: `
    <div
      class="flex items-center justify-between border-b border-slate-200 last:border-b-0 px-3 py-2.5 text-sm hover:bg-slate-50"
      [attr.data-testid]="rowTestId()"
    >
      <div class="flex items-center gap-3">
        <span class="tabular font-medium text-slate-900">{{ timeLabel() }}</span>
        <span class="text-slate-600">{{ aircraftLabel() }}</span>
        @if (reservation().reservationTypeName) {
          <span class="text-xs text-slate-500">{{ reservation().reservationTypeName }}</span>
        }
      </div>
      <a
        class="text-brand-600 no-underline hover:text-brand-700"
        [routerLink]="openLink()"
        [queryParams]="openQueryParams()"
        [attr.data-testid]="openTestId()"
      >
        {{ openLabel() }}
      </a>
    </div>
  `,
})
export class AfReservationRowComponent {
  readonly reservation = input.required<AircraftReservationListItem>();
  /** Resolved aircraft label (immatriculation) — the feature owns the lookup map. */
  readonly aircraftLabel = input.required<string>();
  /** Router target for the open-link (e.g. `['/reservations', id, 'edit']`). */
  readonly openLink = input.required<readonly unknown[] | string>();
  /**
   * Optional query params for the open-link (RouterLink `[queryParams]`). The
   * planning inline list passes `{ returnUrl: <current planning-day url> }` so
   * the reservation editor's Cancel returns to the planning day, not the
   * /reservations overview (J-6b T-10). Empty by default → a bare link.
   */
  readonly openQueryParams = input<Record<string, string> | null>(null);
  readonly openLabel = input.required<string>();
  /**
   * Test-id prefix; the row gets `<prefix>-<id>` and the open-link
   * `<prefix>-edit-<id>` (keeps the planning spec's existing selectors stable).
   */
  readonly testIdPrefix = input.required<string>();

  protected readonly timeLabel = computed(() => reservationTimeLabel(this.reservation()));
  protected readonly rowTestId = computed(() => `${this.testIdPrefix()}-${this.reservation().id}`);
  protected readonly openTestId = computed(
    () => `${this.testIdPrefix()}-edit-${this.reservation().id}`,
  );
}
