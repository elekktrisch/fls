import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { Subject } from 'rxjs';

import { MUTATION_BUS, type MutationEvent } from './mutation-bus';

describe('MUTATION_BUS', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection()],
    });
  });

  afterEach(() => TestBed.resetTestingModule());

  it('provides a Subject<MutationEvent> by default', () => {
    const bus = TestBed.inject(MUTATION_BUS);

    expect(bus).toBeInstanceOf(Subject);
  });

  it('emits subscribed events to consumers', () => {
    const bus = TestBed.inject(MUTATION_BUS);
    const received: MutationEvent[] = [];
    bus.subscribe((e) => received.push(e));

    bus.next({ kind: 'session.logout' });
    bus.next({ kind: 'session.tenantSwitch', clubId: 'club-1' });

    expect(received).toEqual([
      { kind: 'session.logout' },
      { kind: 'session.tenantSwitch', clubId: 'club-1' },
    ]);
  });
});
