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

class FatalStreamError extends Error {}

interface OpenResponse {
  readonly status: number;
  readonly headers: { get(name: string): string | null };
}

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
      openWhenHidden: true,
      onopen: (response) => init.onopen(response),
      onmessage: init.onmessage,
      onerror: init.onerror,
    });
  },
};

export const SSE_TRANSPORT = new InjectionToken<SseTransport>('SSE_TRANSPORT', {
  providedIn: 'root',
  factory: () => fetchEventSourceTransport,
});

@Injectable({ providedIn: 'root' })
export class MeEventsService {
  private readonly oidc = inject(OidcSecurityService);
  private readonly session = inject(SessionStore);
  private readonly destroyRef = inject(DestroyRef);
  private readonly transport = inject(SSE_TRANSPORT);

  private readonly byKind = new Map<string, Subject<MeEvent>>();

  private controller: AbortController | null = null;
  private intentionalClose = false;
  private reconnectAttempt = 0;

  constructor() {
    effect(() => {
      if (this.session.isAuthenticated()) {
        void this.openStream();
      } else {
        this.closeStream();
      }
    });
    this.destroyRef.onDestroy(() => this.closeStream());
  }

  on(kind: string): Observable<MeEvent> {
    return this.subjectFor(kind).asObservable();
  }

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
      return;
    }
    this.intentionalClose = false;
    this.reconnectAttempt = 0;
    const controller = new AbortController();
    this.controller = controller;

    let token: string;
    try {
      token = await firstValueFrom(this.oidc.getAccessToken());
    } catch {
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
          if (outcome === 'fatal') {
            throw new FatalStreamError(`me/events open rejected: ${response.status}`);
          }
          throw new Error(`me/events open transient: ${response.status}`);
        },
        onmessage: (frame) => this.dispatch(frame),
        onerror: (err) => {
          if (this.intentionalClose || err instanceof FatalStreamError) {
            throw err instanceof Error ? err : new FatalStreamError(String(err));
          }
          this.reconnectAttempt += 1;
          return reconnectDelayMs(this.reconnectAttempt);
        },
      });
    } catch (fatalOpenErrorOrIntentionalAbort) {
      void fatalOpenErrorOrIntentionalAbort;
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
