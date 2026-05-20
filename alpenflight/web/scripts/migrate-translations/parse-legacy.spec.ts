import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { parseLegacySql } from './parse-legacy';

const __dirname = dirname(fileURLToPath(import.meta.url));
const FIXTURE = readFileSync(join(__dirname, '__fixtures__', 'legacy-sample.sql'), 'utf-8');

describe('parseLegacySql', () => {
  const rows = parseLegacySql(FIXTURE);

  it('parses every INSERT row from the fixture', () => {
    expect(rows).toHaveLength(8);
  });

  it('captures key + languageId + value', () => {
    expect(rows[0]).toEqual({ key: 'AIRCRAFT_MODEL', languageId: 1, value: 'Modell' });
  });

  it('preserves doubled single-quote escapes as a literal apostrophe', () => {
    const row = rows.find((r) => r.key === 'WITH_APOSTROPHE');
    expect(row?.value).toBe("L'avion");
  });

  it('preserves HTML / entity content unchanged at parse-time (sanitisation happens in transform)', () => {
    const html = rows.find((r) => r.key === 'WITH_HTML');
    expect(html?.value).toBe('<b>fett</b>');
    const entity = rows.find((r) => r.key === 'WITH_ENTITY');
    expect(entity?.value).toBe('Caf&eacute; &amp; Tee');
  });

  it('returns an empty array for non-matching input', () => {
    expect(parseLegacySql('SELECT * FROM Foo;')).toEqual([]);
  });
});
