import { Routes } from '@angular/router';

import { tenantRequiredGuard } from '@core/session/tenant-required.guard';

export const PLANNING_ROUTES: Routes = [
  {
    path: '',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./list/planning-list.page').then((m) => m.PlanningListPage),
  },
  {
    path: 'new/:mode',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./edit/planning-edit.page').then((m) => m.PlanningEditPage),
  },
  {
    path: ':id/:mode',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./edit/planning-edit.page').then((m) => m.PlanningEditPage),
  },
];

export const PLANNING_SETUP_ROUTES: Routes = [
  {
    path: '',
    canActivate: [tenantRequiredGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./setup/planning-setup.page').then((m) => m.PlanningSetupPage),
  },
];
