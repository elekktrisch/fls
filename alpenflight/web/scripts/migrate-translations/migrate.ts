/**
 * One-shot legacy-translations migration.
 *
 *   pnpm tsx scripts/migrate-translations/migrate.ts \
 *     ../../flsserver/database/FLSTest/3\ insert/10\ insert\ internationalisation\ values.sql
 *
 * Parses the legacy `LanguageTranslations` INSERT seed, renames keys
 * (`ALL_CAPS_UNDERSCORE` → flat-dotted-lowercase), strips HTML, applies
 * a German fallback to the non-canonical locales, and writes the four
 * TypeScript locale modules at `src/i18n/<locale>.ts`. Re-runnable;
 * overwrites in full.
 *
 * Orphan filter: source-code references are inventoried via the same
 * grep set the `i18n-key-coverage.spec.ts` gate uses (`t('key')`,
 * `'key' | translate`, `translocoService.translate('key')`). Legacy
 * keys not referenced get dropped at land-time; subsequent feature
 * stories add their own keys to `de.ts` directly.
 *
 * Per S-057 design notes (`docs/modernization/stories/implemented/S-057-*.md`).
 */

import { readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { parseLegacySql } from './parse-legacy';
import {
  mapLegacyToBundles,
  renameKey,
  SUPPORTED_LOCALES,
  type LocaleCode,
  type TranslationTree,
} from './transform';
import { emitLocaleSource } from './emit';

const __dirname = dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = resolve(__dirname, '..', '..');
const I18N_DIR = resolve(PROJECT_ROOT, 'src', 'i18n');

const T_DIRECTIVE = /\bt\(\s*['"]([^'"\\]+)['"]/g;
const TRANSLATE_PIPE = /['"]([^'"\\]+)['"]\s*\|\s*translate\b/g;
const TRANSLATE_SERVICE = /translocoService\s*\.\s*(?:translate|selectTranslate)\(\s*['"]([^'"\\]+)['"]/g;

function walkSource(dir: string, acc: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    if (entry.startsWith('.') || entry === 'node_modules') continue;
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      walkSource(full, acc);
    } else if (
      (full.endsWith('.ts') || full.endsWith('.html')) &&
      !full.endsWith('.spec.ts') &&
      !full.includes(`${join('src', 'i18n')}${process.platform === 'win32' ? '\\' : '/'}`)
    ) {
      acc.push(full);
    }
  }
  return acc;
}

function inventoryReferencedKeys(srcRoot: string): Set<string> {
  const keys = new Set<string>();
  for (const file of walkSource(srcRoot)) {
    const text = readFileSync(file, 'utf-8');
    for (const re of [T_DIRECTIVE, TRANSLATE_PIPE, TRANSLATE_SERVICE]) {
      re.lastIndex = 0;
      let m: RegExpExecArray | null;
      while ((m = re.exec(text)) !== null) keys.add(m[1] as string);
    }
  }
  return keys;
}

async function loadExistingBundle(locale: LocaleCode): Promise<TranslationTree> {
  const modulePath = pathToFileURL(join(I18N_DIR, `${locale}.ts`)).href;
  try {
    const mod = await import(modulePath);
    return (mod.default as TranslationTree) ?? {};
  } catch {
    return {};
  }
}

async function loadExistingBundles(): Promise<Record<LocaleCode, TranslationTree>> {
  const entries = await Promise.all(
    SUPPORTED_LOCALES.map(async (loc) => [loc, await loadExistingBundle(loc)] as const),
  );
  return Object.fromEntries(entries) as Record<LocaleCode, TranslationTree>;
}

async function main(): Promise<void> {
  const [, , sqlPathArg] = process.argv;
  if (!sqlPathArg) {
    console.error('Usage: tsx migrate.ts <path-to-legacy-translation-sql>');
    process.exit(2);
  }

  const sqlPath = resolve(process.cwd(), sqlPathArg);
  const sql = readFileSync(sqlPath, 'utf-8');
  const rows = parseLegacySql(sql);
  console.log(`Parsed ${rows.length} legacy translation rows from ${sqlPath}`);

  const referenced = inventoryReferencedKeys(resolve(PROJECT_ROOT, 'src'));
  console.log(`Inventoried ${referenced.size} translation keys referenced in src/`);

  const existing = await loadExistingBundles();
  const result = mapLegacyToBundles(rows, referenced, existing);

  for (const item of result.htmlStripped) {
    console.warn(`  HTML stripped — ${item.locale}.${item.key}: ${JSON.stringify(item.before)} → ${JSON.stringify(item.after)}`);
  }
  if (result.survivingHtml.length > 0) {
    console.error('HTML survived stripping; aborting before write:');
    for (const item of result.survivingHtml) {
      console.error(`  ${item.locale}.${item.key} = ${JSON.stringify(item.value)}`);
    }
    process.exit(1);
  }
  if (result.droppedAsOrphan.length > 0) {
    console.log(`Dropped ${result.droppedAsOrphan.length} orphan legacy keys (not referenced in src/):`);
    for (const key of result.droppedAsOrphan.slice(0, 20)) console.log(`  ${key}`);
    if (result.droppedAsOrphan.length > 20) {
      console.log(`  …and ${result.droppedAsOrphan.length - 20} more.`);
    }
  }

  for (const locale of SUPPORTED_LOCALES) {
    const out = join(I18N_DIR, `${locale}.ts`);
    writeFileSync(out, emitLocaleSource(locale, result.bundles[locale]), 'utf-8');
    console.log(`Wrote ${out}`);
  }

  console.log('Done.');
}

// Export for testing.
export { inventoryReferencedKeys, renameKey };

if (import.meta.url === `file://${process.argv[1]}` || import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  main().catch((err) => {
    console.error(err);
    process.exit(1);
  });
}
