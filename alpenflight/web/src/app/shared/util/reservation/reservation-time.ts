import type { AircraftReservationListItem } from '@api/generated/model';

const ALL_DAY_LABEL = '00:00–24:00';

export function isoTime(iso: string): string {
  const m = /T(\d{2}:\d{2})/.exec(iso);
  return m?.[1] ?? '';
}

export function reservationTimeLabel(
  r: Pick<AircraftReservationListItem, 'start' | 'end' | 'isAllDay'>,
): string {
  if (r.isAllDay) return ALL_DAY_LABEL;
  return `${isoTime(r.start)}–${isoTime(r.end)}`;
}
