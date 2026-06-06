/**
 * Pure calendar math for the `/reservations` calendar (J-5 T-39).
 *
 * The calendar consolidates the old paged table + the separate
 * `/reservation-scheduler` into ONE screen with a day view (reuses the T-10
 * lane×time placement) and a week view (aircraft×day matrix). All the date
 * arithmetic + per-cell aggregation lives here — no DOM, no signals — so it
 * stays cheap to unit-test (low-CRAP, consistent with T-09/T-10 discipline).
 * The component binds the returned values straight to the template.
 */

/** A single day in the week-picker / week-matrix columns. */
export interface CalendarDay {
  /** Local-midnight ISO of the day (`YYYY-MM-DDT00:00…`), the day's identity. */
  iso: string;
  /** `YYYY-MM-DD` — the stable id fragment for `data-testid`s. */
  key: string;
  /** Day-of-month number (the big tabular figure in the picker). */
  dayOfMonth: number;
  /** Short weekday name in the active locale (e.g. `Thu`). */
  weekdayShort: string;
  /** True when this day is the selected day. */
  isSelected: boolean;
}

/** Aggregate render data for one aircraft×day cell of the week matrix. */
export interface WeekCell {
  count: number;
  /** Total reserved hours that day (timed: end−start; all-day: a full window). */
  hours: number;
  /** Fill fraction 0–100 of the cell's progress bar (hours over the day window). */
  fillPct: number;
}

/** A reservation reduced to the fields the calendar math needs. */
export interface CalendarReservation {
  start: string;
  end: string;
  isAllDay: boolean;
}

/** Hours covered by the day grid (reference default 08–19 inclusive → 12 cols). */
export const DAY_HOURS_START = 8;
export const DAY_HOURS_END = 19;
const DAY_WINDOW_HOURS = DAY_HOURS_END - DAY_HOURS_START + 1;
const MS_PER_DAY = 24 * 60 * 60 * 1000;
const MS_PER_HOUR = 60 * 60 * 1000;

/** Local midnight of the day containing `instant`. */
export function startOfDay(instant: string | number | Date): Date {
  const d = new Date(instant);
  return new Date(d.getFullYear(), d.getMonth(), d.getDate());
}

/** Monday-based start of the ISO week containing `instant` (local midnight). */
export function startOfWeek(instant: string | number | Date): Date {
  const d = startOfDay(instant);
  const dow = (d.getDay() + 6) % 7; // 0 = Monday … 6 = Sunday
  d.setDate(d.getDate() - dow);
  return d;
}

/** Add `days` calendar days to `instant`, returning local midnight. */
export function addDays(instant: string | number | Date, days: number): Date {
  const d = startOfDay(instant);
  d.setDate(d.getDate() + days);
  return d;
}

/**
 * The seven `CalendarDay`s of the week containing `selected`, Monday→Sunday,
 * each flagged for whether it is the selected day. `weekdayShort` is rendered
 * in `locale` (defaults to the runtime locale) — sentence/Title-case per the
 * platform, lowercased nowhere (the template applies `.caps` for display).
 */
export function weekDays(selected: string | number | Date, locale?: string): CalendarDay[] {
  const monday = startOfWeek(selected);
  const selectedKey = isoDate(startOfDay(selected));
  const fmt = new Intl.DateTimeFormat(locale, { weekday: 'short' });
  return Array.from({ length: 7 }, (_, i) => {
    const day = addDays(monday, i);
    const key = isoDate(day);
    return {
      iso: day.toISOString(),
      key,
      dayOfMonth: day.getDate(),
      weekdayShort: fmt.format(day),
      isSelected: key === selectedKey,
    };
  });
}

/** `YYYY-MM-DD` for a local date (not UTC — the picker keys are local days). */
export function isoDate(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

/** True when `reservation` starts on the local day `dayKey` (`YYYY-MM-DD`). */
export function startsOnDay(reservation: CalendarReservation, dayKey: string): boolean {
  return isoDate(startOfDay(reservation.start)) === dayKey;
}

/**
 * Reserved hours a single reservation contributes to its day. All-day spans
 * count as the full day-grid window (reference shows the band, not 24h); a
 * timed span counts its `end − start` in hours (never negative).
 */
export function reservationHours(reservation: CalendarReservation): number {
  if (reservation.isAllDay) return DAY_WINDOW_HOURS;
  const ms = new Date(reservation.end).getTime() - new Date(reservation.start).getTime();
  return Math.max(0, ms / MS_PER_HOUR);
}

/**
 * Aggregate the reservations that start on `dayKey` into one `WeekCell`:
 * a count, the summed reserved hours, and a fill fraction of the day window
 * (clamped 0–100) for the thin progress bar.
 */
export function weekCell(reservations: readonly CalendarReservation[], dayKey: string): WeekCell {
  const onDay = reservations.filter((r) => startsOnDay(r, dayKey));
  const hours = onDay.reduce((acc, r) => acc + reservationHours(r), 0);
  const fillPct = Math.min(100, (hours / DAY_WINDOW_HOURS) * 100);
  return { count: onDay.length, hours, fillPct };
}

/** Whether `instant` is within `[weekStart, weekStart + 7 days)`. */
export function isInWeek(instant: string | number | Date, weekStart: Date): boolean {
  const t = new Date(instant).getTime();
  return t >= weekStart.getTime() && t < weekStart.getTime() + 7 * MS_PER_DAY;
}
