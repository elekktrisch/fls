export interface CalendarDay {
  // RENAME: iso -> localMidnightIso
  iso: string;
  // RENAME: key -> dayKey
  key: string;
  dayOfMonth: number;
  weekdayShort: string;
  isSelected: boolean;
}

export interface WeekCell {
  count: number;
  hours: number;
  fillPct: number;
}

export interface CalendarReservation {
  start: string;
  end: string;
  isAllDay: boolean;
}

export const DAY_HOURS_START = 8;
// RENAME: DAY_HOURS_END -> DAY_HOURS_END_INCLUSIVE
export const DAY_HOURS_END = 19;
const DAY_WINDOW_HOURS = DAY_HOURS_END - DAY_HOURS_START + 1;
const MS_PER_DAY = 24 * 60 * 60 * 1000;
const MS_PER_HOUR = 60 * 60 * 1000;

export function startOfDay(instant: string | number | Date): Date {
  const d = new Date(instant);
  return new Date(d.getFullYear(), d.getMonth(), d.getDate());
}

export function startOfWeek(instant: string | number | Date): Date {
  const d = startOfDay(instant);
  const mondayBasedDayOfWeek = (d.getDay() + 6) % 7;
  d.setDate(d.getDate() - mondayBasedDayOfWeek);
  return d;
}

export function addDays(instant: string | number | Date, days: number): Date {
  const d = startOfDay(instant);
  d.setDate(d.getDate() + days);
  return d;
}

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

export function isoDate(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

export type CalendarView = 'day' | 'week';

export function stepDaysForView(view: CalendarView): number {
  return view === 'week' ? 7 : 1;
}

export function formatDdMmYyyy(instant: string | number | Date): string {
  const d = new Date(instant);
  const day = String(d.getDate()).padStart(2, '0');
  const m = String(d.getMonth() + 1).padStart(2, '0');
  return `${day}.${m}.${d.getFullYear()}`;
}

export function periodLabel(view: CalendarView, selected: string | number | Date): string {
  if (view === 'day') return formatDdMmYyyy(startOfDay(selected));
  const monday = startOfWeek(selected);
  const sunday = addDays(monday, 6);
  return `${formatDdMmYyyy(monday)} – ${formatDdMmYyyy(sunday)}`;
}

export function startsOnDay(reservation: CalendarReservation, dayKey: string): boolean {
  return isoDate(startOfDay(reservation.start)) === dayKey;
}

export function reservationHours(reservation: CalendarReservation): number {
  if (reservation.isAllDay) return DAY_WINDOW_HOURS;
  const ms = new Date(reservation.end).getTime() - new Date(reservation.start).getTime();
  return Math.max(0, ms / MS_PER_HOUR);
}

export function weekCell(reservations: readonly CalendarReservation[], dayKey: string): WeekCell {
  const onDay = reservations.filter((r) => startsOnDay(r, dayKey));
  const hours = onDay.reduce((acc, r) => acc + reservationHours(r), 0);
  const fillPct = Math.min(100, (hours / DAY_WINDOW_HOURS) * 100);
  return { count: onDay.length, hours, fillPct };
}

export function isInWeek(instant: string | number | Date, weekStart: Date): boolean {
  const t = new Date(instant).getTime();
  return t >= weekStart.getTime() && t < weekStart.getTime() + 7 * MS_PER_DAY;
}
