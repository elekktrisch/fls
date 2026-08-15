import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import type { Observable } from 'rxjs';

export interface HandshakeResponse {
  uploadId: string;
  publicKeyPem: string;
  expiresAt: string;
}

@Injectable({ providedIn: 'root' })
export class MigrateHandshakeService {
  readonly #http = inject(HttpClient);

  issue(): Observable<HandshakeResponse> {
    return this.#http.post<HandshakeResponse>('/api/v1/migrations/handshake', null);
  }

  current(): Observable<HandshakeResponse> {
    return this.#http.get<HandshakeResponse>('/api/v1/migrations/handshake/current');
  }
}
