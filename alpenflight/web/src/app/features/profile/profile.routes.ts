import { Routes } from '@angular/router';

import { authGuard } from '@core/session/session.guard';

/**
 * `/profile` — the self-edit surface (J-4 / S-182). A logged-in principal
 * maintains their own Account / Personal / Pilot / Notifications data without an
 * admin. Entry is the nav-bar avatar dropdown (wired at the app shell — see
 * `app.component.ts` `userSummary()` + `af-nav-bar` Profile menuitem).
 *
 * `authGuard` gates it to an authenticated principal (any role with a `t_user`
 * row); `showNavBar: true` keeps the app chrome (and thus the avatar dropdown)
 * visible on `/profile`. The four tab bodies are stubs in T-03 — the real forms
 * + caller-scoped `PATCH /api/v1/me/*` calls land in T-05/T-07/T-09/T-11.
 */
export const PROFILE_ROUTES: Routes = [
  {
    path: '',
    canActivate: [authGuard],
    data: { showNavBar: true },
    loadComponent: () => import('./profile-shell.page').then((m) => m.ProfileShellPage),
  },
];
