import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { NzI18nService, de_DE, fr_FR } from 'ng-zorro-antd/i18n';

import { LocaleService, type AppLocale } from './locale.service';
import { TRANSLATION_ADAPTER } from './translation-adapter';

describe('LocaleService', () => {
  const nzI18n = { setLocale: vi.fn() };
  const setActiveLang = vi.fn<(locale: AppLocale) => void>();
  const adapter = { setActiveLang };

  beforeEach(() => {
    nzI18n.setLocale.mockReset();
    setActiveLang.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: NzI18nService, useValue: nzI18n },
        { provide: TRANSLATION_ADAPTER, useValue: adapter },
      ],
    });
  });

  afterEach(() => TestBed.resetTestingModule());

  it("defaults current() to 'de'", () => {
    const svc = TestBed.inject(LocaleService);
    expect(svc.current()).toBe('de');
  });

  it("set('de') invokes nzI18n.setLocale(de_DE), adapter.setActiveLang('de'), and updates document.lang", () => {
    const svc = TestBed.inject(LocaleService);
    svc.set('de');
    expect(nzI18n.setLocale).toHaveBeenCalledWith(de_DE);
    expect(setActiveLang).toHaveBeenCalledWith('de');
    expect(document.documentElement.lang).toBe('de');
    expect(svc.current()).toBe('de');
  });

  it("set('fr') swaps to fr_FR", () => {
    const svc = TestBed.inject(LocaleService);
    svc.set('fr');
    expect(nzI18n.setLocale).toHaveBeenCalledWith(fr_FR);
    expect(setActiveLang).toHaveBeenCalledWith('fr');
    expect(document.documentElement.lang).toBe('fr');
    expect(svc.current()).toBe('fr');
  });

  it('throws on unknown locale tokens', () => {
    const svc = TestBed.inject(LocaleService);
    expect(() => svc.set('xx' as never)).toThrow(/unsupported locale/);
  });
});
