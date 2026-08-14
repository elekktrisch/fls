export interface BlockPlacement {
  leftPct: number;
  widthPct: number;
}

export interface SchedulerWindow {
  startMs: number;
  endMs: number;
}

export function dayWindow(instant: string | number | Date): SchedulerWindow {
  const d = new Date(instant);
  const startMs = Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
  const endMs = startMs + 24 * 60 * 60 * 1000;
  return { startMs, endMs };
}

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
  const minStillVisibleWidthPct = 0.5;
  const widthPct = clamp(
    Math.max(rawWidth, minStillVisibleWidthPct),
    minStillVisibleWidthPct,
    100 - leftPct,
  );
  return { leftPct, widthPct };
}
