import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';

import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import type { ReservationItem, SchedulerLane } from '../reservations.store';
import { ReservationsStore } from '../reservations.store';
import {
  dayWindow,
  placeBlock,
  type BlockPlacement,
  type SchedulerWindow,
} from './reservation-scheduler.placement';

/** A reservation row pre-decorated with its horizontal placement for the lane. */
interface PlacedBlock {
  reservation: ReservationItem;
  placement: BlockPlacement;
}

const HOUR_TICKS = [0, 3, 6, 9, 12, 15, 18, 21] as const;

/**
 * `/reservation-scheduler` calendar view (J-5 T-10) — read-only aircraft×time
 * grid. Each AIRCRAFT is a horizontal LANE; each reservation is a BLOCK placed
 * in its aircraft's lane, offset left by its start time and sized by duration
 * (the placement math is the pure `reservation-scheduler.placement` helper).
 *
 * The carve is explicit (journey "Scheduler render"): the new view need NOT copy
 * the legacy pixel grid (`rowHeight 30`, `cellWidth 8px/hr`). The load-bearing
 * proof is lane×time PLACEMENT — a reservation lands in the right lane at a
 * horizontal offset derived from its time. Drag-to-create / drag-move is the
 * legacy "heaviest seam" and is NOT a J-5 acceptance criterion — this view is
 * read-only.
 *
 * Lanes reuse the list store's already-loaded reservations + picker (the
 * `schedulerLanes` selector) — no duplicate fetch. The displayed day is the day
 * of the earliest loaded reservation (or today when empty); reservations outside
 * that day are clamped to the lane edges by the placement helper. The per-user
 * legacy `AircraftIdsToDisplayInScheduler` setting is NOT carried into J-5
 * (deferred): every aircraft with a reservation gets a lane.
 */
@Component({
  selector: 'af-reservation-scheduler',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    AfPageComponent,
    AfPageErrorComponent,
    AfPageHeaderComponent,
    DatePipe,
    TranslocoDirective,
  ],
  template: `
    <af-page>
      <ng-container *transloco="let t; read: 'reservations.scheduler'">
        <af-page-header
          [title]="t('title')"
          [description]="windowDate() | date: 'EEEE, dd.MM.yyyy'"
        />

        <af-page-error
          [message]="store.loadError()"
          (retry)="store.refresh()"
          data-testid="reservation-scheduler-error"
        />

        <div class="border border-slate-200 overflow-x-auto" data-testid="reservation-scheduler">
          <!-- Hour ruler — visual time axis; not load-bearing for the assertion. -->
          <div class="flex border-b border-slate-200 bg-slate-50">
            <div class="w-28 shrink-0 border-r border-slate-200 px-2 py-1 text-xs text-slate-500">
              {{ t('aircraft') }}
            </div>
            <div class="relative flex-1 h-6">
              @for (tick of hourTicks; track tick) {
                <span
                  class="tabular absolute top-1 -translate-x-1/2 text-xs text-slate-400"
                  [style.left.%]="(tick / 24) * 100"
                >
                  {{ tick }}
                </span>
              }
            </div>
          </div>

          @if (lanes().length === 0) {
            <div class="px-3 py-4 text-sm text-slate-500" data-testid="reservation-scheduler-empty">
              {{ t('empty') }}
            </div>
          }

          @for (lane of lanes(); track lane.aircraftId) {
            <div
              class="flex border-b border-slate-200 last:border-b-0"
              [attr.data-testid]="'reservation-scheduler-lane-' + lane.aircraftId"
            >
              <div
                class="tabular w-28 shrink-0 border-r border-slate-200 px-2 py-2 text-sm text-slate-900"
              >
                {{ lane.immatriculation }}
              </div>
              <div class="relative flex-1 h-10">
                @for (block of placedBlocks(lane); track block.reservation.id) {
                  <!-- rounded-[2px]: ADR 0024 max-tolerated radius exception (sharp default is 0). -->
                  <div
                    class="absolute top-1 bottom-1 flex items-center overflow-hidden whitespace-nowrap rounded-[2px] bg-brand-100 px-1.5 text-xs text-brand-700 border border-brand-500"
                    [style.left.%]="block.placement.leftPct"
                    [style.width.%]="block.placement.widthPct"
                    [attr.title]="blockLabel(block.reservation)"
                    [attr.data-testid]="'reservation-scheduler-block-' + block.reservation.id"
                  >
                    {{ blockLabel(block.reservation) }}
                  </div>
                }
              </div>
            </div>
          }
        </div>
      </ng-container>
    </af-page>
  `,
})
export class ReservationSchedulerPage {
  protected readonly store = inject(ReservationsStore);
  protected readonly hourTicks = HOUR_TICKS;
  protected readonly lanes = this.store.schedulerLanes;

  /** Earliest reservation's day, else today — the single day the grid renders. */
  protected readonly windowDate = computed<string>(() => {
    const starts = this.store
      .entities()
      .map((r) => r.start)
      .sort();
    return starts[0] ?? new Date().toISOString();
  });

  private readonly window = computed<SchedulerWindow>(() => dayWindow(this.windowDate()));

  protected placedBlocks(lane: SchedulerLane): PlacedBlock[] {
    const win = this.window();
    return lane.reservations.map((reservation) => ({
      reservation,
      placement: placeBlock(reservation.start, reservation.end, reservation.isAllDay, win),
    }));
  }

  protected blockLabel(r: ReservationItem): string {
    return this.store.immatById()[r.aircraftId] ?? r.aircraftId;
  }
}
