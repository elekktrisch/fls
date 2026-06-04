/**
 * Transport-agnostic core for the per-principal Server-Sent-Events client
 * (S-176 / J-3). Extracted from {@link MeEventsService} so unit tests can
 * exercise the open/close/reconnect decisions and per-kind dispatch without
 * bootstrapping `@microsoft/fetch-event-source`, the OIDC library, or a real
 * server — the same split as `core/auth/oidc-session-bridge.ts`
 * (`applyClaimsToSession`).
 *
 * The backend channel is `GET /api/v1/me/events` (T-04): an authenticated
 * `text/event-stream` that emits `event: <kind>` lines. The first kind shipped
 * is `flight.created` (T-05). SSE is the *change overlay* only — every tile
 * still loads its state via a normal GET (journey note), so this client just
 * has to surface events to whoever subscribes per kind.
 */

/** The SSE endpoint, same-origin (CLAUDE.md cross-cutting: no hardcoded host). */
export const ME_EVENTS_URL = '/api/v1/me/events';

/** Server `event: <kind>` values this client knows about. Open for extension. */
export type MeEventKind = 'flight.created';

/** A decoded SSE frame handed to per-kind subscribers. */
export interface MeEvent {
  /** The `event:` field (the kind). */
  readonly kind: string;
  /** The `data:` field, verbatim. Empty string when the frame carried none. */
  readonly data: string;
  /** The `id:` field if present (last-event-id), else null. */
  readonly id: string | null;
}

/**
 * What a transport frame looks like once normalised from the library's
 * `EventSourceMessage` (or a test fake). `fetch-event-source` reports
 * heartbeat / comment lines with an empty `event`, which we drop.
 */
export interface RawFrame {
  readonly event?: string;
  readonly data?: string;
  readonly id?: string;
}

/**
 * Bounded exponential backoff for transparent reconnect (AC7 resilience).
 * Returns the delay in ms before the {@code attempt}-th retry (1-based), or
 * the cap once exhausted. Deterministic (no jitter) so the spec can assert it.
 */
export function reconnectDelayMs(attempt: number, baseMs = 1_000, capMs = 30_000): number {
  if (attempt <= 0) {
    return baseMs;
  }
  const delay = baseMs * 2 ** (attempt - 1);
  return Math.min(delay, capMs);
}

/**
 * Classifies an HTTP response on stream open. The library calls our `onopen`
 * once the response headers land; we translate that into one of three
 * outcomes:
 *  - `ok`        → headers good + `text/event-stream`; keep the stream.
 *  - `fatal`     → 401/403 (or any 4xx that isn't 429); do NOT retry — an
 *                  expired/absent token won't fix itself by reconnecting, and
 *                  a logout must not loop. The session effect re-opens with a
 *                  fresh token when auth returns.
 *  - `retriable` → 5xx / 429 / transient; allow the library to back off+retry.
 */
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

/**
 * Normalises a raw transport frame into a {@link MeEvent}, or `null` for a
 * frame that carries no kind (heartbeat / comment line — the 25s keep-alive
 * the server sends, T-04). Callers drop nulls.
 */
export function toMeEvent(frame: RawFrame): MeEvent | null {
  const kind = frame.event?.trim();
  if (!kind) {
    return null;
  }
  return { kind, data: frame.data ?? '', id: frame.id ?? null };
}
