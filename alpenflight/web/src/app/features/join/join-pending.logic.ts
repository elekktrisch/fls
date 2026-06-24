/**
 * The SSE `join-request.status-changed` frame the backend emits on each
 * committed transition: `{ requestId, status }` (the listener's payload). Only
 * `status` drives the pending page; `requestId` is carried for future
 * disambiguation but unused here (one live request per pilot).
 */
export interface StatusChangedPayload {
  readonly status: string;
}

/**
 * Decode the SSE `data:` JSON into a status, or `null` for a frame we can't
 * parse. The page ignores an undecodable frame rather than navigating on
 * garbage — the next GET (`loadMine`) remains the source of truth.
 */
export function parseStatusChanged(data: string): string | null {
  try {
    const payload = JSON.parse(data) as Partial<StatusChangedPayload>;
    return typeof payload.status === 'string' ? payload.status : null;
  } catch {
    return null;
  }
}

/**
 * What the pending page does on an incoming status. `approved` graduates the
 * pilot into the club (token refresh → `/start`); the two terminal-but-not-
 * joined outcomes route back to `/join` (denied first surfaces the reason on
 * the pending page via a `loadMine`, so the CTA can echo it). A `pending`
 * echo or an unknown status is a no-op.
 */
export type StatusAction = 'refresh-and-start' | 'show-denied' | 'to-join' | 'none';

export function actionForStatus(status: string | null): StatusAction {
  switch (status) {
    case 'APPROVED':
      return 'refresh-and-start';
    case 'DENIED':
      return 'show-denied';
    case 'WITHDRAWN':
      return 'to-join';
    default:
      return 'none';
  }
}
