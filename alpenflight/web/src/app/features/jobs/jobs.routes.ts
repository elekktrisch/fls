import { Routes } from '@angular/router';

import { sysadminGuard } from '@core/session/sysadmin.guard';

export const JOBS_ROUTES: Routes = [
  {
    path: '',
    canActivate: [sysadminGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./list/jobs-list.page').then((m) => m.JobsListPage),
  },
];
