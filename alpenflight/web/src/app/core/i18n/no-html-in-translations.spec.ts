// Aliased to avoid shadowing vitest's globally-injected `it()`.
import deBundle from '../../../i18n/de';
import enBundle from '../../../i18n/en';
import frBundle from '../../../i18n/fr';
import itBundle from '../../../i18n/it';

/**
 * Guard against translator-pasted HTML slipping into a locale file
 * (or surviving the migration script's strip step). Angular interpolation
 * auto-escapes, so an HTML tag in a value renders as literal `&lt;b&gt;`
 * text — not a security hole, but never the intended UX. CLAUDE.md
 * §6 / §10 forbid `[innerHTML]` + `bypassSecurityTrust*` as escape
 * hatches; this spec keeps the rule enforceable at PR time.
 */

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

const HTML_LIKE = /<[a-z][^>]*>|<\/[a-z][^>]*>/i;

describe('no HTML in translation values', () => {
  const bundles = { de: deBundle, en: enBundle, fr: frBundle, it: itBundle } as const;

  for (const [locale, tree] of Object.entries(bundles)) {
    it(`${locale}.ts has no HTML-shaped values`, () => {
      const offenders: Array<{ key: string; value: string }> = [];
      for (const [key, value] of leafEntries(tree as Tree)) {
        if (HTML_LIKE.test(value)) {
          offenders.push({ key, value });
        }
      }
      if (offenders.length > 0) {
        throw new Error(
          `HTML-shaped values found — Angular interpolation will escape them; ` +
            `fix the translation or split into separate keys:\n` +
            offenders.map((o) => `  ${o.key} = ${JSON.stringify(o.value)}`).join('\n'),
        );
      }
    });
  }
});
