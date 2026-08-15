import type { HttpErrorResponse } from '@angular/common/http';

export interface ProblemDetailBody {
  readonly type?: string;
  readonly detail?: string;
  readonly message?: string;
  readonly field?: string;
}

export interface SaveErrorOutcome<K extends string> {
  readonly saveError: string;
  readonly saveErrorKind: K;
}

export interface SaveErrorRule<K extends string> {
  readonly status: number;
  readonly when?: (body: ProblemDetailBody) => boolean;
  readonly outcome: (body: ProblemDetailBody, e: HttpErrorResponse) => SaveErrorOutcome<K>;
}

export function problemDetailBody(e: HttpErrorResponse): ProblemDetailBody | null {
  return (e.error ?? null) as ProblemDetailBody | null;
}

export function classifyApiError<K extends string>(
  e: HttpErrorResponse,
  narrowestFirstRules: readonly SaveErrorRule<K>[],
  fallback: (body: ProblemDetailBody | null, e: HttpErrorResponse) => SaveErrorOutcome<K>,
): SaveErrorOutcome<K> {
  const body = problemDetailBody(e);
  for (const rule of narrowestFirstRules) {
    if (rule.status !== e.status) continue;
    if (rule.when && !rule.when(body ?? {})) continue;
    return rule.outcome(body ?? {}, e);
  }
  return fallback(body, e);
}

export function genericSaveErrorMessage(
  body: ProblemDetailBody | null,
  e: HttpErrorResponse,
): string {
  return body?.detail ?? body?.message ?? e.message;
}
