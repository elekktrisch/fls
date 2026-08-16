#!/usr/bin/env node
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const thisScriptDir = dirname(fileURLToPath(import.meta.url));

export const DEFAULT_SCAN_ROOT = resolve(thisScriptDir, '../e2e/tests');

export const GUARDED_DATE_FIELDS = ['flightDate', 'startDateTime', 'ldgDateTime'];

const ABSOLUTE_DATE_SEED = new RegExp(
  `\\b(${GUARDED_DATE_FIELDS.join('|')})\\s*:\\s*(['"])(\\d{4}-\\d{2}-\\d{2}[^'"]*)\\2`,
  'g',
);

const CHARS_A_REGEX_LITERAL_MAY_FOLLOW = new Set([
  '(',
  ',',
  '=',
  ':',
  '[',
  '!',
  '&',
  '|',
  '?',
  '{',
  '}',
  ';',
  '+',
  '-',
  '*',
  '%',
  '<',
  '>',
  '~',
  '^',
  '\n',
]);

export function blankOutStringsAndCommentsKeepingOffsets(source) {
  const out = source.split('');
  let index = 0;
  let lastSignificantChar = '\n';

  const blank = (from, to) => {
    for (let i = from; i < to && i < out.length; i += 1) {
      if (out[i] !== '\n') out[i] = ' ';
    }
  };

  while (index < source.length) {
    const char = source[index];
    const next = source[index + 1];

    if (char === '/' && next === '/') {
      const end = source.indexOf('\n', index);
      const stop = end === -1 ? source.length : end;
      blank(index, stop);
      index = stop;
      continue;
    }

    if (char === '/' && next === '*') {
      const end = source.indexOf('*/', index + 2);
      const stop = end === -1 ? source.length : end + 2;
      blank(index, stop);
      index = stop;
      continue;
    }

    if (char === "'" || char === '"' || char === '`') {
      let cursor = index + 1;
      while (cursor < source.length) {
        if (source[cursor] === '\\') {
          cursor += 2;
          continue;
        }
        if (source[cursor] === char) break;
        cursor += 1;
      }
      blank(index, Math.min(cursor + 1, source.length));
      index = cursor + 1;
      lastSignificantChar = char;
      continue;
    }

    if (char === '/' && CHARS_A_REGEX_LITERAL_MAY_FOLLOW.has(lastSignificantChar)) {
      let cursor = index + 1;
      let insideCharacterClass = false;
      let closed = false;
      while (cursor < source.length && source[cursor] !== '\n') {
        const c = source[cursor];
        if (c === '\\') {
          cursor += 2;
          continue;
        }
        if (c === '[') insideCharacterClass = true;
        else if (c === ']') insideCharacterClass = false;
        else if (c === '/' && !insideCharacterClass) {
          closed = true;
          break;
        }
        cursor += 1;
      }
      if (closed) {
        blank(index, cursor + 1);
        index = cursor + 1;
        lastSignificantChar = '/';
        continue;
      }
    }

    if (!/\s/.test(char)) lastSignificantChar = char;
    else if (char === '\n') lastSignificantChar = '\n';
    index += 1;
  }

  return out.join('');
}

export function findApiPostArgumentSpans(source) {
  const masked = blankOutStringsAndCommentsKeepingOffsets(source);
  const spans = [];
  const postCall = /\.post\s*\(/g;
  let match;
  while ((match = postCall.exec(masked)) !== null) {
    const openParen = match.index + match[0].length - 1;
    let depth = 0;
    for (let i = openParen; i < masked.length; i += 1) {
      if (masked[i] === '(') depth += 1;
      else if (masked[i] === ')') {
        depth -= 1;
        if (depth === 0) {
          spans.push({ start: openParen, end: i });
          break;
        }
      }
    }
  }
  return spans;
}

function lineAndColumnOf(source, offset) {
  const before = source.slice(0, offset);
  const line = before.split('\n').length;
  const column = offset - (before.lastIndexOf('\n') + 1) + 1;
  return { line, column };
}

export function findAbsoluteDateSeedsInApiPosts(source) {
  const spans = findApiPostArgumentSpans(source);
  const findings = [];
  ABSOLUTE_DATE_SEED.lastIndex = 0;
  let match;
  while ((match = ABSOLUTE_DATE_SEED.exec(source)) !== null) {
    const offset = match.index;
    if (!spans.some((span) => offset > span.start && offset < span.end)) continue;
    findings.push({ ...lineAndColumnOf(source, offset), field: match[1], value: match[3] });
  }
  return findings;
}

function specFilesUnder(root) {
  const found = [];
  const walk = (dir) => {
    for (const entry of readdirSync(dir)) {
      const full = join(dir, entry);
      if (statSync(full).isDirectory()) walk(full);
      else if (entry.endsWith('.spec.ts')) found.push(full);
    }
  };
  walk(root);
  return found.sort();
}

export function scanSpecTree(root = DEFAULT_SCAN_ROOT) {
  const violations = [];
  for (const file of specFilesUnder(root)) {
    for (const finding of findAbsoluteDateSeedsInApiPosts(readFileSync(file, 'utf8'))) {
      violations.push({ file, ...finding });
    }
  }
  return violations;
}

export const RULE_EXPLANATION = [
  'An e2e spec must not seed an absolute date through an API POST.',
  'The server applies a default 90-day window to flights.list(), so an absolute',
  'seed date silently leaves that window on a future run date. The spec then reds',
  'on an unchanged sha, in a scheduled lane that nobody watches (J-19 MAIN-1).',
  'Fix: derive the date from the run date with',
  "seededFlightDateInsideListWindowAndPastBillGate() from 'tests/real-idp/_helpers/seed-flight-date',",
  'then build startDateTime / ldgDateTime from it with a template literal.',
].join('\n  ');

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  const root = process.argv[2] ? resolve(process.argv[2]) : DEFAULT_SCAN_ROOT;
  const violations = scanSpecTree(root);
  if (violations.length === 0) {
    console.log(`absolute-flight-date guard: no absolute API-seeded date under ${root}`);
  } else {
    console.error(`absolute-flight-date guard: ${violations.length} violation(s)\n`);
    for (const v of violations) {
      console.error(`  ${relative(process.cwd(), v.file)}:${v.line}:${v.column}`);
      console.error(`    ${v.field}: '${v.value}'`);
    }
    console.error(`\n  ${RULE_EXPLANATION}`);
    process.exit(1);
  }
}
