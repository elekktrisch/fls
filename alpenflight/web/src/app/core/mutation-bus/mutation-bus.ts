import { InjectionToken } from '@angular/core';
import { Subject } from 'rxjs';

export type MutationEvent =
  | { kind: 'session.logout' }
  | { kind: 'session.tenantSwitch'; clubId: string }
  | { kind: 'aircraft.created'; aircraftId: string }
  | { kind: 'aircraft.updated'; aircraftId: string }
  | { kind: 'flight.booked'; flightId: string }
  | { kind: 'club.created'; id: string }
  | { kind: 'club.updated'; id: string }
  | { kind: 'club.deleted'; id: string };

export const MUTATION_BUS = new InjectionToken<Subject<MutationEvent>>('MUTATION_BUS', {
  providedIn: 'root',
  factory: () => new Subject<MutationEvent>(),
});
