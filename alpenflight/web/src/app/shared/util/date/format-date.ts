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
 * Format a full ISO datetime as `DD.MM.YYYY HH:mm` from the value's *local*
 * calendar fields — the date-only siblings render a day, but audit / event
 * timestamps carry a wall-clock time the operator needs. Accepts a `Date`, an
 * epoch ms number, or an ISO string; returns `''` for an unparseable / empty
 * input.
 */
export function formatIsoDateTime(value: Date | number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '';
  const d = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(d.getTime())) return '';
  const hh = String(d.getHours()).padStart(2, '0');
  const min = String(d.getMinutes()).padStart(2, '0');
  return `${formatDdMmYyyy(d)} ${hh}:${min}`;
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

/**
 * Parse a `YYYY-MM-DD` (date-only) ISO string into a *local-midnight* `Date`.
 *
 * The `new Date('YYYY-MM-DD')` built-in parses date-only strings as **UTC**
 * midnight; a control that renders via local calendar fields (e.g. ng-zorro's
 * `nz-range-picker`) then shows the *previous* day west of UTC (J-6b T-13: the
 * flights-list range picker displayed the picked range a day early in any
 * negative-offset zone, and the picker's model drifted out of sync with the
 * store's ISO `from`/`to`). Constructing from the `(y, m-1, d)` components binds
 * the date to local midnight, so it round-trips symmetrically with
 * `isoDateFromLocal` regardless of timezone. Returns `null` for an
 * unparseable / non-10-char input.
 */
export function localDateFromIso(iso: string | null | undefined): Date | null {
  if (!iso || iso.length !== 10) return null;
  const [yyyy, mm, dd] = iso.split('-').map(Number);
  if (!yyyy || !mm || !dd) return null;
  const d = new Date(yyyy, mm - 1, dd);
  return Number.isNaN(d.getTime()) ? null : d;
}

/**
 * Format a `Date` as a `YYYY-MM-DD` (date-only) ISO string from its *local*
 * calendar fields — the inverse of `localDateFromIso`. Using local fields (not
 * `toISOString`, which is UTC) keeps the round-trip timezone-symmetric: the day
 * the user sees in a local-rendered picker is the day sent to the server.
 */
export function isoDateFromLocal(d: Date): string {
  const yyyy = d.getFullYear();
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${yyyy}-${mm}-${dd}`;
}
