import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';

import type { CannedType, LocationCannedType, PersonCannedType } from '../canned-report';
import { LOCATION_CANNED_TYPES, PERSON_CANNED_TYPES } from '../canned-report';

interface Tile {
  readonly type: CannedType;
  readonly label: string;
}

// Tile labels — sentence case (ADR 0024). Mirrors legacy
// `flsweb/src/reporting/flightreports.html` (REPORT_MY_FLIGHTS_* /
// REPORT_LOCATION_FLIGHTS_* keys), ordered as the picker grid renders them.
const PERSON_LABELS: Readonly<Record<PersonCannedType, string>> = {
  'my-flights-today': 'Today',
  'my-flights-yesterday': 'Yesterday',
  'my-flights-last-7-days': 'Last 7 days',
  'my-flights-last-30-days': 'Last 30 days',
  'my-flights-last-12-months': 'Last 12 months',
  'my-flights-last-24-months': 'Last 24 months',
  'my-flights-this-year': 'This year',
  'my-flights-previous-year': 'Previous year',
};

const LOCATION_LABELS: Readonly<Record<LocationCannedType, string>> = {
  'location-flights-today': 'Today',
  'location-flights-yesterday': 'Yesterday',
  'location-flights-this-year': 'This year',
  'location-flights-previous-year': 'Previous year',
};

const PERSON_TILES: readonly Tile[] = PERSON_CANNED_TYPES.map((type) => ({
  type,
  label: PERSON_LABELS[type],
}));

const LOCATION_TILES: readonly Tile[] = LOCATION_CANNED_TYPES.map((type) => ({
  type,
  label: LOCATION_LABELS[type],
}));

/**
 * Flight-reports picker. Two categories — person ("my flights") + location
 * ("home location") — each a tile grid linking to `/flightreports/:category/:type`.
 * Legacy parity: `flsweb/src/reporting/flightreports.html`. Visual idiom follows
 * the logbook page header (no actions in the header here — the picker has none;
 * the Export button lives on the results screen, T-10).
 *
 * Tiles are plain `routerLink` anchors so navigation is native (and the e2e spec
 * can assert the href contract). The canned `:type` → derived date-range happens
 * downstream in the results page (T-10) via `cannedReportSpec`.
 */
@Component({
  selector: 'af-flight-reports-picker',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [RouterLink, AfPageComponent, AfPageHeaderComponent],
  template: `
    <af-page>
      <af-page-header
        title="Flight reports"
        description="Pick a canned report or build a custom one."
      />

      <section class="mb-10" data-testid="flightreports-category-person">
        <h2 class="text-sm font-medium uppercase tracking-wider text-slate-500 mt-0 mb-3">
          My flights
        </h2>
        <div class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-3">
          @for (tile of personTiles; track tile.type) {
            <a
              class="flex items-center justify-center px-4 py-6 text-center text-sm font-medium text-slate-900 no-underline border border-slate-200 bg-white hover:border-brand-500 hover:text-brand-700 transition-colors"
              [routerLink]="['/flightreports', 'person', tile.type]"
              [attr.data-testid]="'flightreports-tile-person-' + tile.type"
            >
              {{ tile.label }}
            </a>
          }
        </div>
      </section>

      <section data-testid="flightreports-category-location">
        <h2 class="text-sm font-medium uppercase tracking-wider text-slate-500 mt-0 mb-3">
          Home location
        </h2>
        <div class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-3">
          @for (tile of locationTiles; track tile.type) {
            <a
              class="flex items-center justify-center px-4 py-6 text-center text-sm font-medium text-slate-900 no-underline border border-slate-200 bg-white hover:border-brand-500 hover:text-brand-700 transition-colors"
              [routerLink]="['/flightreports', 'location', tile.type]"
              [attr.data-testid]="'flightreports-tile-location-' + tile.type"
            >
              {{ tile.label }}
            </a>
          }
        </div>
      </section>
    </af-page>
  `,
})
export class FlightReportsPickerPage {
  protected readonly personTiles = PERSON_TILES;
  protected readonly locationTiles = LOCATION_TILES;
}
