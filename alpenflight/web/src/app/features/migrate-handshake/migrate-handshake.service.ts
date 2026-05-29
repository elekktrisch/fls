import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import type { Observable } from 'rxjs';

/**
 * S-140 handshake API contract. Two endpoints; the response shape is
 * exactly three fields (uploadId + publicKeyPem + expiresAt — asserted
 * by the integration test on the server side too). Direct {@link HttpClient}
 * because the OpenAPI snapshot has not been regenerated yet — switch to
 * the generated service once the snapshot lands.
 */
export interface HandshakeResponse {
  uploadId: string;
  publicKeyPem: string;
  expiresAt: string;
}

@Injectable({ providedIn: 'root' })
export class MigrateHandshakeService {
  readonly #http = inject(HttpClient);

  /** POST /api/v1/migrations/handshake — mint a fresh keypair (silently supersedes any prior in-flight row). */
  issue(): Observable<HandshakeResponse> {
    // The server rejects a body with 400, so an empty body is the wire shape.
    return this.#http.post<HandshakeResponse>('/api/v1/migrations/handshake', null);
  }

  /** GET /api/v1/migrations/handshake/current — restore the caller's in-flight row, or 404. */
  current(): Observable<HandshakeResponse> {
    return this.#http.get<HandshakeResponse>('/api/v1/migrations/handshake/current');
  }
}
