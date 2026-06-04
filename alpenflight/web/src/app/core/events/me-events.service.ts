import { DestroyRef, Injectable, InjectionToken, effect, inject } from '@angular/core';
import { type EventSourceMessage, fetchEventSource } from '@microsoft/fetch-event-source';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { Observable, Subject, firstValueFrom, map } from 'rxjs';

import { SessionStore } from '../session/session.store';

import {
  ME_EVENTS_URL,
  type MeEvent,
  classifyOpen,
  reconnectDelayMs,
  toMeEvent,
} from './me-events.core';

/**
 * Thrown to tell `@microsoft/fetch-event-source` to STOP (a fatal `onerror`
 * re-throw): a 401/403 or an intentional close. The library's default `onerror`
 * (returning nothing) auto-retries with backoff; throwing aborts the operation.
 */
class FatalStreamError extends Error {}

/** Minimal shape `onopen` needs from the fetch `Response`. */
interface OpenResponse {
  readonly status: number;
  readonly headers: { get(name: string): string | null };
}

/**
 * Pluggable transport so the spec can drive the open/close/reconnect/dispatch
 * logic without the real library or a server (the `core/auth/oidc-session-bridge`
 * extract-pure-core pattern). Production binds it to
 * `@microsoft/fetch-event-source`; the spec provides a fake via
 * {@link SSE_TRANSPORT}.
 */
export interface SseTransport {
  open(
    url: string,
    init: {
      headers: Record<string, string>;
      signal: AbortSignal;
      onopen: (response: OpenResponse) => Promise<void>;
      onmessage: (frame: EventSourceMessage) => void;
      onerror: (err: unknown) => number | void;
    },
  ): Promise<void>;
}

const fetchEventSourceTransport: SseTransport = {
  open(url, init) {
    return fetchEventSource(url, {
      headers: init.headers,
      signal: init.signal,
      // Keep the stream alive in a background tab — the dashboard tile must
      // still update when the user flips back. The default aborts+reopens on
      // every visibilitychange, churning tokens + reconnect counters.
      openWhenHidden: true,
      onopen: (response) => init.onopen(response),
      onmessage: init.onmessage,
      onerror: init.onerror,
    });
  },
};

/**
 * DI seam for the SSE transport. Defaults to the real
 * `@microsoft/fetch-event-source` binding; the spec overrides it with a fake.
 */
export const SSE_TRANSPORT = new InjectionToken<SseTransport>('SSE_TRANSPORT', {
  providedIn: 'root',
  factory: () => fetchEventSourceTransport,
});

/**
 * Frontend SSE client for the per-principal channel (`GET /api/v1/me/events`,
 * S-176 / J-3). Opened once per authenticated session (wired from the app
 * bootstrap, NOT per component) and torn down on logout. Exposes events
 * per kind via {@link on} so dashboard tiles (T-09 club-admin today-count,
 * T-11 sysadmin) react to a `flight.created` push without a reload.
 *
 * Why not native `EventSource`: it cannot set `Authorization: Bearer`, and the
 * channel is Bearer-gated (AC5). `@microsoft/fetch-event-source` is a
 * fetch-stream reader, so the existing OIDC access token rides as a header
 * (carve decision, journey "SSE transport — Browser auth"). It also gives us
 * transparent backoff reconnect (AC7) via its `onerror` contract.
 *
 * Zoneless note: the library callbacks run outside any Angular zone, but the
 * app is zoneless (no zone.js) — `Subject.next` / signal writes schedule change
 * detection on their own, so no `NgZone.run` marshalling is needed. Events flow
 * through RxJS Subjects (a true event stream, CLAUDE.md §4); consumers bridge to
 * signals at the component boundary via `toSignal`.
 */
@Injectable({ providedIn: 'root' })
export class MeEventsService {
  private readonly oidc = inject(OidcSecurityService);
  private readonly session = inject(SessionStore);
  private readonly destroyRef = inject(DestroyRef);
  private readonly transport = inject(SSE_TRANSPORT);

  /** One Subject per event-kind, created lazily on first `on(kind)`/dispatch. */
  private readonly byKind = new Map<string, Subject<MeEvent>>();

  /** Aborts the in-flight stream on an intentional close (logout / destroy). */
  private controller: AbortController | null = null;
  /** Set on an intentional close so a racing reconnect does not re-open. */
  private intentionalClose = false;
  private reconnectAttempt = 0;

  constructor() {
    // Open when the OIDC session authenticates; close on logout. Session status
    // is a signal, so this effect re-runs on every transition (mirrors
    // OidcSessionBridge's userData effect).
    effect(() => {
      if (this.session.isAuthenticated()) {
        void this.openStream();
      } else {
        this.closeStream();
      }
    });
    this.destroyRef.onDestroy(() => this.closeStream());
  }

  /**
   * Subscribe to a single event kind. A component does
   * `meEvents.on('flight.created').subscribe(...)` (or bridges via `toSignal`).
   * The stream is owned by the session lifecycle, not the subscription —
   * subscribing never opens or closes it.
   */
  on(kind: string): Observable<MeEvent> {
    return this.subjectFor(kind).asObservable();
  }

  /** Convenience: just the decoded `data` payloads of one kind. */
  data(kind: string): Observable<string> {
    return this.on(kind).pipe(map((e) => e.data));
  }

  private subjectFor(kind: string): Subject<MeEvent> {
    let subject = this.byKind.get(kind);
    if (!subject) {
      subject = new Subject<MeEvent>();
      this.byKind.set(kind, subject);
    }
    return subject;
  }

  private dispatch(frame: EventSourceMessage): void {
    const event = toMeEvent(frame);
    if (event) {
      this.subjectFor(event.kind).next(event);
    }
  }

  private async openStream(): Promise<void> {
    if (this.controller) {
      return; // already streaming
    }
    this.intentionalClose = false;
    this.reconnectAttempt = 0;
    const controller = new AbortController();
    this.controller = controller;

    let token: string;
    try {
      token = await firstValueFrom(this.oidc.getAccessToken());
    } catch {
      // No token resolvable → treat as a fatal open; the session effect will
      // re-open once auth settles. Don't spin.
      if (this.controller === controller) {
        this.controller = null;
      }
      return;
    }

    const headers: Record<string, string> = token ? { Authorization: `Bearer ${token}` } : {};

    try {
      await this.transport.open(ME_EVENTS_URL, {
        headers,
        signal: controller.signal,
        onopen: async (response) => {
          const outcome = classifyOpen(response.status, response.headers.get('content-type'));
          if (outcome === 'ok') {
            this.reconnectAttempt = 0;
            return;
          }
          // fatal (401/403/4xx) → stop. retriable (5xx/429) → throw a non-fatal
          // error so the library backs off + retries.
          if (outcome === 'fatal') {
            throw new FatalStreamError(`me/events open rejected: ${response.status}`);
          }
          throw new Error(`me/events open transient: ${response.status}`);
        },
        onmessage: (frame) => this.dispatch(frame),
        onerror: (err) => {
          // Intentional close (logout/destroy) or a fatal open → rethrow to stop
          // the retry loop. Anything else is transient: return a bounded backoff
          // delay and let the library reconnect (AC7).
          if (this.intentionalClose || err instanceof FatalStreamError) {
            throw err instanceof Error ? err : new FatalStreamError(String(err));
          }
          this.reconnectAttempt += 1;
          return reconnectDelayMs(this.reconnectAttempt);
        },
      });
    } catch {
      // Fatal stop (rethrown above) — swallow so it doesn't surface as an
      // unhandled rejection.
    } finally {
      if (this.controller === controller) {
        this.controller = null;
      }
    }
  }

  private closeStream(): void {
    this.intentionalClose = true;
    this.reconnectAttempt = 0;
    this.controller?.abort();
    this.controller = null;
  }
}
