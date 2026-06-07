import type { AircraftReservationListItem } from '@api/generated/model';

/** All-day reservations render as the full-day window rather than instants. */
const ALL_DAY_LABEL = '00:00–24:00';

/**
 * `HH:mm` from an ISO instant (`2026-07-01T10:00:00Z` → `10:00`).
 *
 * Reads the wire-clock fields directly (no timezone re-projection) so the label
 * matches the stored reservation window — the same posture the J-5 read-side
 * carries through the SPA.
 */
export function isoTime(iso: string): string {
  const m = /T(\d{2}:\d{2})/.exec(iso);
  return m?.[1] ?? '';
}

/**
 * One reservation's flat-list time label: `HH:mm–HH:mm`, or the all-day window.
 *
 * Promoted from the planning-edit page (J-6 T-08) so the planning-day panel and
 * any other flat reservation list share one presentation rule rather than each
 * re-deriving it. The calendar grid (J-5) keeps its own per-block `DatePipe`
 * rendering — positioned hour-blocks, not list rows — so it does NOT consume
 * this helper.
 */
export function reservationTimeLabel(
  r: Pick<AircraftReservationListItem, 'start' | 'end' | 'isAllDay'>,
): string {
  if (r.isAllDay) return ALL_DAY_LABEL;
  return `${isoTime(r.start)}–${isoTime(r.end)}`;
}
