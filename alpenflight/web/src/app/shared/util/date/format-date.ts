/**
 * Project-wide date display format helpers (J-6b T-12). Legacy hardcodes
 * `DD.MM.YYYY` everywhere and the rewrite matches — this is the canonical,
 * locale-independent date-only renderer for user-facing labels/columns. The
 * `af-date-picker` shares the same `dd.MM.yyyy` shape via `DEFAULT_DATE_FORMAT`.
 *
 * Pure + framework-free so it's Vitest-testable without a TestBed (web CLAUDE.md
 * §8). Feature-local copies (`flights/list` `formatLegacyDate`,
 * `reservations/calendar` `formatDdMmYyyy`) predate this and already emit the
 * same shape; new display sites should centralise here.
 */

/**
 * Format a date as `DD.MM.YYYY` using the value's *local* calendar fields, so a
 * CH date never drifts a day across the UTC boundary. Accepts a `Date`, an epoch
 * ms number, or an ISO string; returns `''` for an unparseable / empty input.
 */
export function formatDdMmYyyy(value: Date | number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '';
  const d = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(d.getTime())) return '';
  const dd = String(d.getDate()).padStart(2, '0');
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  return `${dd}.${mm}.${d.getFullYear()}`;
}

/**
 * Format a `YYYY-MM-DD` (date-only) ISO string as `DD.MM.YYYY` *without*
 * constructing a `Date` — avoids the `new Date('YYYY-MM-DD')` UTC-midnight
 * gotcha entirely (a date-only string is parsed as UTC, then rendered in local
 * time can roll back a day west of UTC). Returns `''` for a non-10-char input.
 */
export function formatIsoDateDdMmYyyy(iso: string | null | undefined): string {
  if (!iso || iso.length !== 10) return '';
  const [yyyy, mm, dd] = iso.split('-');
  if (!yyyy || !mm || !dd) return '';
  return `${dd}.${mm}.${yyyy}`;
}
