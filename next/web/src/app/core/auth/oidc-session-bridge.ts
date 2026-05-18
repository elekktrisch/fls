import { Injectable } from '@angular/core';

import type { User } from '../session/session.store';

export interface SessionPort {
  login(user: User, clubId: string | null): void;
  logout(): void;
  isAuthenticated(): boolean;
}

export function applyClaimsToSession(_claims: unknown, _session: SessionPort): void {
  throw new Error('S-021 stub: applyClaimsToSession');
}

export function handleSilentRenewFailed(
  _session: SessionPort,
  _reauthorize: () => void,
): void {
  throw new Error('S-021 stub: handleSilentRenewFailed');
}

@Injectable({ providedIn: 'root' })
export class OidcSessionBridge {
  constructor() {
    throw new Error('S-021 stub: OidcSessionBridge constructor');
  }
}
