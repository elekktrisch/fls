/**
 * Pure placement math for the `/reservation-scheduler` calendar view (J-5 T-10).
 *
 * The new view deliberately does NOT copy the legacy pixel grid (`rowHeight 30`,
 * `cellWidth 8px/hr`, `hoursPerDay 24`). The load-bearing assertion is lane×time
 * PLACEMENT: a reservation row lands in its aircraft's lane, horizontally offset
 * by its start time and sized by its duration, measured as a fraction of the
 * displayed time window. Keeping the math here (no DOM, no signals) makes it
 * cheap to unit-test (low-CRAP, consistent with T-09's discipline) — the
 * component just binds the returned percentages to CSS `left` / `width`.
 *
 * Drag-to-create / drag-move is the legacy "heaviest seam" and is explicitly NOT
 * a J-5 acceptance criterion — this view is read-only placement only.
 */

/** Horizontal placement of a reservation block within a lane, as CSS percentages. */
export interface BlockPlacement {
  /** Offset from the lane's left edge, in % of the window width (clamped 0–100). */
  leftPct: number;
  /** Block width, in % of the window width (clamped so left+width ≤ 100). */
  widthPct: number;
}

/** The visible time window a scheduler day renders (half-open `[start, end)`). */
export interface SchedulerWindow {
  /** Window start instant (ms epoch) — maps to leftPct 0. */
  startMs: number;
  /** Window end instant (ms epoch) — maps to leftPct 100. */
  endMs: number;
}

/**
 * The UTC-day window containing `instant` — `[day 00:00, next day 00:00)`.
 * The scheduler shows one day per column-set; a reservation is placed relative
 * to the day it starts in (all-day spans collapse to the full window).
 */
export function dayWindow(instant: string | number | Date): SchedulerWindow {
  const d = new Date(instant);
  const startMs = Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
  const endMs = startMs + 24 * 60 * 60 * 1000;
  return { startMs, endMs };
}

/**
 * The LOCAL hour window `[day startHour:00, day endHourExclusive:00)` for the
 * day containing `instant`. The `/reservations` calendar day view (J-5 T-39)
 * renders only business hours (reference default 08–19), so blocks are placed
 * relative to that window rather than the full UTC day — a 10:00 reservation in
 * an 08:00–20:00 window lands at (10−8)/12 of the lane, matching the reference.
 * Local time (not UTC) so the displayed hour matches the user's day.
 */
export function hourWindow(
  instant: string | number | Date,
  startHour: number,
  endHourExclusive: number,
): SchedulerWindow {
  const d = new Date(instant);
  const startMs = new Date(d.getFullYear(), d.getMonth(), d.getDate(), startHour).getTime();
  const endMs = new Date(d.getFullYear(), d.getMonth(), d.getDate(), endHourExclusive).getTime();
  return { startMs, endMs };
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

/**
 * Place a reservation `[start, end)` within `window`, as left/width percentages.
 *
 * - An all-day reservation (or any span covering the whole window) renders as a
 *   full-width band (`leftPct 0, widthPct 100`).
 * - A timed reservation is offset by `(start - window.start) / window.width` and
 *   sized by its duration over the window width; both are clamped to the window
 *   so a block that starts before / ends after the day stays inside the lane.
 * - A zero/negative span yields a tiny non-zero width so the block is still
 *   visible (degenerate, but the placement assertion only needs lane + offset).
 */
export function placeBlock(
  start: string | number | Date,
  end: string | number | Date,
  isAllDay: boolean,
  window: SchedulerWindow,
): BlockPlacement {
  const windowMs = window.endMs - window.startMs;
  if (windowMs <= 0) return { leftPct: 0, widthPct: 100 };
  if (isAllDay) return { leftPct: 0, widthPct: 100 };

  const startMs = new Date(start).getTime();
  const endMs = new Date(end).getTime();

  const rawLeft = ((startMs - window.startMs) / windowMs) * 100;
  const rawWidth = ((endMs - startMs) / windowMs) * 100;

  const leftPct = clamp(rawLeft, 0, 100);
  const minWidth = 0.5; // keep a degenerate/zero-length block visible.
  const widthPct = clamp(Math.max(rawWidth, minWidth), minWidth, 100 - leftPct);
  return { leftPct, widthPct };
}
