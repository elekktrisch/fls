import { InjectionToken } from '@angular/core';
import { Subject } from 'rxjs';

export type MutationEvent =
  | { kind: 'session.logout' }
  | { kind: 'session.tenantSwitch'; clubId: string }
  | { kind: 'aircraft.created'; aircraftId: string }
  | { kind: 'aircraft.updated'; aircraftId: string }
  | { kind: 'aircraft.deleted'; aircraftId: string }
  | { kind: 'flight.booked'; flightId: string }
  | { kind: 'flight.created'; flightId: string }
  | { kind: 'flight.updated'; flightId: string }
  | { kind: 'flight.deleted'; flightId: string }
  | { kind: 'club.created'; id: string }
  | { kind: 'club.updated'; id: string }
  | { kind: 'club.deleted'; id: string }
  | { kind: 'location.created'; id: string }
  | { kind: 'location.updated'; id: string }
  | { kind: 'location.deleted'; id: string }
  | { kind: 'person.created'; id: string }
  | { kind: 'person.updated'; id: string }
  | { kind: 'person.deleted'; id: string }
  | { kind: 'flightType.created'; id: string }
  | { kind: 'flightType.updated'; id: string }
  | { kind: 'flightType.deleted'; id: string }
  | { kind: 'article.created'; id: string }
  | { kind: 'article.updated'; id: string }
  | { kind: 'article.deleted'; id: string }
  | { kind: 'user.created'; id: string }
  | { kind: 'user.updated'; id: string }
  | { kind: 'user.deleted'; id: string };

export const MUTATION_BUS = new InjectionToken<Subject<MutationEvent>>('MUTATION_BUS', {
  providedIn: 'root',
  factory: () => new Subject<MutationEvent>(),
});
