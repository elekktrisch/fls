#!/usr/bin/env node
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const thisScriptDir = dirname(fileURLToPath(import.meta.url));

export const ALPENFLIGHT_E2E_TREE = resolve(thisScriptDir, '../e2e');
export const LEGACY_E2E_TREE = resolve(thisScriptDir, '../../../e2e');

export const DEFAULT_SCAN_ROOTS = [ALPENFLIGHT_E2E_TREE, LEGACY_E2E_TREE];

export const GUARDED_DATE_FIELDS = ['flightDate', 'startDateTime', 'ldgDateTime'];

const ABSOLUTE_DATE_SEED_IN_EITHER_CAMEL_OR_LEGACY_PASCAL_CASE = new RegExp(
  `\\b(${GUARDED_DATE_FIELDS.join('|')})\\s*:\\s*(['"])(\\d{4}-\\d{2}-\\d{2}[^'"]*)\\2`,
  'gi',
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

const API_CALL_THAT_CAN_CARRY_A_SEED_BODY = /\.(post|fetch)\s*\(/g;

export const HTTP_METHOD_ABSENT = 'ABSENT';
export const HTTP_METHOD_NOT_A_STRING_LITERAL = 'NOT_A_STRING_LITERAL';

function offsetOfMatchingCloseParen(masked, openParen) {
  let depth = 0;
  for (let i = openParen; i < masked.length; i += 1) {
    if (masked[i] === '(') depth += 1;
    else if (masked[i] === ')') {
      depth -= 1;
      if (depth === 0) return i;
    }
  }
  return -1;
}

export function httpMethodDeclaredInsideArguments(source, start, end) {
  const masked = blankOutStringsAndCommentsKeepingOffsets(source);
  const methodKey = /\bmethod\b/g;
  const arguments_ = masked.slice(start, end);
  const hit = methodKey.exec(arguments_);
  if (hit === null) return HTTP_METHOD_ABSENT;

  let cursor = start + hit.index + 'method'.length;

  while (cursor < end && /\s/.test(masked[cursor])) cursor += 1;
  if (masked[cursor] !== ':') return HTTP_METHOD_NOT_A_STRING_LITERAL;
  cursor += 1;
  while (cursor < end && /\s/.test(source[cursor])) cursor += 1;

  const quote = source[cursor];
  if (quote !== "'" && quote !== '"' && quote !== '`') return HTTP_METHOD_NOT_A_STRING_LITERAL;
  const closingQuote = source.indexOf(quote, cursor + 1);
  if (closingQuote === -1 || closingQuote >= end) return HTTP_METHOD_NOT_A_STRING_LITERAL;
  return source.slice(cursor + 1, closingQuote).toUpperCase();
}

export function findApiPostArgumentSpans(source) {
  const masked = blankOutStringsAndCommentsKeepingOffsets(source);
  const spans = [];
  API_CALL_THAT_CAN_CARRY_A_SEED_BODY.lastIndex = 0;
  let match;
  while ((match = API_CALL_THAT_CAN_CARRY_A_SEED_BODY.exec(masked)) !== null) {
    const openParen = match.index + match[0].length - 1;
    const closeParen = offsetOfMatchingCloseParen(masked, openParen);
    if (closeParen === -1) continue;

    if (match[1] === 'fetch') {
      const declared = httpMethodDeclaredInsideArguments(source, openParen, closeParen);
      const couldBeAPost = declared === 'POST' || declared === HTTP_METHOD_NOT_A_STRING_LITERAL;
      if (!couldBeAPost) continue;
    }

    spans.push({ start: openParen, end: closeParen });
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
  ABSOLUTE_DATE_SEED_IN_EITHER_CAMEL_OR_LEGACY_PASCAL_CASE.lastIndex = 0;
  let match;
  while ((match = ABSOLUTE_DATE_SEED_IN_EITHER_CAMEL_OR_LEGACY_PASCAL_CASE.exec(source)) !== null) {
    const offset = match.index;
    if (!spans.some((span) => offset > span.start && offset < span.end)) continue;
    findings.push({ ...lineAndColumnOf(source, offset), field: match[1], value: match[3] });
  }
  return findings;
}

const DIRECTORIES_HOLDING_NO_SEEDING_SOURCE = new Set([
  'node_modules',
  'node_modules_sandbox',
  'node_modules.windows',
  '.git',
  'test-results',
  'playwright-report',
  'screenshots',
]);

export function typeScriptFilesUnder(root) {
  const found = [];
  const walk = (dir) => {
    for (const entry of readdirSync(dir)) {
      if (DIRECTORIES_HOLDING_NO_SEEDING_SOURCE.has(entry)) continue;
      const full = join(dir, entry);
      if (statSync(full).isDirectory()) walk(full);
      else if (entry.endsWith('.ts') && !entry.endsWith('.d.ts')) found.push(full);
    }
  };
  walk(root);
  return found.sort();
}

export function scanTypeScriptTree(root) {
  const violations = [];
  for (const file of typeScriptFilesUnder(root)) {
    for (const finding of findAbsoluteDateSeedsInApiPosts(readFileSync(file, 'utf8'))) {
      violations.push({ file, ...finding });
    }
  }
  return violations;
}

export function scanEveryGuardedTree(roots = DEFAULT_SCAN_ROOTS) {
  const missing = roots.filter((root) => !existsSync(root));
  if (missing.length > 0) {
    throw new Error(
      `absolute-flight-date guard: a scanned e2e tree moved or was renamed, so the guard no longer covers its own inputs: ${missing.join(', ')}`,
    );
  }
  return roots.flatMap((root) => scanTypeScriptTree(root));
}

export const RULE_EXPLANATION = [
  'No e2e source file may seed an absolute date through an API POST.',
  'The server applies a default 90-day window to flights.list(), so an absolute',
  'seed date silently leaves that window on a future run date. The spec then reds',
  'on an unchanged sha, in a scheduled lane that nobody watches (J-19 MAIN-1).',
  'The guard reads every .ts file under both e2e trees, spec and helper alike, and',
  "treats .post(...) and .fetch(..., { method: 'POST' }) as seeding call sites.",
  'Fix: derive the date from the run date with',
  "seededFlightDateInsideListWindowAndPastBillGate() from 'tests/real-idp/_helpers/seed-flight-date',",
  'then build startDateTime / ldgDateTime from it with a template literal.',
].join('\n  ');

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  const roots =
    process.argv.length > 2 ? process.argv.slice(2).map((a) => resolve(a)) : DEFAULT_SCAN_ROOTS;
  const violations = scanEveryGuardedTree(roots);
  for (const root of roots) {
    console.log(
      `absolute-flight-date guard: scanned ${typeScriptFilesUnder(root).length} .ts file(s) under ${relative(process.cwd(), root) || root}`,
    );
  }
  if (violations.length === 0) {
    console.log('absolute-flight-date guard: no absolute API-seeded date, and nothing is exempt');
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
