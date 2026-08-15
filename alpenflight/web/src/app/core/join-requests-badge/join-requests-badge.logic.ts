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

export function isPendingSubmit(data: string): boolean {
  return parseStatus(data) === 'PENDING';
}

export function decrementClampedAtZero(count: number): number {
  return count > 0 ? count - 1 : 0;
}
