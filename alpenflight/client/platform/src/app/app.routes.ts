import { Routes } from '@angular/router';
import { Home } from './home/home';
import { DestinationPlaceholder } from './shell/destination-placeholder';

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
  { path: '**', redirectTo: '' },
];
