import { Routes } from '@angular/router';
import { Home } from './home/home';
import { DestinationPlaceholder } from './shell/destination-placeholder';
import { ComponentSpike } from './dev/component-spike';

export interface Destination {
  readonly path: string;
  readonly label: string;
}

// Single source of truth for the destination nav: Shell derives its links from this list instead
// of hand-duplicating labels, so a rename here cannot silently desync the nav text from the page.
export const DESTINATIONS: readonly Destination[] = [
  { path: 'operate', label: 'Operate' },
  { path: 'plan', label: 'Plan' },
  { path: 'records', label: 'Records' },
  { path: 'admin', label: 'Admin' },
];

export const routes: Routes = [
  { path: '', component: Home },
  ...DESTINATIONS.map((destination) => ({
    path: destination.path,
    component: DestinationPlaceholder,
    data: { label: destination.label },
  })),
  // Story 1.5 deferred spike (deferred-work.md, source_spec spec-1-5): ng-zorro-antd dark-theme
  // override cost probe. Never a real destination — deliberately absent from DESTINATIONS/the nav.
  // Its NzI18n/NzDateAdapter providers live in app.config.ts, not here: NzI18nService is
  // providedIn: 'root', so a route-scoped provider can't reach it (root services resolve their
  // constructor injections against the root injector, not the requesting route's injector).
  { path: 'dev/component-spike', component: ComponentSpike },
  { path: '**', redirectTo: '' },
];
