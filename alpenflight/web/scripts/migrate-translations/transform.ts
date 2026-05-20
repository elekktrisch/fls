import type { LegacyRow } from './parse-legacy';
import { LEGACY_LANG_ID_TO_LOCALE } from './parse-legacy';

export type LocaleCode = 'de' | 'fr' | 'it' | 'en';
export const SUPPORTED_LOCALES: readonly LocaleCode[] = ['de', 'fr', 'it', 'en'];
export const CANONICAL_LOCALE: LocaleCode = 'de';

export interface TranslationTree {
  [key: string]: string | TranslationTree;
}

export interface KeyCollision {
  readonly path: string;
  readonly conflict: string;
}

export interface MigrationResult {
  readonly bundles: Record<LocaleCode, TranslationTree>;
  readonly droppedAsOrphan: readonly string[];
  readonly htmlStripped: ReadonlyArray<{
    key: string;
    locale: LocaleCode;
    before: string;
    after: string;
  }>;
  readonly survivingHtml: ReadonlyArray<{ key: string; locale: LocaleCode; value: string }>;
  readonly keyCollisions: readonly KeyCollision[];
}

/**
 * Convert a legacy `ALL_CAPS_UNDERSCORE` key to flat-dotted-lowercase
 * (`aircraft.model`). Single-token keys land at the tree root
 * (`MASTERDATA` → `masterdata`).
 */
export function renameKey(legacyKey: string): string {
  return legacyKey.toLowerCase().split('_').filter(Boolean).join('.');
}

// Catches plain tags, HTML comments, CDATA, processing instructions,
// doctype declarations, and self-closing pseudo-tags. Anything starting
// with `<` followed by a letter, `/`, `!`, or `?` and continuing until
// the next `>` is removed.
const HTML_TAG_GLOBAL = /<[a-z!?/][^>]*>/gi;
// Same shape as HTML_TAG_GLOBAL but non-global so `.test()` is stateless.
const HTML_TAG_TEST = /<[a-z!?/][^>]*>/i;
const HTML_ENTITY_GLOBAL = /&(?:[a-z]+|#\d+|#x[0-9a-f]+);/gi;
const HTML_ENTITY_TEST = /&(?:[a-z]+|#\d+|#x[0-9a-f]+);/i;

const MAX_CODEPOINT = 0x10ffff;

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
  if (numeric) {
    const cp = Number.parseInt(numeric[1]!, 10);
    return cp <= MAX_CODEPOINT ? String.fromCodePoint(cp) : entity;
  }
  const hex = /^&#x([0-9a-f]+);$/i.exec(entity);
  if (hex) {
    const cp = Number.parseInt(hex[1]!, 16);
    return cp <= MAX_CODEPOINT ? String.fromCodePoint(cp) : entity;
  }
  return entity;
}

function stripHtmlOnce(value: string): string {
  return value.replace(HTML_TAG_GLOBAL, '').replace(HTML_ENTITY_GLOBAL, decodeEntity);
}

/**
 * Iteratively strip until a fixed point. Defends against partial-overlap
 * tag tricks (`<scr<b>ipt>` etc.) and lets the result of a decode pass
 * trigger a second tag-strip (`&lt;script&gt;` → `<script>` → ``).
 */
function stripHtml(value: string): string {
  let prev: string;
  let cur = value;
  do {
    prev = cur;
    cur = stripHtmlOnce(cur);
  } while (cur !== prev);
  return cur;
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

function trySetAtPath(
  tree: TranslationTree,
  path: string,
  value: string,
): KeyCollision | null {
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
      return { path, conflict: segments.slice(0, i + 1).join('.') };
    } else {
      node = next;
    }
  }
  const leaf = segments[segments.length - 1]!;
  if (typeof node[leaf] === 'object') {
    return { path, conflict: `${path} (existing nested tree)` };
  }
  node[leaf] = value;
  return null;
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
  const htmlStripped: Array<{
    key: string;
    locale: LocaleCode;
    before: string;
    after: string;
  }> = [];
  const survivingHtml: Array<{ key: string; locale: LocaleCode; value: string }> = [];
  const keyCollisions: KeyCollision[] = [];

  const skipOrphanFiltering = referencedKeys.size === 0;
  const existingPaths = flattenPaths(bundles[CANONICAL_LOCALE]);

  for (const row of rows) {
    const locale = LEGACY_LANG_ID_TO_LOCALE[row.languageId] as LocaleCode | undefined;
    if (!locale) continue;
    if (!row.value || row.value.length === 0) continue;

    const renamed = renameKey(row.key);

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
    if (HTML_TAG_TEST.test(value) || HTML_ENTITY_TEST.test(value)) {
      const before = value;
      value = stripHtml(value);
      htmlStripped.push({ key: renamed, locale, before, after: value });
    }

    const collision = trySetAtPath(bundles[locale], renamed, value);
    if (collision) {
      keyCollisions.push(collision);
      continue;
    }

    if (HTML_TAG_TEST.test(value) || HTML_ENTITY_TEST.test(value)) {
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
    keyCollisions,
  };
}

function mirrorMissingFromCanonical(
  canonical: TranslationTree,
  target: TranslationTree,
): void {
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
