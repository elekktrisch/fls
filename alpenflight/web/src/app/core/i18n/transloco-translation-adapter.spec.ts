import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { vi } from 'vitest';

import { TranslocoTranslationAdapter } from './transloco-translation-adapter';

describe('TranslocoTranslationAdapter', () => {
  const setActiveLang = vi.fn();

  beforeEach(() => {
    setActiveLang.mockClear();
    TestBed.configureTestingModule({
      providers: [{ provide: TranslocoService, useValue: { setActiveLang } }],
    });
  });

  it('delegates setActiveLang to TranslocoService', () => {
    const adapter = TestBed.inject(TranslocoTranslationAdapter);
    adapter.setActiveLang('fr');
    expect(setActiveLang).toHaveBeenCalledExactlyOnceWith('fr');
  });

  it('forwards every supported locale unchanged', () => {
    const adapter = TestBed.inject(TranslocoTranslationAdapter);
    for (const loc of ['de', 'fr', 'it', 'en'] as const) {
      adapter.setActiveLang(loc);
    }
    expect(setActiveLang).toHaveBeenCalledTimes(4);
    expect(setActiveLang).toHaveBeenNthCalledWith(1, 'de');
    expect(setActiveLang).toHaveBeenNthCalledWith(2, 'fr');
    expect(setActiveLang).toHaveBeenNthCalledWith(3, 'it');
    expect(setActiveLang).toHaveBeenNthCalledWith(4, 'en');
  });
});
