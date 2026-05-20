import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';

import { AVAILABLE_LOCALES } from '@core/i18n';
import { type AppLocale, LocaleService } from '@shared/ui/locale';

const LOCALE_AUTONYMS: Record<AppLocale, string> = {
  de: 'Deutsch',
  fr: 'Français',
  it: 'Italiano',
  en: 'English',
};

const LOCALE_SHORT: Record<AppLocale, string> = {
  de: 'DE',
  fr: 'FR',
  it: 'IT',
  en: 'EN',
};

/**
 * Inline button-row locale picker. Reads + writes through LocaleService,
 * which is the single switch for ng-zorro locale, transloco active lang,
 * and `<html lang>`. Each button renders the short code visibly + the
 * autonym (e.g. "Deutsch") as the accessible name.
 */
@Component({
  selector: 'af-lang-picker',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav class="flex gap-1" [attr.aria-label]="ariaLabel()">
      @for (loc of locales; track loc) {
        <button
          type="button"
          class="bg-transparent border px-2.5 py-1 text-sm tracking-wide cursor-pointer focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand-500 focus-visible:outline-offset-2"
          [class.border-transparent]="loc !== active()"
          [class.text-slate-500]="loc !== active()"
          [class.hover:text-slate-900]="loc !== active()"
          [class.text-slate-900]="loc === active()"
          [class.border-slate-200]="loc === active()"
          [attr.aria-pressed]="loc === active()"
          [attr.aria-label]="autonym(loc)"
          [attr.data-testid]="'af-lang-' + loc"
          (click)="pick(loc)"
        >
          {{ short(loc) }}
        </button>
      }
    </nav>
  `,
})
export class AfLangPickerComponent {
  readonly #locale = inject(LocaleService);

  readonly ariaLabel = input<string>('Language');

  protected readonly locales: readonly AppLocale[] = AVAILABLE_LOCALES;
  protected readonly active = this.#locale.current;

  protected short(loc: AppLocale): string {
    return LOCALE_SHORT[loc];
  }
  protected autonym(loc: AppLocale): string {
    return LOCALE_AUTONYMS[loc];
  }
  protected pick(loc: AppLocale): void {
    this.#locale.set(loc);
  }
}
