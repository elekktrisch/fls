import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { parseLegacySql } from './parse-legacy';
import { mapLegacyToBundles, renameKey } from './transform';

const __dirname = dirname(fileURLToPath(import.meta.url));
const FIXTURE = readFileSync(join(__dirname, '__fixtures__', 'legacy-sample.sql'), 'utf-8');

describe('renameKey', () => {
  it('joins underscore-separated tokens with a dot, lowercased', () => {
    expect(renameKey('AIRCRAFT_MODEL')).toBe('aircraft.model');
    expect(renameKey('ACCOUNTING_RULE_FILTER_ACTIVE')).toBe('accounting.rule.filter.active');
  });

  it('keeps single-token keys at the root', () => {
    expect(renameKey('MASTERDATA')).toBe('masterdata');
  });
});

describe('mapLegacyToBundles', () => {
  const rows = parseLegacySql(FIXTURE);
  const referenced = new Set([
    'aircraft.model',
    'masterdata',
    'accounting.rule.filter.active',
    'with.html',
    'with.entity',
    'with.apostrophe',
    // 'orphan.key' deliberately absent — should be dropped.
  ]);

  it('places German keys at the lowercase + dotted path in de', () => {
    const { bundles } = mapLegacyToBundles(rows, referenced);
    expect((bundles.de as { aircraft: { model: string } }).aircraft.model).toBe('Modell');
    expect((bundles.de as { masterdata: string }).masterdata).toBe('Stammdaten');
  });

  it('drops legacy keys that no source-tree reference grabbed', () => {
    const { droppedAsOrphan } = mapLegacyToBundles(rows, referenced);
    expect(droppedAsOrphan).toContain('orphan.key');
  });

  it('strips HTML tags + decodes named entities, with the change logged', () => {
    const { bundles, htmlStripped, survivingHtml } = mapLegacyToBundles(rows, referenced);
    const de = bundles.de as { with: { html: string; entity: string } };
    expect(de.with.html).toBe('fett');
    expect(de.with.entity).toBe('Café & Tee');
    expect(htmlStripped.map((h) => h.key)).toEqual(expect.arrayContaining(['with.html', 'with.entity']));
    expect(survivingHtml).toEqual([]);
  });

  it('mirrors the German value into fr/it/en when no legacy translation exists (fallback rule)', () => {
    const { bundles } = mapLegacyToBundles(rows, referenced);
    const get = (loc: 'de' | 'fr' | 'it' | 'en') =>
      (bundles[loc] as { aircraft: { model: string } }).aircraft.model;
    expect(get('de')).toBe('Modell');
    expect(get('fr')).toBe('Modell');
    expect(get('it')).toBe('Modell');
    expect(get('en')).toBe('Modell');
  });

  it('skips rows with an unknown LanguageId (defensive)', () => {
    const { bundles } = mapLegacyToBundles(rows, referenced);
    const flat = JSON.stringify(bundles);
    expect(flat).not.toContain('should be skipped');
  });

  it('emits all four locales even when the dump carries only one', () => {
    const { bundles } = mapLegacyToBundles(rows, referenced);
    expect(Object.keys(bundles).sort()).toEqual(['de', 'en', 'fr', 'it']);
  });

  it('skips orphan filtering when the referenced set is empty', () => {
    const { bundles, droppedAsOrphan } = mapLegacyToBundles(rows, new Set());
    expect(droppedAsOrphan).toEqual([]);
    expect((bundles.de as { orphan: { key: string } }).orphan.key).toBe('kein Konsument');
  });

  it('preserves screen-author content from existing bundles on re-runs', () => {
    const existing = {
      de: { landing: { hello: 'Hallo' } },
      fr: { landing: { hello: 'Bonjour' } },
      it: { landing: { hello: 'Ciao' } },
      en: { landing: { hello: 'Hello' } },
    };
    const { bundles } = mapLegacyToBundles(rows, referenced, existing);

    const get = (loc: 'de' | 'fr' | 'it' | 'en') =>
      (bundles[loc] as { landing: { hello: string } }).landing.hello;

    expect(get('de')).toBe('Hallo');
    expect(get('fr')).toBe('Bonjour');
    expect(get('it')).toBe('Ciao');
    expect(get('en')).toBe('Hello');
    expect((bundles.de as { aircraft: { model: string } }).aircraft.model).toBe('Modell');
  });

  it('reports key collisions instead of throwing mid-walk', () => {
    const collisionRow = [
      { key: 'AIRCRAFT', languageId: 1, value: 'Flugzeug' },
      // Renames to `aircraft` (string at root), then `AIRCRAFT_MODEL`
      // renames to `aircraft.model` and tries to nest under the string.
      { key: 'AIRCRAFT_MODEL', languageId: 1, value: 'Modell' },
    ];
    const { keyCollisions } = mapLegacyToBundles(
      collisionRow,
      new Set(['aircraft', 'aircraft.model']),
    );
    expect(keyCollisions.length).toBe(1);
    expect(keyCollisions[0]?.path).toBe('aircraft.model');
  });
});
