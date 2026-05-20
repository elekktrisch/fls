import type { LocaleCode, TranslationTree } from './transform';
import { CANONICAL_LOCALE } from './transform';

const INDENT = '  ';

function serializeTree(tree: TranslationTree, depth: number): string {
  const keys = Object.keys(tree).sort();
  if (keys.length === 0) return '{}';
  const lines: string[] = ['{'];
  for (const key of keys) {
    const value = tree[key]!;
    const safeKey = /^[a-z][a-zA-Z0-9_$]*$/.test(key) ? key : JSON.stringify(key);
    const indent = INDENT.repeat(depth + 1);
    if (typeof value === 'string') {
      lines.push(`${indent}${safeKey}: ${JSON.stringify(value)},`);
    } else {
      lines.push(`${indent}${safeKey}: ${serializeTree(value, depth + 1)},`);
    }
  }
  lines.push(`${INDENT.repeat(depth)}}`);
  return lines.join('\n');
}

export function emitLocaleSource(locale: LocaleCode, tree: TranslationTree): string {
  const body = serializeTree(tree, 0);
  if (locale === CANONICAL_LOCALE) {
    return [
      '/**',
      ' * Canonical locale. The shape exported here defines `Translations` —',
      ' * every other locale file imports the type and is forced to mirror',
      ' * the shape exactly. Add a key here first; the other locales become',
      ' * compile errors until they add the same key.',
      ' */',
      `const ${locale} = ${body};`,
      '',
      'export type Translations = typeof de;',
      `export default ${locale};`,
      '',
    ].join('\n');
  }
  return [
    `import type { Translations } from './${CANONICAL_LOCALE}';`,
    '',
    `const ${locale}: Translations = ${body};`,
    '',
    `export default ${locale};`,
    '',
  ].join('\n');
}
