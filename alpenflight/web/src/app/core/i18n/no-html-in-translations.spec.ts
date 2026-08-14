import deBundle from '../../../i18n/de';
import enBundle from '../../../i18n/en';
import frBundle from '../../../i18n/fr';
import itBundle from '../../../i18n/it';

interface Tree {
  [k: string]: string | Tree;
}

function* leafEntries(tree: Tree, prefix = ''): Generator<readonly [string, string]> {
  for (const [k, v] of Object.entries(tree)) {
    const path = prefix ? `${prefix}.${k}` : k;
    if (typeof v === 'string') {
      yield [path, v];
    } else {
      yield* leafEntries(v, path);
    }
  }
}

const HTML_TAG_LIKE = /<[a-z!?/][^>]*>/i;
const HTML_ENTITY_LIKE = /&(?:[a-z]+|#\d+|#x[0-9a-f]+);/i;

describe('no HTML in translation values', () => {
  const bundles = { de: deBundle, en: enBundle, fr: frBundle, it: itBundle } as const;

  for (const [locale, tree] of Object.entries(bundles)) {
    it(`${locale}.ts has no HTML-shaped values`, () => {
      const offenders: { key: string; value: string; reason: string }[] = [];
      for (const [key, value] of leafEntries(tree as Tree)) {
        if (HTML_TAG_LIKE.test(value)) {
          offenders.push({ key, value, reason: 'HTML tag / comment / CDATA' });
        } else if (HTML_ENTITY_LIKE.test(value)) {
          offenders.push({ key, value, reason: 'HTML entity (use the decoded character)' });
        }
      }
      if (offenders.length > 0) {
        throw new Error(
          `HTML-shaped values found — Angular interpolation will escape them; ` +
            `fix the translation or split into separate keys:\n` +
            offenders
              .map((o) => `  [${o.reason}] ${o.key} = ${JSON.stringify(o.value)}`)
              .join('\n'),
        );
      }
    });
  }
});
