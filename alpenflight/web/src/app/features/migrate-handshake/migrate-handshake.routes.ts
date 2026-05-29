import { Routes } from '@angular/router';

import { authGuard } from '@core/session/session.guard';

/**
 * /migrate/* route tree. Inherits the auth guard (verified-email check
 * lives on the server-side @PreAuthorize). Lazy-loaded from app.routes.ts.
 */
export const MIGRATE_HANDSHAKE_ROUTES: Routes = [
  {
    path: 'start',
    loadComponent: () =>
      import('./migrate-handshake.page').then((m) => m.MigrateHandshakePageComponent),
    canActivate: [authGuard],
    data: { showNavBar: false },
  },
];
