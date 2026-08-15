import { describe, expect, it } from 'vitest';

import {
  addDays,
  formatDdMmYyyy,
  isInWeek,
  isoDate,
  periodLabel,
  reservationHours,
  startOfWeek,
  startsOnDay,
  stepDaysForView,
  weekCell,
  weekDays,
  type CalendarReservation,
} from './reservations-calendar.model';

describe('reservations calendar model', () => {
  it('starts the week on Monday for any day in it', () => {
    expect(isoDate(startOfWeek('2026-06-06T09:00:00'))).toBe('2026-06-01');
    expect(isoDate(startOfWeek('2026-06-01T23:00:00'))).toBe('2026-06-01');
    expect(isoDate(startOfWeek('2026-06-07T00:30:00'))).toBe('2026-06-01');
  });

  it('builds seven Monday→Sunday days with the selected one flagged', () => {
    const days = weekDays('2026-06-04', 'en-US');
    expect(days).toHaveLength(7);
    expect(days.map((d) => d.dayKey)).toEqual([
      '2026-06-01',
      '2026-06-02',
      '2026-06-03',
      '2026-06-04',
      '2026-06-05',
      '2026-06-06',
      '2026-06-07',
    ]);
    expect(days[0]?.weekdayShort).toBe('Mon');
    expect(days[0]?.dayOfMonth).toBe(1);
    expect(days.filter((d) => d.isSelected).map((d) => d.dayKey)).toEqual(['2026-06-04']);
  });

  it('addDays crosses month boundaries', () => {
    expect(isoDate(addDays('2026-06-30', 1))).toBe('2026-07-01');
    expect(isoDate(addDays('2026-06-01', -1))).toBe('2026-05-31');
  });

  it('reservationHours counts timed duration and all-day as the day window', () => {
    const timed: CalendarReservation = {
      start: '2026-06-04T10:00:00',
      end: '2026-06-04T12:30:00',
      isAllDay: false,
    };
    expect(reservationHours(timed)).toBeCloseTo(2.5, 5);

    const allDay: CalendarReservation = {
      start: '2026-06-04T00:00:00',
      end: '2026-06-04T00:00:00',
      isAllDay: true,
    };
    expect(reservationHours(allDay)).toBe(12);

    const negative: CalendarReservation = {
      start: '2026-06-04T12:00:00',
      end: '2026-06-04T10:00:00',
      isAllDay: false,
    };
    expect(reservationHours(negative)).toBe(0);
  });

  it('startsOnDay matches by the local start day', () => {
    const r: CalendarReservation = {
      start: '2026-06-04T23:30:00',
      end: '2026-06-05T01:00:00',
      isAllDay: false,
    };
    expect(startsOnDay(r, '2026-06-04')).toBe(true);
    expect(startsOnDay(r, '2026-06-05')).toBe(false);
  });

  it('weekCell aggregates count, hours and a clamped fill fraction', () => {
    const reservations: CalendarReservation[] = [
      { start: '2026-06-04T08:00:00', end: '2026-06-04T11:00:00', isAllDay: false },
      { start: '2026-06-04T13:00:00', end: '2026-06-04T16:00:00', isAllDay: false },
      { start: '2026-06-05T09:00:00', end: '2026-06-05T10:00:00', isAllDay: false },
    ];
    const cell = weekCell(reservations, '2026-06-04');
    expect(cell.count).toBe(2);
    expect(cell.hours).toBeCloseTo(6, 5);
    expect(cell.fillPct).toBeCloseTo(50, 5);
  });

  it('weekCell clamps the fill fraction at 100%', () => {
    const reservations: CalendarReservation[] = [
      { start: '2026-06-04T00:00:00', end: '2026-06-04T00:00:00', isAllDay: true },
      { start: '2026-06-04T08:00:00', end: '2026-06-04T16:00:00', isAllDay: false },
    ];
    const cell = weekCell(reservations, '2026-06-04');
    expect(cell.fillPct).toBe(100);
  });

  it('stepDaysForView steps one day in day-view, a whole week in week-view (T-08 #3)', () => {
    expect(stepDaysForView('day')).toBe(1);
    expect(stepDaysForView('week')).toBe(7);
  });

  it('formatDdMmYyyy zero-pads to DD.MM.YYYY (T-08 #3)', () => {
    expect(formatDdMmYyyy('2026-06-04T09:00:00')).toBe('04.06.2026');
    expect(formatDdMmYyyy('2026-12-31T23:30:00')).toBe('31.12.2026');
    expect(formatDdMmYyyy('2026-01-01T00:00:00')).toBe('01.01.2026');
  });

  it('periodLabel is the single day in day-view, the week start–end range in week-view', () => {
    expect(periodLabel('day', '2026-06-04T15:00:00')).toBe('04.06.2026');
    expect(periodLabel('week', '2026-06-04T15:00:00')).toBe('01.06.2026 – 07.06.2026');
    expect(periodLabel('week', '2026-06-07T23:00:00')).toBe('01.06.2026 – 07.06.2026');
  });

  it('isInWeek bounds the half-open week range', () => {
    const monday = startOfWeek('2026-06-04');
    expect(isInWeek('2026-06-01T00:00:00', monday)).toBe(true);
    expect(isInWeek('2026-06-07T23:59:00', monday)).toBe(true);
    expect(isInWeek('2026-06-08T00:00:00', monday)).toBe(false);
    expect(isInWeek('2026-05-31T23:00:00', monday)).toBe(false);
  });
});
