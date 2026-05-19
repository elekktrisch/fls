import { provideZonelessChangeDetection, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { ViewportService } from '../viewport/viewport.service';
import { DensityService } from './density.service';

interface ViewportStub {
  readonly _lg: ReturnType<typeof signal<boolean>>;
  isAtLeast: (bp: 'lg') => ReturnType<typeof signal<boolean>>;
}

function viewportStub(initialAtLeastLg: boolean): ViewportStub {
  const lg = signal(initialAtLeastLg);
  return {
    _lg: lg,
    isAtLeast: () => lg,
  };
}

describe('DensityService', () => {
  let vp: ViewportStub;

  beforeEach(() => {
    vp = viewportStub(false);
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), { provide: ViewportService, useValue: vp }],
    });
  });

  afterEach(() => TestBed.resetTestingModule());

  it("derives 'comfortable' when viewport is below lg", () => {
    const ds = TestBed.inject(DensityService);
    expect(ds.density()).toBe('comfortable');
  });

  it("derives 'dense' when viewport is at-least lg", () => {
    vp._lg.set(true);
    const ds = TestBed.inject(DensityService);
    expect(ds.density()).toBe('dense');
  });

  it("setOverride('dense') wins over viewport", () => {
    const ds = TestBed.inject(DensityService);
    expect(ds.density()).toBe('comfortable');
    ds.setOverride('dense');
    expect(ds.density()).toBe('dense');
  });

  it('clearOverride() restores viewport-derived value', () => {
    const ds = TestBed.inject(DensityService);
    ds.setOverride('dense');
    expect(ds.density()).toBe('dense');
    ds.clearOverride();
    expect(ds.density()).toBe('comfortable');
  });
});
