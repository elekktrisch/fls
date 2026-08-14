export interface StatusChangedPayload {
  readonly status: string;
}

export function parseStatusChanged(data: string): string | null {
  try {
    const payload = JSON.parse(data) as Partial<StatusChangedPayload>;
    return typeof payload.status === 'string' ? payload.status : null;
  } catch {
    return null;
  }
}

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
