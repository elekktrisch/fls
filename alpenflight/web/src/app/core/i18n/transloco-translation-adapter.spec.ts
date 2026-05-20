import { runInInjectionContext, Injector } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { vi } from 'vitest';

import { TranslocoTranslationAdapter } from './transloco-translation-adapter';

describe('TranslocoTranslationAdapter', () => {
  const setActiveLang = vi.fn();

  const make = (): TranslocoTranslationAdapter => {
    const injector = Injector.create({
      providers: [{ provide: TranslocoService, useValue: { setActiveLang } }],
    });
    return runInInjectionContext(injector, () => new TranslocoTranslationAdapter());
  };

  beforeEach(() => setActiveLang.mockClear());

  it('delegates setActiveLang to TranslocoService', () => {
    make().setActiveLang('fr');
    expect(setActiveLang).toHaveBeenCalledExactlyOnceWith('fr');
  });

  it('forwards every supported locale unchanged', () => {
    const adapter = make();
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
