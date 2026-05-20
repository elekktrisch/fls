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
 * existing entries are preserved.
 *
 * Orphan filter: source-code references are inventoried via the same
 * grep set the `i18n-key-coverage.spec.ts` gate uses (`t('key')`,
 * `'key' | translate`, `translocoService.translate('key')`). Legacy
 * keys not referenced get dropped at land-time; subsequent feature
 * stories add their own keys to `de.ts` directly.
 */

import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { parseLegacySql } from './parse-legacy';
import {
  mapLegacyToBundles,
  SUPPORTED_LOCALES,
  type LocaleCode,
  type TranslationTree,
} from './transform';
import { emitLocaleSource } from './emit';

const HERE = fileURLToPath(new URL('.', import.meta.url));
const PROJECT_ROOT = resolve(HERE, '..', '..');
const I18N_DIR = resolve(PROJECT_ROOT, 'src', 'i18n');

const T_DIRECTIVE = /\bt\(\s*['"]([^'"\\]+)['"]/g;
const TRANSLATE_PIPE = /['"]([^'"\\]+)['"]\s*\|\s*translate\b/g;
const TRANSLATE_SERVICE =
  /translocoService\s*\.\s*(?:translate|selectTranslate)\(\s*['"]([^'"\\]+)['"]/g;

function walkSource(dir: string, acc: string[] = []): string[] {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.name.startsWith('.') || entry.name === 'node_modules') continue;
    // Skip symlinks defensively — the sandbox layout has `node_modules`
    // and `.angular/cache` symlinked to Linux-local paths (CLAUDE.md
    // §9); a stray symlink loop into `src/` would recurse forever.
    if (entry.isSymbolicLink()) continue;
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      walkSource(full, acc);
    } else if (
      entry.isFile() &&
      (full.endsWith('.ts') || full.endsWith('.html')) &&
      !full.endsWith('.spec.ts') &&
      !full.includes(`${join('src', 'i18n')}${process.platform === 'win32' ? '\\' : '/'}`)
    ) {
      acc.push(full);
    }
  }
  return acc;
}

export function inventoryReferencedKeys(srcRoot: string): Set<string> {
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
  } catch (err) {
    // Fail loud rather than silently dropping a hand-edited locale
    // file with a syntax error — silent fallback would mass-rewrite
    // operator content under the orphan rule.
    console.error(`Failed to import existing ${locale}.ts; aborting before write.`);
    throw err;
  }
}

async function loadExistingBundles(): Promise<Record<LocaleCode, TranslationTree>> {
  const result: Record<LocaleCode, TranslationTree> = { de: {}, fr: {}, it: {}, en: {} };
  for (const locale of SUPPORTED_LOCALES) {
    result[locale] = await loadExistingBundle(locale);
  }
  return result;
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
    console.warn(
      `  HTML stripped — ${item.locale}.${item.key}: ${JSON.stringify(item.before)} → ${JSON.stringify(item.after)}`,
    );
  }
  if (result.keyCollisions.length > 0) {
    console.error('Key collisions; aborting before write:');
    for (const c of result.keyCollisions) {
      console.error(`  ${c.path} collides with existing ${c.conflict}`);
    }
    process.exit(1);
  }
  if (result.survivingHtml.length > 0) {
    console.error('HTML / entity content survived stripping; aborting before write:');
    for (const item of result.survivingHtml) {
      console.error(`  ${item.locale}.${item.key} = ${JSON.stringify(item.value)}`);
    }
    process.exit(1);
  }
  if (result.droppedAsOrphan.length > 0) {
    console.log(
      `Dropped ${result.droppedAsOrphan.length} orphan legacy keys (not referenced in src/):`,
    );
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

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  main().catch((err) => {
    console.error(err);
    process.exit(1);
  });
}
