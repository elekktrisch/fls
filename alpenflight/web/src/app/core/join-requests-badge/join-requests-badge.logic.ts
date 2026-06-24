/**
 * The SSE `join-request.status-changed` frame the admin channel receives. The
 * backend (`JoinRequestSseListener`) only nudges club admins on a new PENDING
 * submit — APPROVED/DENIED/WITHDRAWN frames go to the pilot — so an inbound
 * frame can only grow the admin's queue.
 */
export interface BadgeStatusPayload {
  readonly status: string;
}

function parseStatus(data: string): string | null {
  try {
    const payload = JSON.parse(data) as Partial<BadgeStatusPayload>;
    return typeof payload.status === 'string' ? payload.status : null;
  } catch {
    return null;
  }
}

/** Whether an inbound SSE frame is a new PENDING submit (the count goes up). */
export function isPendingSubmit(data: string): boolean {
  return parseStatus(data) === 'PENDING';
}

/** Clamp at zero so a stray decrement (double event) never shows a negative pill. */
export function decrement(count: number): number {
  return count > 0 ? count - 1 : 0;
}
