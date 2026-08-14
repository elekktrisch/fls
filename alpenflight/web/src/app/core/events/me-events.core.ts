export const ME_EVENTS_URL = '/api/v1/me/events';

// ext: server SSE `event:` names
export type MeEventKind = 'flight.created';

export interface MeEvent {
  readonly kind: string;
  readonly data: string;
  readonly id: string | null;
}

export interface RawFrame {
  readonly event?: string;
  readonly data?: string;
  readonly id?: string;
}

export function reconnectDelayMs(attempt: number, baseMs = 1_000, capMs = 30_000): number {
  if (attempt <= 0) {
    return baseMs;
  }
  const delay = baseMs * 2 ** (attempt - 1);
  return Math.min(delay, capMs);
}

export type OpenOutcome = 'ok' | 'fatal' | 'retriable';

export function classifyOpen(status: number, contentType: string | null): OpenOutcome {
  if (status >= 200 && status < 300) {
    return contentType?.startsWith('text/event-stream') ? 'ok' : 'fatal';
  }
  if (status === 429) {
    return 'retriable';
  }
  if (status >= 400 && status < 500) {
    return 'fatal';
  }
  return 'retriable';
}

export function toMeEvent(frame: RawFrame): MeEvent | null {
  const kind = frame.event?.trim();
  if (!kind) {
    return null;
  }
  return { kind, data: frame.data ?? '', id: frame.id ?? null };
}
