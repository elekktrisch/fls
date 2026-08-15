import { Routes } from '@angular/router';

import { authGuard } from '@core/session/session.guard';

export const MIGRATE_HANDSHAKE_ROUTES: Routes = [
  {
    path: 'start',
    loadComponent: () =>
      import('./migrate-handshake.page').then((m) => m.MigrateHandshakePageComponent),
    canActivate: [authGuard],
    data: { showNavBar: false },
  },
];
