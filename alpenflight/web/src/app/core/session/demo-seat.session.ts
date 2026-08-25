import { HttpErrorResponse, type HttpInterceptorFn } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';

import type { ProblemDetail } from '@api/generated/model';

const DEMO_SEAT_ACCESS_TOKEN_STORAGE_KEY = 'alpenflight.demo-seat-access-token';

const API_PATH_PREFIX = '/api/v1/';

const ANONYMOUS_PUBLIC_PATH_PREFIX_A_SEAT_BEARER_MUST_NOT_RIDE = '/api/v1/public/';

const SEAT_BUSY_STATUS = 503;

export function claimsOfAccessToken(accessToken: string): unknown {
  const payload = accessToken.split('.')[1];
  if (payload === undefined) {
    return null;
  }
  try {
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
    const bytes = Uint8Array.from(atob(padded), (character) => character.charCodeAt(0));
    return JSON.parse(new TextDecoder().decode(bytes)) as unknown;
  } catch {
    return null;
  }
}

export function seatBearerRidesThisRequest(url: string): boolean {
  return (
    url.startsWith(API_PATH_PREFIX) &&
    !url.startsWith(ANONYMOUS_PUBLIC_PATH_PREFIX_A_SEAT_BEARER_MUST_NOT_RIDE)
  );
}

export function seatBusyProblemOf(failure: unknown): ProblemDetail | null {
  if (!(failure instanceof HttpErrorResponse) || failure.status !== SEAT_BUSY_STATUS) {
    return null;
  }
  const body: unknown = failure.error;
  if (typeof body === 'string') {
    try {
      return JSON.parse(body) as ProblemDetail;
    } catch {
      return {};
    }
  }
  return body !== null && typeof body === 'object' ? (body as ProblemDetail) : {};
}

function theTabScopedStore(): Storage | null {
  try {
    // eslint-disable-next-line no-restricted-globals
    return typeof sessionStorage === 'undefined' ? null : sessionStorage;
  } catch {
    return null;
  }
}

function readTheHeldSeatToken(): string | null {
  return theTabScopedStore()?.getItem(DEMO_SEAT_ACCESS_TOKEN_STORAGE_KEY) ?? null;
}

function writeTheHeldSeatToken(accessToken: string | null): void {
  const store = theTabScopedStore();
  if (store === null) {
    return;
  }
  if (accessToken === null) {
    store.removeItem(DEMO_SEAT_ACCESS_TOKEN_STORAGE_KEY);
    return;
  }
  store.setItem(DEMO_SEAT_ACCESS_TOKEN_STORAGE_KEY, accessToken);
}

@Injectable({ providedIn: 'root' })
export class DemoSeatSession {
  readonly #accessToken = signal<string | null>(readTheHeldSeatToken());

  readonly accessToken = this.#accessToken.asReadonly();

  readonly isLive = computed(() => this.#accessToken() !== null);

  hold(accessToken: string): void {
    writeTheHeldSeatToken(accessToken);
    this.#accessToken.set(accessToken);
  }

  release(): void {
    writeTheHeldSeatToken(null);
    this.#accessToken.set(null);
  }
}

export const demoSeatAuthInterceptor: HttpInterceptorFn = (request, next) => {
  const heldSeatToken = inject(DemoSeatSession).accessToken();
  if (heldSeatToken === null || !seatBearerRidesThisRequest(request.url)) {
    return next(request);
  }
  return next(request.clone({ setHeaders: { Authorization: `Bearer ${heldSeatToken}` } }));
};
