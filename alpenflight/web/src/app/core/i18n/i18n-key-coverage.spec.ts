import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

import de from '../../../i18n/de';


const PROJECT_SRC = join(process.cwd(), 'src');

const T_DIRECTIVE = /\bt\(\s*['"]([^'"\\]+)['"]/g;
const TRANSLATE_PIPE = /['"]([^'"\\]+)['"]\s*\|\s*translate\b/g;
const TRANSLATE_SERVICE =
  /translocoService\s*\.\s*(?:translate|selectTranslate)\(\s*['"]([^'"\\]+)['"]/g;

interface Reference {
  file: string;
  key: string;
}

function flattenKeys(obj: object, prefix = ''): string[] {
  const out: string[] = [];
  for (const [k, v] of Object.entries(obj)) {
    const path = prefix ? `${prefix}.${k}` : k;
    if (v !== null && typeof v === 'object') {
      out.push(...flattenKeys(v, path));
    } else {
      out.push(path);
    }
  }
  return out;
}

function walk(dir: string, acc: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    if (entry.startsWith('.') || entry === 'node_modules') continue;
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      walk(full, acc);
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

function extractRefs(file: string): string[] {
  const text = readFileSync(file, 'utf-8');
  const keys = new Set<string>();
  for (const re of [T_DIRECTIVE, TRANSLATE_PIPE, TRANSLATE_SERVICE]) {
    re.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = re.exec(text)) !== null) keys.add(m[1] as string);
  }
  return [...keys];
}

describe('i18n key coverage', () => {
  const knownPaths = flattenKeys(de);
  const sourceFiles = walk(PROJECT_SRC);

  it('every referenced translation key exists in de.ts (or a suffix of it)', () => {
    const unknown: Reference[] = [];

    for (const file of sourceFiles) {
      for (const key of extractRefs(file)) {
        const matched = knownPaths.some((p) => p === key || p.endsWith('.' + key));
        if (!matched) {
          unknown.push({ file: relative(process.cwd(), file), key });
        }
      }
    }

    if (unknown.length > 0) {
      const detail = unknown.map((u) => `  ${u.file} — t('${u.key}')`).join('\n');
      throw new Error(`Translation keys referenced but not defined in de.ts:\n${detail}`);
    }
  });

  it('canonical de tree is non-empty (sanity)', () => {
    expect(knownPaths.length).toBeGreaterThan(0);
  });
});
