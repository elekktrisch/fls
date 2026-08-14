import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import type { AircraftReservationListItem } from '@api/generated/model';
import { reservationTimeLabel } from '@shared/util/reservation';

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
  readonly aircraftLabel = input.required<string>();
  readonly openLink = input.required<readonly unknown[] | string>();
  readonly openQueryParams = input<Record<string, string> | null>(null);
  readonly openLabel = input.required<string>();
  readonly testIdPrefix = input.required<string>();

  protected readonly timeLabel = computed(() => reservationTimeLabel(this.reservation()));
  protected readonly rowTestId = computed(() => `${this.testIdPrefix()}-${this.reservation().id}`);
  protected readonly openTestId = computed(
    () => `${this.testIdPrefix()}-edit-${this.reservation().id}`,
  );
}
