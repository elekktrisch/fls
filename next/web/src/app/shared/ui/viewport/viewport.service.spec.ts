import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { ViewportService } from './viewport.service';

type MqlListener = (event: { matches: boolean }) => void;

interface FakeMql {
  matches: boolean;
  addEventListener: (type: 'change', listener: MqlListener) => void;
  removeEventListener: (type: 'change', listener: MqlListener) => void;
  fire: (matches: boolean) => void;
}

function fakeMatchMedia(initial: Record<string, boolean>) {
  const registry = new Map<string, FakeMql>();
  const factory = (query: string): MediaQueryList => {
    if (!registry.has(query)) {
      const listeners = new Set<MqlListener>();
      const mql: FakeMql = {
        matches: initial[query] ?? false,
        addEventListener: (_type, l) => listeners.add(l),
        removeEventListener: (_type, l) => listeners.delete(l),
        fire: (matches: boolean) => {
          mql.matches = matches;
          for (const l of listeners) l({ matches });
        },
      };
      registry.set(query, mql);
    }
    return registry.get(query) as unknown as MediaQueryList;
  };
  return { factory, registry };
}

describe('ViewportService', () => {
  let mqls: ReturnType<typeof fakeMatchMedia>;

  beforeEach(() => {
    mqls = fakeMatchMedia({
      '(min-width: 360px)': true,
      '(min-width: 768px)': true,
      '(min-width: 1024px)': false,
      '(min-width: 1440px)': false,
    });
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      writable: true,
      value: mqls.factory,
    });
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection()],
    });
  });

  afterEach(() => TestBed.resetTestingModule());

  it('initialises isAtLeast signals from matchMedia.matches', () => {
    const vp = TestBed.inject(ViewportService);
    expect(vp.isAtLeast('sm')()).toBe(true);
    expect(vp.isAtLeast('md')()).toBe(true);
    expect(vp.isAtLeast('lg')()).toBe(false);
    expect(vp.isAtLeast('xl')()).toBe(false);
  });

  it('isBelow is the inverse of isAtLeast', () => {
    const vp = TestBed.inject(ViewportService);
    expect(vp.isBelow('lg')()).toBe(true);
    expect(vp.isBelow('sm')()).toBe(false);
  });

  it('updates isAtLeast when the MediaQueryList fires a change event', () => {
    const vp = TestBed.inject(ViewportService);
    expect(vp.isAtLeast('lg')()).toBe(false);

    (mqls.registry.get('(min-width: 1024px)') as unknown as FakeMql).fire(true);

    expect(vp.isAtLeast('lg')()).toBe(true);
    expect(vp.isBelow('lg')()).toBe(false);
  });
});
