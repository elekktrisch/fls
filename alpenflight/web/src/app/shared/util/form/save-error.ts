import type { HttpErrorResponse } from '@angular/common/http';

export interface ApiErrorBody {
  readonly key?: string;
  readonly message?: string;
  readonly field?: string;
}

export function mapApiSaveError(
  e: HttpErrorResponse,
  keyMessages: Readonly<Record<string, string>>,
  options: {
    readonly statusMessages?: Readonly<Record<number, string>>;
    readonly fallback?: string;
  } = {},
): string {
  const body = (e.error ?? null) as ApiErrorBody | null;
  const keyed = body?.key ? keyMessages[body.key] : undefined;
  if (keyed) return keyed;
  if (body && typeof body.message === 'string' && body.message.length > 0) {
    return body.field ? `${body.field}: ${body.message}` : body.message;
  }
  const byStatus = options.statusMessages?.[e.status];
  if (byStatus) return byStatus;
  return options.fallback ?? e.message;
}
