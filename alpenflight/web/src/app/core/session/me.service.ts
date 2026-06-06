import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import type { Observable } from 'rxjs';

import type { AppRole } from './session.store';

/**
 * Wire shape of {@code GET /api/v1/me} from the server (S-165). Hand-written
 * for now — the OpenAPI codegen will produce an equivalent
 * {@code @api/generated/me/me.service.ts} once {@code openapi.json} is
 * regenerated; this seam can swap then with no consumer change.
 */
export interface MeResponse {
  id: string | null;
  personId: string | null;
  clubId: string | null;
  roles: readonly string[];
  firstName: string | null;
  lastName: string | null;
  email: string | null;
  username: string | null;
  // BCP-47 code of the user's persisted `t_user.language_id` (e.g. `de`,
  // `fr`). Lets cold-start honor the saved language preference without a
  // second round-trip — see SessionStore.loadMe + core/i18n. Null when no
  // user row matches the JWT sub.
  languageCode: string | null;
}

/**
 * Partial that the {@link SessionStore#loadMe} method patches onto
 * {@code authenticatedUser}. Role list is preserved as-is from the JWT —
 * {@code /me} echoes the same realm roles, but the JWT-derived list is
 * authoritative for client-side {@code hasRole} predicates.
 */
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
