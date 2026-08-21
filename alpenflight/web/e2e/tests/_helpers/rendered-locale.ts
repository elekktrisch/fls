import { type Page } from '@playwright/test';

import de, { type Translations } from '../../../src/i18n/de';
import en from '../../../src/i18n/en';
import fr from '../../../src/i18n/fr';
import it from '../../../src/i18n/it';

const BUNDLE_PER_RENDERED_LANG: Readonly<Record<string, Translations>> = { de, en, fr, it };

export async function labelInTheLocaleTheSessionRenders(
  page: Page,
  pickLabel: (translations: Translations) => string,
): Promise<string> {
  const renderedLang = ((await page.locator('html').getAttribute('lang')) ?? '').toLowerCase();
  const bundle = BUNDLE_PER_RENDERED_LANG[renderedLang];
  if (!bundle) {
    throw new Error(
      `the application renders <html lang="${renderedLang}">, and no shipped locale bundle ` +
        `carries that language — the session locale and the asserted label cannot agree`,
    );
  }
  return pickLabel(bundle);
}
