import { inject } from '@angular/core';
import { type CanActivateFn } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';

import { SessionStore } from './session.store';

/**
 * Default-deny route guard. Public routes opt-in via `data.publicAccess === true`.
 *
 * Reads SessionStore (the only seam app code touches) and falls back to
 * the OIDC library when an unauthenticated principal hits a private route
 * — a hard redirect to Keycloak per [ADR 0007] §"Hard-401 redirect."
 *
 * Returns `false` (defer) while the session is still resolving so a hard
 * refresh does not race the OIDC `checkAuth()` resolution. Under the
 * normal boot path, `withAppInitializerAuthCheck()` blocks bootstrap
 * until the status settles, so this branch only fires on rare
 * post-init resolves.
 */
export const authGuard: CanActivateFn = (route) => {
  const session = inject(SessionStore);
  const oidc = inject(OidcSecurityService);

  if (route.data['publicAccess'] === true) {
    return true;
  }
  if (session.isLoadingSession()) {
    return false;
  }
  if (session.isAuthenticated()) {
    return true;
  }
  oidc.authorize();
  return false;
};
