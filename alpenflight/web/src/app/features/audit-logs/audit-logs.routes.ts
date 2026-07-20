import { Routes } from '@angular/router';

import { clubAdminGuard } from '@core/session/club-admin.guard';

export const AUDIT_LOGS_ROUTES: Routes = [
  {
    path: '',
    canActivate: [clubAdminGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./list/audit-logs-list.page').then((m) => m.AuditLogsListPage),
  },
];
