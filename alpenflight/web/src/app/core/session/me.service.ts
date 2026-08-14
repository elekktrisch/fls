import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import type { Observable } from 'rxjs';

import type { AppRole } from './session.store';

export interface MeResponse {
  id: string | null;
  personId: string | null;
  clubId: string | null;
  roles: readonly string[];
  firstName: string | null;
  lastName: string | null;
  email: string | null;
  username: string | null;
  languageCode: string | null;
  homebaseLocationId: string | null;
}

export interface SessionUserPatch {
  id?: string;
  personId: string | null;
  clubId: string | null;
  firstName: string;
  lastName: string;
  email: string;
  username: string;
  roles: readonly AppRole[];
}

@Injectable({ providedIn: 'root' })
export class MeService {
  private readonly http = inject(HttpClient);

  getMe(): Observable<MeResponse> {
    return this.http.get<MeResponse>('/api/v1/me');
  }
}
