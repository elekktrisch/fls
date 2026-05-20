import type { LegacyRow } from './parse-legacy';
import { LEGACY_LANG_ID_TO_LOCALE } from './parse-legacy';

export type LocaleCode = 'de' | 'fr' | 'it' | 'en';
export const SUPPORTED_LOCALES: readonly LocaleCode[] = ['de', 'fr', 'it', 'en'];
export const CANONICAL_LOCALE: LocaleCode = 'de';

export interface TranslationTree {
  [key: string]: string | TranslationTree;
}

export interface MigrationResult {
  readonly bundles: Record<LocaleCode, TranslationTree>;
  readonly droppedAsOrphan: readonly string[];
  readonly htmlStripped: ReadonlyArray<{ key: string; locale: LocaleCode; before: string; after: string }>;
  readonly survivingHtml: ReadonlyArray<{ key: string; locale: LocaleCode; value: string }>;
}

/**
 * Convert a legacy `ALL_CAPS_UNDERSCORE` key to flat-dotted-lowercase
 * (`aircraft.model`). Single-token keys land at the tree root
 * (`MASTERDATA` → `masterdata`).
 */
export function renameKey(legacyKey: string): string {
  return legacyKey.toLowerCase().split('_').filter(Boolean).join('.');
}

const HTML_TAG = /<[a-z][^>]*>|<\/[a-z][^>]*>/i;
const HTML_TAG_GLOBAL = /<[a-z][^>]*>|<\/[a-z][^>]*>/gi;
const HTML_ENTITY = /&(?:[a-z]+|#\d+|#x[0-9a-f]+);/gi;

// Named-entity table covering the accented characters that appear in
// the legacy translation seed (DE / FR / IT / EN). The full HTML5 entity
// set isn't needed — the legacy DB is tiny + manually authored. Unknown
// entities decode to themselves so a future operator notices.
const NAMED_ENTITIES: Record<string, string> = {
  '&amp;': '&',
  '&lt;': '<',
  '&gt;': '>',
  '&quot;': '"',
  '&apos;': "'",
  '&nbsp;': ' ',
  '&auml;': 'ä',
  '&ouml;': 'ö',
  '&uuml;': 'ü',
  '&Auml;': 'Ä',
  '&Ouml;': 'Ö',
  '&Uuml;': 'Ü',
  '&szlig;': 'ß',
  '&aacute;': 'á',
  '&Aacute;': 'Á',
  '&agrave;': 'à',
  '&Agrave;': 'À',
  '&eacute;': 'é',
  '&Eacute;': 'É',
  '&egrave;': 'è',
  '&Egrave;': 'È',
  '&iacute;': 'í',
  '&Iacute;': 'Í',
  '&oacute;': 'ó',
  '&Oacute;': 'Ó',
  '&ccedil;': 'ç',
  '&Ccedil;': 'Ç',
  '&euro;': '€',
};

function decodeEntity(entity: string): string {
  if (entity in NAMED_ENTITIES) return NAMED_ENTITIES[entity]!;
  const numeric = /^&#(\d+);$/.exec(entity);
  if (numeric) return String.fromCodePoint(Number.parseInt(numeric[1]!, 10));
  const hex = /^&#x([0-9a-f]+);$/i.exec(entity);
  if (hex) return String.fromCodePoint(Number.parseInt(hex[1]!, 16));
  return entity;
}

function stripHtml(value: string): string {
  return value.replace(HTML_TAG_GLOBAL, '').replace(HTML_ENTITY, decodeEntity);
}

function flattenPaths(tree: TranslationTree | undefined, prefix = ''): string[] {
  if (!tree) return [];
  const out: string[] = [];
  for (const [k, v] of Object.entries(tree)) {
    const path = prefix ? `${prefix}.${k}` : k;
    if (typeof v === 'object') {
      out.push(...flattenPaths(v, path));
    } else {
      out.push(path);
    }
  }
  return out;
}

function cloneTree(source?: TranslationTree): TranslationTree {
  if (!source) return {};
  const out: TranslationTree = {};
  for (const [k, v] of Object.entries(source)) {
    out[k] = typeof v === 'string' ? v : cloneTree(v);
  }
  return out;
}

function setAtPath(tree: TranslationTree, path: string, value: string): void {
  const segments = path.split('.');
  let node: TranslationTree = tree;
  for (let i = 0; i < segments.length - 1; i++) {
    const seg = segments[i]!;
    const next = node[seg];
    if (next === undefined) {
      const fresh: TranslationTree = {};
      node[seg] = fresh;
      node = fresh;
    } else if (typeof next === 'string') {
      throw new Error(
        `Key collision: '${path}' tries to nest under '${segments.slice(0, i + 1).join('.')}', which is already a string value. Likely a legacy-key naming conflict.`,
      );
    } else {
      node = next;
    }
  }
  const leaf = segments[segments.length - 1]!;
  if (typeof node[leaf] === 'object') {
    throw new Error(
      `Key collision: '${path}' tries to set a string where a nested tree already exists.`,
    );
  }
  node[leaf] = value;
}

/**
 * Build the four locale trees from legacy rows + the source-code key
 * inventory (the list of keys the SPA actually references — used to drop
 * orphans). Existing bundle content is preserved; legacy values
 * overwrite where they have the same key. Re-running the migration
 * doesn't wipe screen-author-supplied translations that aren't in the
 * legacy DB.
 *
 * `referencedKeys` is the set of *renamed* paths the source references
 * — i.e. after `renameKey()`. Pass an empty set to skip orphan
 * filtering (useful for tests; production callers always pass the real
 * inventory).
 */
export function mapLegacyToBundles(
  rows: readonly LegacyRow[],
  referencedKeys: ReadonlySet<string>,
  existingBundles?: Partial<Record<LocaleCode, TranslationTree>>,
): MigrationResult {
  const bundles: Record<LocaleCode, TranslationTree> = {
    de: cloneTree(existingBundles?.de),
    fr: cloneTree(existingBundles?.fr),
    it: cloneTree(existingBundles?.it),
    en: cloneTree(existingBundles?.en),
  };
  const droppedAsOrphan: string[] = [];
  const htmlStripped: Array<{ key: string; locale: LocaleCode; before: string; after: string }> = [];
  const survivingHtml: Array<{ key: string; locale: LocaleCode; value: string }> = [];

  const skipOrphanFiltering = referencedKeys.size === 0;
  const seenKeys = new Set<string>();
  const existingPaths = flattenPaths(existingBundles?.[CANONICAL_LOCALE]);

  for (const row of rows) {
    const locale = LEGACY_LANG_ID_TO_LOCALE[row.languageId] as LocaleCode | undefined;
    if (!locale) continue;
    if (!row.value || row.value.length === 0) continue;

    const renamed = renameKey(row.key);
    seenKeys.add(renamed);

    if (!skipOrphanFiltering) {
      // A legacy key is REDUNDANT if its renamed path is already a path-
      // suffix of an existing canonical-bundle path — the source's
      // `t('foo')` reference is satisfied by existing content under any
      // `<scope>.foo` path (same logic as `i18n-key-coverage.spec.ts`).
      const redundant = existingPaths.some(
        (p) => p === renamed || p.endsWith('.' + renamed),
      );
      const referenced = referencedKeys.has(renamed);
      if (redundant || !referenced) {
        droppedAsOrphan.push(renamed);
        continue;
      }
    }

    let value = row.value;
    if (HTML_TAG.test(value) || HTML_ENTITY.test(value)) {
      const before = value;
      value = stripHtml(value);
      htmlStripped.push({ key: renamed, locale, before, after: value });
    }

    setAtPath(bundles[locale], renamed, value);

    if (HTML_TAG.test(value)) {
      survivingHtml.push({ key: renamed, locale, value });
    }
  }

  // German fallback: every key in `de` that isn't translated in `fr` /
  // `it` / `en` gets the German string verbatim. The `Translations`
  // type forces compile-time parity; the German fallback satisfies it
  // until a translator replaces the value.
  for (const locale of SUPPORTED_LOCALES) {
    if (locale === CANONICAL_LOCALE) continue;
    mirrorMissingFromCanonical(bundles[CANONICAL_LOCALE], bundles[locale]);
  }

  return {
    bundles,
    droppedAsOrphan: [...new Set(droppedAsOrphan)].sort(),
    htmlStripped,
    survivingHtml,
  };
}

function mirrorMissingFromCanonical(canonical: TranslationTree, target: TranslationTree): void {
  for (const [key, value] of Object.entries(canonical)) {
    if (typeof value === 'string') {
      if (target[key] === undefined) {
        target[key] = value;
      }
    } else {
      const existing = target[key];
      if (existing === undefined) {
        const fresh: TranslationTree = {};
        target[key] = fresh;
        mirrorMissingFromCanonical(value, fresh);
      } else if (typeof existing === 'object') {
        mirrorMissingFromCanonical(value, existing);
      }
    }
  }
}
