// V4 process-state codes (display-only this iteration; transitions are J-10b).
const PREPARED = 10;
const BOOKED = 20;
const ERROR = 30;
const CANCELLED = 99;

/** The `deliveries.state.*` i18n key for a process-state code (fallback: prepared). */
export function processStateKey(processStateId: number): string {
  switch (processStateId) {
    case BOOKED:
      return 'state.booked';
    case ERROR:
      return 'state.error';
    case CANCELLED:
      return 'state.cancelled';
    case PREPARED:
    default:
      return 'state.prepared';
  }
}

/** Slate-neutral badge classes per state; only Error/Cancelled break to a tint. */
export function processStateClass(processStateId: number): string {
  switch (processStateId) {
    case BOOKED:
      return 'border-emerald-300 bg-emerald-50 text-emerald-700';
    case ERROR:
      return 'border-red-300 bg-red-50 text-red-700';
    case CANCELLED:
      return 'border-slate-300 bg-slate-100 text-slate-500';
    case PREPARED:
    default:
      return 'border-slate-200 bg-slate-50 text-slate-600';
  }
}
