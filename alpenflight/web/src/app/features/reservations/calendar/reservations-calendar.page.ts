import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';

import { AfButtonComponent } from '@ui/atoms/af-button';
import { AfIconComponent } from '@ui/atoms/af-icon';
import { AfPageComponent } from '@ui/molecules/af-page';
import { AfPageHeaderComponent } from '@ui/molecules/af-page-header';
import { AfPageErrorComponent } from '@ui/organisms/af-page-error';

import { SessionStore } from '../../../core/session/session.store';
import type { ReservationItem, SchedulerLane } from '../reservations.store';
import { ReservationsStore } from '../reservations.store';
import {
  hourWindow,
  placeBlock,
  type BlockPlacement,
} from '../scheduler/reservation-scheduler.placement';
import {
  DAY_HOURS_END,
  DAY_HOURS_START,
  addDays,
  isoDate,
  periodLabel,
  startOfDay,
  startsOnDay,
  stepDaysForView,
  weekCell,
  weekDays,
  type CalendarDay,
  type WeekCell,
} from './reservations-calendar.model';

interface PlacedBlock {
  reservation: ReservationItem;
  placement: BlockPlacement;
  label: string;
  isMaintenance: boolean;
}

const DAY_HOURS = Array.from(
  { length: DAY_HOURS_END - DAY_HOURS_START + 1 },
  (_, i) => DAY_HOURS_START + i,
);
const MAINTENANCE_TYPES = new Set(['Maintenance', 'Wartung', 'Unterhalt']);

@Component({
  selector: 'af-reservations-calendar',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  imports: [
    AfButtonComponent,
    AfIconComponent,
    AfPageComponent,
    AfPageErrorComponent,
    AfPageHeaderComponent,
    DatePipe,
    TranslocoDirective,
  ],
  template: `
    <af-page>
      <ng-container *transloco="let t; read: 'reservations'">
        <af-page-header [title]="t('title')" [description]="periodLabel()">
          <af-button
            type="default"
            htmlType="button"
            (clicked)="goToday()"
            data-testid="reservations-today-button"
          >
            <af-icon name="calendar" [size]="16" class="mr-1.5 inline-flex align-middle" />
            {{ t('calendar.today') }}
          </af-button>
          @if (canMutate()) {
            <af-button
              type="primary"
              htmlType="button"
              (clicked)="newReservation()"
              data-testid="reservations-new-button"
            >
              <af-icon name="plus" [size]="16" class="mr-1.5 inline-flex align-middle" />
              {{ t('new') }}
            </af-button>
          }
        </af-page-header>

        <!-- Week navigation + day-picker + day/week toggle -->
        <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
          <div class="flex items-center gap-1">
            <!-- Pager step follows the active view (T-08 #3): day view steps ±1
                 day (prev/next-day testids), week view steps ±7 (prev/next-week). -->
            <button
              type="button"
              class="inline-flex h-9 w-9 items-center justify-center border border-slate-300 bg-white text-slate-600 cursor-pointer hover:bg-slate-50"
              [attr.aria-label]="view() === 'week' ? t('calendar.prevWeek') : t('calendar.prevDay')"
              (click)="shiftPeriod(-1)"
              [attr.data-testid]="
                view() === 'week' ? 'reservations-prev-week' : 'reservations-prev-day'
              "
            >
              <af-icon name="chevron-left" [size]="16" />
            </button>
            <div
              class="ml-2 flex border border-slate-300"
              role="tablist"
              [attr.aria-label]="t('calendar.dayPicker')"
            >
              @for (d of weekDayCells(); track d.key) {
                <button
                  type="button"
                  role="tab"
                  [attr.aria-selected]="d.isSelected"
                  class="flex min-w-14 flex-col items-center gap-0.5 border-0 px-3 py-1.5 cursor-pointer not-first:border-l not-first:border-slate-300"
                  [class.bg-slate-900]="d.isSelected"
                  [class.!text-white]="d.isSelected"
                  [class.bg-white]="!d.isSelected"
                  [class.!text-slate-500]="!d.isSelected"
                  (click)="selectDay(d)"
                  [attr.data-testid]="'reservations-daypicker-' + d.key"
                >
                  <span class="caps text-[10px] opacity-70">{{ d.weekdayShort }}</span>
                  <span class="tabular text-sm">{{ d.dayOfMonth }}</span>
                </button>
              }
            </div>
            <button
              type="button"
              class="ml-2 inline-flex h-9 w-9 items-center justify-center border border-slate-300 bg-white text-slate-600 cursor-pointer hover:bg-slate-50"
              [attr.aria-label]="view() === 'week' ? t('calendar.nextWeek') : t('calendar.nextDay')"
              (click)="shiftPeriod(1)"
              [attr.data-testid]="
                view() === 'week' ? 'reservations-next-week' : 'reservations-next-day'
              "
            >
              <af-icon name="chevron-right" [size]="16" />
            </button>
            <!-- View-aware period label (T-08 #3): DD.MM.YYYY day / start–end week. -->
            <span
              class="tabular ml-3 text-sm font-medium text-slate-700"
              data-testid="reservations-period-label"
              >{{ periodLabel() }}</span
            >
          </div>

          <div class="flex border border-slate-300" data-testid="reservations-view-toggle">
            @for (v of views; track v) {
              <!-- Selected = design fg-bg + bg-text (screens-reservations.jsx:106-110):
                   surface-fg (slate-900) ground + white text, a legible active
                   state, not an inverted "blacked-out" block (J-6b T-08 #2).
                   The text color utility is IMPORTANT deliberately (J-6b T-17):
                   ng-zorro's stylesheet is imported UNLAYERED (styles.css:2), so
                   its global button color reset beats Tailwind's LAYERED text
                   utilities and left the selected button dark-on-dark (the
                   operator's #2 bug T-08 missed). The important utility wins the
                   cascade over the unlayered antd reset, restoring legible text. -->
              <button
                type="button"
                class="border-0 px-3.5 py-1.5 text-xs font-medium capitalize cursor-pointer not-first:border-l not-first:border-slate-300"
                [class.bg-slate-900]="view() === v"
                [class.!text-white]="view() === v"
                [class.bg-white]="view() !== v"
                [class.!text-slate-500]="view() !== v"
                [class.hover:bg-slate-50]="view() !== v"
                (click)="view.set(v)"
                [attr.aria-pressed]="view() === v"
                [attr.data-selected]="view() === v"
                [attr.data-testid]="'reservations-view-' + v"
              >
                @if (v === 'day') {
                  {{ t('calendar.view.day') }}
                } @else {
                  {{ t('calendar.view.week') }}
                }
              </button>
            }
          </div>
        </div>

        <af-page-error
          [message]="store.loadError()"
          (retry)="store.refresh()"
          data-testid="reservations-error"
        />

        @if (view() === 'day') {
          <!-- ─── Day view: aircraft × hour grid ─── -->
          <div class="overflow-x-auto border border-slate-200" data-testid="reservations-day-grid">
            <div class="min-w-[720px]">
              <div class="flex items-center justify-between border-b border-slate-200 px-3 py-2">
                <span class="font-medium">{{ selectedDayIso() | date: 'EEEE, dd.MM.yyyy' }}</span>
                <span class="muted tabular text-xs">
                  {{ t('calendar.reservationsCount', { count: dayReservationCount() }) }} ·
                  {{ hoursStart }}:00–{{ hoursEnd }}:00
                </span>
              </div>

              <!-- hour header -->
              <div
                class="grid border-b border-slate-200 bg-slate-50"
                [style.grid-template-columns]="'140px repeat(' + dayHours.length + ', 1fr)'"
              >
                <div></div>
                @for (h of dayHours; track h) {
                  <div
                    class="tabular border-l border-slate-200 py-1.5 pl-1.5 text-left text-[11px] text-slate-500"
                  >
                    {{ pad(h) }}
                  </div>
                }
              </div>

              @if (lanes().length === 0) {
                <div class="px-3 py-4 text-sm text-slate-500" data-testid="reservations-day-empty">
                  {{ t('scheduler.empty') }}
                </div>
              }

              @for (lane of lanes(); track lane.aircraftId) {
                <div
                  class="grid border-b border-slate-200 last:border-b-0"
                  style="grid-template-columns: 140px 1fr"
                  [style.min-height.px]="56"
                  [attr.data-testid]="'reservation-scheduler-lane-' + lane.aircraftId"
                >
                  <div class="flex flex-col justify-center border-r border-slate-200 px-3 py-2">
                    <span class="tabular text-[13px] font-medium text-slate-900">
                      {{ lane.immatriculation }}
                    </span>
                  </div>
                  <div class="relative">
                    <!-- hour grid lines -->
                    <div
                      class="pointer-events-none absolute inset-0 grid"
                      [style.grid-template-columns]="'repeat(' + dayHours.length + ', 1fr)'"
                    >
                      @for (h of dayHours; track h) {
                        <div class="border-l border-slate-200"></div>
                      }
                    </div>
                    @for (block of placedBlocks(lane); track block.reservation.id) {
                      <!-- rounded-[2px]: ADR 0024 max-tolerated radius exception. -->
                      <button
                        type="button"
                        class="absolute top-1 bottom-1 flex flex-col justify-center gap-px overflow-hidden rounded-[2px] border-0 border-l-[3px] px-2 text-left text-xs cursor-pointer"
                        [class.bg-brand-100]="!block.isMaintenance"
                        [class.border-brand-500]="!block.isMaintenance"
                        [class.text-brand-700]="!block.isMaintenance"
                        [class.af-maintenance-band]="block.isMaintenance"
                        [class.border-slate-400]="block.isMaintenance"
                        [class.text-slate-700]="block.isMaintenance"
                        [style.left]="'calc(' + block.placement.leftPct + '% + 2px)'"
                        [style.width]="'calc(' + block.placement.widthPct + '% - 4px)'"
                        [attr.title]="block.label"
                        (click)="editReservation(block.reservation)"
                        [attr.data-testid]="'reservation-scheduler-block-' + block.reservation.id"
                      >
                        <span class="truncate font-medium">{{ pilot(block.reservation) }}</span>
                        <span class="tabular text-[11px] opacity-80">
                          {{ block.reservation.start | date: 'HH:mm' }}–{{
                            block.reservation.end | date: 'HH:mm'
                          }}
                          @if (block.reservation.reservationTypeName) {
                            <span class="opacity-70">
                              · {{ block.reservation.reservationTypeName }}</span
                            >
                          }
                        </span>
                      </button>
                    }
                  </div>
                </div>
              }
            </div>
          </div>
        } @else {
          <!-- ─── Week view: aircraft × day matrix ─── -->
          <div class="overflow-x-auto border border-slate-200" data-testid="reservations-week-grid">
            <div class="min-w-[720px]">
              <div
                class="grid border-b border-slate-200 bg-slate-50"
                [style.grid-template-columns]="'140px repeat(' + weekDayCells().length + ', 1fr)'"
              >
                <div class="caps muted px-3 py-2.5">{{ t('scheduler.aircraft') }}</div>
                @for (d of weekDayCells(); track d.key) {
                  <div class="flex items-baseline gap-1.5 border-l border-slate-200 px-2 py-2.5">
                    <span class="caps muted">{{ d.weekdayShort }}</span>
                    <span class="tabular text-xs font-medium">{{ d.dayOfMonth }}</span>
                  </div>
                }
              </div>

              @if (lanes().length === 0) {
                <div class="px-3 py-4 text-sm text-slate-500" data-testid="reservations-week-empty">
                  {{ t('scheduler.empty') }}
                </div>
              }

              @for (lane of lanes(); track lane.aircraftId) {
                <div
                  class="grid border-b border-slate-200 last:border-b-0"
                  [style.grid-template-columns]="'140px repeat(' + weekDayCells().length + ', 1fr)'"
                  [style.min-height.px]="48"
                  [attr.data-testid]="'reservations-week-lane-' + lane.aircraftId"
                >
                  <div class="border-r border-slate-200 px-3 py-2">
                    <span class="tabular block text-[13px] font-medium text-slate-900">
                      {{ lane.immatriculation }}
                    </span>
                  </div>
                  @for (d of weekDayCells(); track d.key) {
                    <button
                      type="button"
                      class="flex flex-col gap-0.5 border-0 border-l border-slate-200 px-2 py-2 text-left cursor-pointer hover:bg-slate-50"
                      [class.bg-slate-50]="d.isSelected"
                      (click)="openDayCell(lane, d)"
                      [attr.data-testid]="'reservations-week-cell-' + lane.aircraftId + '-' + d.key"
                    >
                      @let cell = cellFor(lane, d);
                      @if (cell.count > 0) {
                        <span class="tabular text-xs font-medium">
                          {{ cell.count }} · {{ cell.hours.toFixed(1) }}h
                        </span>
                        <span class="block h-[3px] w-full bg-slate-100">
                          <span
                            class="block h-full bg-brand-500"
                            [style.width.%]="cell.fillPct"
                          ></span>
                        </span>
                        <span class="muted truncate text-[11px]">{{ firstPilot(lane, d) }}</span>
                      } @else {
                        <span class="text-[11px] text-slate-400">—</span>
                      }
                    </button>
                  }
                </div>
              }
            </div>
          </div>
        }
      </ng-container>
    </af-page>
  `,
  styles: [
    `
      /* Maintenance hatch — reference repeating-linear-gradient; not expressible
         as a Tailwind utility (ADR 0024 §11 documented exception). */
      .af-maintenance-band {
        background: repeating-linear-gradient(
          135deg,
          var(--color-slate-200) 0 6px,
          var(--color-slate-100) 6px 12px
        );
      }
    `,
  ],
})
export class ReservationsCalendarPage {
  protected readonly store = inject(ReservationsStore);
  protected readonly session = inject(SessionStore);
  protected readonly router = inject(Router);

  protected readonly views = ['day', 'week'] as const;
  protected readonly dayHours = DAY_HOURS;
  protected readonly hoursStart = DAY_HOURS_START;
  protected readonly hoursEnd = DAY_HOURS_END + 1;

  protected readonly view = signal<'day' | 'week'>('day');
  protected readonly selectedDayIso = signal<string>(startOfDay(new Date()).toISOString());

  protected readonly lanes = this.store.schedulerLanes;
  protected readonly canMutate = computed(
    () => this.session.isClubAdmin() || this.session.isSystemAdmin(),
  );

  protected readonly weekDayCells = computed<CalendarDay[]>(() => weekDays(this.selectedDayIso()));

  private readonly dayHourWindow = computed(() =>
    hourWindow(this.selectedDayIso(), DAY_HOURS_START, DAY_HOURS_END + 1),
  );

  protected readonly periodLabel = computed<string>(() =>
    periodLabel(this.view(), this.selectedDayIso()),
  );

  protected readonly dayReservationCount = computed<number>(() => {
    const key = isoDate(startOfDay(this.selectedDayIso()));
    return this.store.entities().filter((r) => startsOnDay(r, key)).length;
  });

  protected placedBlocks(lane: SchedulerLane): PlacedBlock[] {
    const win = this.dayHourWindow();
    const key = isoDate(startOfDay(this.selectedDayIso()));
    return lane.reservations
      .filter((r) => startsOnDay(r, key))
      .map((reservation) => ({
        reservation,
        placement: placeBlock(reservation.start, reservation.end, reservation.isAllDay, win),
        label: this.pilot(reservation),
        isMaintenance: MAINTENANCE_TYPES.has(reservation.reservationTypeName ?? ''),
      }));
  }

  protected cellFor(lane: SchedulerLane, day: CalendarDay): WeekCell {
    return weekCell(lane.reservations, day.key);
  }

  protected firstPilot(lane: SchedulerLane, day: CalendarDay): string {
    const onDay = lane.reservations.filter((r) => startsOnDay(r, day.key));
    const head = onDay.at(0);
    if (!head) return '';
    const first = this.pilot(head);
    return onDay.length > 1 ? `${first} +${onDay.length - 1}` : first;
  }

  protected pilot(r: ReservationItem): string {
    return this.store.pilotNameById()[r.pilotPersonId] ?? r.pilotPersonId;
  }

  protected pad(h: number): string {
    return String(h).padStart(2, '0');
  }

  protected selectDay(d: CalendarDay): void {
    this.selectedDayIso.set(d.iso);
  }

  protected shiftPeriod(delta: number): void {
    const step = stepDaysForView(this.view());
    this.selectedDayIso.set(addDays(this.selectedDayIso(), delta * step).toISOString());
  }

  protected goToday(): void {
    this.selectedDayIso.set(startOfDay(new Date()).toISOString());
    this.view.set('day');
  }

  protected openDayCell(_lane: SchedulerLane, d: CalendarDay): void {
    this.selectedDayIso.set(d.iso);
    this.view.set('day');
  }

  protected newReservation(): void {
    void this.router.navigateByUrl('/reservations/new');
  }

  protected editReservation(r: ReservationItem): void {
    void this.router.navigate(['/reservations', r.id, 'edit']);
  }
}
