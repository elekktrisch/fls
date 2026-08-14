#!/usr/bin/env node
import { readFileSync, writeFileSync, mkdirSync, readdirSync, statSync } from 'node:fs';
import { extname, join, relative, resolve, basename } from 'node:path';

export const LANGUAGE_BY_EXTENSION = new Map([
  ['.java', 'clike'],
  ['.kt', 'clike'],
  ['.kts', 'clike'],
  ['.ts', 'clike'],
  ['.mts', 'clike'],
  ['.js', 'clike'],
  ['.mjs', 'clike'],
  ['.cjs', 'clike'],
  ['.css', 'clike'],
  ['.sql', 'sql'],
  ['.sh', 'shell'],
  ['.bash', 'shell'],
  ['.yml', 'yaml'],
  ['.yaml', 'yaml'],
  ['.html', 'html'],
]);

const EXTENSIONS_WITH_REGEX_LITERALS = new Set(['.ts', '.mts', '.js', '.mjs', '.cjs']);
const EXTENSIONS_WITH_TEXT_BLOCKS = new Set(['.java', '.kt', '.kts']);
const EXTENSIONS_WITH_TEMPLATE_LITERALS = new Set(['.ts', '.mts', '.js', '.mjs', '.cjs', '.kt', '.kts']);
const EXTENSIONS_WITHOUT_LINE_COMMENTS = new Set(['.css']);

const EXCLUDED_DIRECTORY_NAMES = new Set([
  'node_modules', 'build', 'target', '.gradle', '.git', 'dist', '.angular',
  'coverage', 'test-results', 'playwright-report', 'flsserver', 'flsweb',
  '.comment-strip', 'generated',
]);

const EXCLUDED_FILE_NAMES = new Set(['gradlew', 'gradlew.bat']);

const KEYWORDS_ALLOWING_A_FOLLOWING_REGEX_LITERAL = new Set([
  'return', 'typeof', 'instanceof', 'in', 'of', 'case', 'do', 'else',
  'yield', 'await', 'new', 'delete', 'void', 'throw',
]);

const TOOL_DIRECTIVE_PATTERNS = [
  /^\s*eslint-(disable|enable)/,
  /^\s*@ts-(ignore|expect-error|nocheck)\b/,
  /^\s*prettier-ignore/,
  /^\s*noinspection\b/,
  /language\s*=/,
  /^\s*@formatter:(off|on)/,
  /^\s*ext:/,
];

const RENAME_MARKER_PATTERN = /^\s*RENAME:/;

const HIGH_INFORMATION_VOCABULARY =
  /\b(because|why|not|never|must|always|workaround|gotcha|differs?|defaults? to|beware|careful|caveat|otherwise|deliberate(ly)?|intentional(ly)?|do not|don't|hack|subtle|surprising|assumes?)\b/i;

const EXTERNAL_FACT_PATTERN =
  /(https?:\/\/|RFC ?\d|chromium|chrome|firefox|safari|keycloak|postgres|flyway|spring|orval|playwright|legacy|proffix|\bv?\d+\.\d+|\b[SJT]-\d+\b)/i;

const GENERIC_IDENTIFIER_PATTERN =
  /\b(raw|ctx|data|tmp|temp|val|value|item|items|obj|result|res|req|handle|process|manager|helper|util|utils|info|state|flag|arg|args|param|params|list|map|set|str|num|idx|i|j|k|x|y|p|f|e)\b/;

const BARE_LITERAL_PATTERN = /(?<![\w."'])(\d{2,}|0x[0-9a-f]+|'[^']{1,40}'|"[^"]{1,40}")/i;

export const HIGH_SCORE_THRESHOLD = 8;

function consumeQuotedRun(source, startIndex, quoteCharacter, stopAtNewline) {
  let index = startIndex + 1;
  while (index < source.length) {
    const character = source[index];
    if (character === '\\') {
      index += 2;
      continue;
    }
    if (character === quoteCharacter) return index + 1;
    if (stopAtNewline && character === '\n') return index;
    index += 1;
  }
  return source.length;
}

function consumeRegexLiteral(source, startIndex) {
  let index = startIndex + 1;
  let insideCharacterClass = false;
  while (index < source.length) {
    const character = source[index];
    if (character === '\\') {
      index += 2;
      continue;
    }
    if (character === '\n') return startIndex;
    if (character === '[') insideCharacterClass = true;
    else if (character === ']') insideCharacterClass = false;
    else if (character === '/' && !insideCharacterClass) {
      index += 1;
      while (index < source.length && /[a-z]/i.test(source[index])) index += 1;
      return index;
    }
    index += 1;
  }
  return startIndex;
}

function consumeTemplateLiteral(source, startIndex) {
  let index = startIndex + 1;
  while (index < source.length) {
    const character = source[index];
    if (character === '\\') {
      index += 2;
      continue;
    }
    if (character === '`') return index + 1;
    if (character === '$' && source[index + 1] === '{') {
      index = consumeInterpolationBlock(source, index + 2);
      continue;
    }
    index += 1;
  }
  return source.length;
}

function consumeInterpolationBlock(source, startIndex) {
  let index = startIndex;
  let braceDepth = 1;
  while (index < source.length && braceDepth > 0) {
    const character = source[index];
    if (character === '{') braceDepth += 1;
    else if (character === '}') braceDepth -= 1;
    else if (character === '`') {
      index = consumeTemplateLiteral(source, index);
      continue;
    } else if (character === '"' || character === "'") {
      index = consumeQuotedRun(source, index, character, true);
      continue;
    }
    index += 1;
  }
  return index;
}

function regexLiteralCanStartAfter(lastCodeCharacter, lastWord) {
  if (lastCodeCharacter === '') return true;
  if (KEYWORDS_ALLOWING_A_FOLLOWING_REGEX_LITERAL.has(lastWord)) return true;
  return !/[A-Za-z0-9_$)\]}]/.test(lastCodeCharacter);
}

function scanCLike(source, extension) {
  const comments = [];
  const literals = [];
  const lineCommentsAllowed = !EXTENSIONS_WITHOUT_LINE_COMMENTS.has(extension);
  const regexLiteralsAllowed = EXTENSIONS_WITH_REGEX_LITERALS.has(extension);
  const textBlocksAllowed = EXTENSIONS_WITH_TEXT_BLOCKS.has(extension);
  const templateLiteralsAllowed = EXTENSIONS_WITH_TEMPLATE_LITERALS.has(extension);
  let index = 0;
  let lastCodeCharacter = '';
  let lastWord = '';
  while (index < source.length) {
    const character = source[index];
    const nextCharacter = source[index + 1];
    if (lineCommentsAllowed && character === '/' && nextCharacter === '/') {
      let end = source.indexOf('\n', index);
      if (end === -1) end = source.length;
      comments.push({ start: index, end, body: source.slice(index + 2, end) });
      index = end;
      continue;
    }
    if (character === '/' && nextCharacter === '*') {
      const closing = source.indexOf('*/', index + 2);
      const end = closing === -1 ? source.length : closing + 2;
      comments.push({ start: index, end, body: source.slice(index + 2, Math.max(index + 2, end - 2)) });
      index = end;
      continue;
    }
    if (textBlocksAllowed && source.startsWith('"""', index)) {
      const closing = source.indexOf('"""', index + 3);
      const end = closing === -1 ? source.length : closing + 3;
      literals.push(source.slice(index, end));
      index = end;
      lastCodeCharacter = '"';
      continue;
    }
    if (character === '"' || character === "'") {
      const end = consumeQuotedRun(source, index, character, true);
      literals.push(source.slice(index, end));
      index = end;
      lastCodeCharacter = character;
      continue;
    }
    if (templateLiteralsAllowed && character === '`') {
      const end = consumeTemplateLiteral(source, index);
      literals.push(source.slice(index, end));
      index = end;
      lastCodeCharacter = '`';
      continue;
    }
    if (regexLiteralsAllowed && character === '/' && regexLiteralCanStartAfter(lastCodeCharacter, lastWord)) {
      const end = consumeRegexLiteral(source, index);
      if (end > index) {
        literals.push(source.slice(index, end));
        index = end;
        lastCodeCharacter = '/';
        lastWord = '';
        continue;
      }
    }
    if (/[A-Za-z_$]/.test(character)) {
      let end = index;
      while (end < source.length && /[A-Za-z0-9_$]/.test(source[end])) end += 1;
      lastWord = source.slice(index, end);
      lastCodeCharacter = source[end - 1];
      index = end;
      continue;
    }
    if (!/\s/.test(character)) {
      lastCodeCharacter = character;
      lastWord = '';
    }
    index += 1;
  }
  return { comments, literals };
}

function scanSql(source) {
  const comments = [];
  const literals = [];
  let index = 0;
  while (index < source.length) {
    const character = source[index];
    if (character === '-' && source[index + 1] === '-') {
      let end = source.indexOf('\n', index);
      if (end === -1) end = source.length;
      comments.push({ start: index, end, body: source.slice(index + 2, end) });
      index = end;
      continue;
    }
    if (character === '/' && source[index + 1] === '*') {
      let depth = 1;
      let cursor = index + 2;
      while (cursor < source.length && depth > 0) {
        if (source.startsWith('/*', cursor)) {
          depth += 1;
          cursor += 2;
        } else if (source.startsWith('*/', cursor)) {
          depth -= 1;
          cursor += 2;
        } else cursor += 1;
      }
      comments.push({ start: index, end: cursor, body: source.slice(index + 2, Math.max(index + 2, cursor - 2)) });
      index = cursor;
      continue;
    }
    if (character === "'" || character === '"') {
      let cursor = index + 1;
      while (cursor < source.length) {
        if (source[cursor] === '\\' && character === "'") {
          cursor += 2;
          continue;
        }
        if (source[cursor] === character) {
          if (source[cursor + 1] === character) {
            cursor += 2;
            continue;
          }
          cursor += 1;
          break;
        }
        cursor += 1;
      }
      literals.push(source.slice(index, cursor));
      index = cursor;
      continue;
    }
    if (character === '$') {
      const tagMatch = /^\$[A-Za-z_0-9]*\$/.exec(source.slice(index));
      if (tagMatch) {
        const tag = tagMatch[0];
        const closing = source.indexOf(tag, index + tag.length);
        const end = closing === -1 ? source.length : closing + tag.length;
        literals.push(source.slice(index, end));
        index = end;
        continue;
      }
    }
    index += 1;
  }
  return { comments, literals };
}

function scanShell(source) {
  const comments = [];
  const literals = [];
  let index = 0;
  let previousCharacter = '';
  const pendingHeredocDelimiters = [];
  while (index < source.length) {
    const character = source[index];
    if (character === '\n' && pendingHeredocDelimiters.length > 0) {
      let cursor = index + 1;
      while (pendingHeredocDelimiters.length > 0 && cursor < source.length) {
        const { delimiter, stripsIndentation } = pendingHeredocDelimiters[0];
        let lineEnd = source.indexOf('\n', cursor);
        if (lineEnd === -1) lineEnd = source.length;
        const line = source.slice(cursor, lineEnd);
        const compared = stripsIndentation ? line.replace(/^\t+/, '') : line;
        cursor = lineEnd + 1;
        if (compared.trimEnd() === delimiter) pendingHeredocDelimiters.shift();
      }
      literals.push(source.slice(index, Math.min(cursor, source.length)));
      index = Math.min(cursor, source.length);
      previousCharacter = '\n';
      continue;
    }
    if (character === '<' && source[index + 1] === '<' && source[index + 2] !== '<') {
      const heredocMatch = /^<<(-?)\s*(['"]?)([A-Za-z_][A-Za-z0-9_]*)\2/.exec(source.slice(index));
      if (heredocMatch) {
        pendingHeredocDelimiters.push({ delimiter: heredocMatch[3], stripsIndentation: heredocMatch[1] === '-' });
        index += heredocMatch[0].length;
        previousCharacter = 'x';
        continue;
      }
    }
    if (character === "'") {
      const closing = source.indexOf("'", index + 1);
      const end = closing === -1 ? source.length : closing + 1;
      literals.push(source.slice(index, end));
      index = end;
      previousCharacter = "'";
      continue;
    }
    if (character === '"') {
      const end = consumeQuotedRun(source, index, '"', false);
      literals.push(source.slice(index, end));
      index = end;
      previousCharacter = '"';
      continue;
    }
    if (character === '#' && (index === 0 || /[\s;&|(]/.test(previousCharacter))) {
      let end = source.indexOf('\n', index);
      if (end === -1) end = source.length;
      comments.push({ start: index, end, body: source.slice(index + 1, end) });
      index = end;
      continue;
    }
    previousCharacter = character;
    index += 1;
  }
  return { comments, literals };
}

function scanYaml(source) {
  const comments = [];
  const literals = [];
  const lines = source.split('\n');
  let offset = 0;
  let blockScalarParentIndent = null;
  for (const line of lines) {
    const indent = line.length - line.trimStart().length;
    if (blockScalarParentIndent !== null) {
      if (line.trim() === '' || indent > blockScalarParentIndent) {
        literals.push(line);
        offset += line.length + 1;
        continue;
      }
      blockScalarParentIndent = null;
    }
    let index = 0;
    let commentStart = -1;
    while (index < line.length) {
      const character = line[index];
      if (character === "'" || character === '"') {
        const end = consumeQuotedRun(line, index, character, true);
        literals.push(line.slice(index, end));
        index = end;
        continue;
      }
      if (character === '#' && (index === 0 || /\s/.test(line[index - 1]))) {
        commentStart = index;
        break;
      }
      index += 1;
    }
    if (commentStart !== -1) {
      comments.push({
        start: offset + commentStart,
        end: offset + line.length,
        body: line.slice(commentStart + 1),
      });
    }
    const codePortion = commentStart === -1 ? line : line.slice(0, commentStart);
    if (/(\||>)[+-]?\d*\s*$/.test(codePortion.trimEnd())) blockScalarParentIndent = indent;
    offset += line.length + 1;
  }
  return { comments, literals };
}

function scanHtml(source) {
  const comments = [];
  let index = 0;
  while (index < source.length) {
    const opening = source.indexOf('<!--', index);
    if (opening === -1) break;
    const closing = source.indexOf('-->', opening + 4);
    const end = closing === -1 ? source.length : closing + 3;
    comments.push({ start: opening, end, body: source.slice(opening + 4, Math.max(opening + 4, end - 3)) });
    index = end;
  }
  return { comments, literals: [] };
}

export function scanComments(source, extension) {
  const language = LANGUAGE_BY_EXTENSION.get(extension);
  if (language === 'clike') return scanCLike(source, extension);
  if (language === 'sql') return scanSql(source);
  if (language === 'shell') return scanShell(source);
  if (language === 'yaml') return scanYaml(source);
  if (language === 'html') return scanHtml(source);
  return { comments: [], literals: [] };
}

export function classifyComment(source, comment) {
  const isShebang = comment.start === 0 && source.startsWith('#!');
  if (isShebang) return 'directive';
  if (RENAME_MARKER_PATTERN.test(comment.body)) return 'rename-marker';
  if (TOOL_DIRECTIVE_PATTERNS.some((pattern) => pattern.test(comment.body))) return 'directive';
  return 'prose';
}

function lineNumberAt(source, index) {
  let line = 1;
  for (let cursor = 0; cursor < index; cursor += 1) if (source[cursor] === '\n') line += 1;
  return line;
}

function attachedDeclarationAfter(source, endIndex) {
  const remainder = source.slice(endIndex, endIndex + 800).split('\n');
  for (const line of remainder) {
    const trimmed = line.trim();
    if (trimmed !== '' && !/^(\/\/|\/\*|\*|--|#|<!--)/.test(trimmed)) return trimmed.slice(0, 160);
  }
  return '';
}

export function scoreComment(entry) {
  let score = Math.min(entry.text.length / 40, 6);
  if (HIGH_INFORMATION_VOCABULARY.test(entry.text)) score += 4;
  if (EXTERNAL_FACT_PATTERN.test(entry.text)) score += 3;
  if (GENERIC_IDENTIFIER_PATTERN.test(entry.attachedDeclaration)) score += 3;
  if (BARE_LITERAL_PATTERN.test(entry.attachedDeclaration)) score += 2;
  return Math.round(score * 10) / 10;
}

function expandRemovalToWholeLines(source, comment) {
  let lineStart = source.lastIndexOf('\n', comment.start - 1) + 1;
  const beforeIsBlank = source.slice(lineStart, comment.start).trim() === '';
  let lineEnd = source.indexOf('\n', comment.end);
  if (lineEnd === -1) lineEnd = source.length;
  const afterIsBlank = source.slice(comment.end, lineEnd).trim() === '';
  if (beforeIsBlank && afterIsBlank) {
    return { start: lineStart, end: Math.min(lineEnd + 1, source.length) };
  }
  let start = comment.start;
  while (start > lineStart && /[ \t]/.test(source[start - 1])) start -= 1;
  return { start, end: comment.end };
}

export function stripSource(source, extension) {
  const { comments, literals } = scanComments(source, extension);
  const removable = comments.filter((comment) => classifyComment(source, comment) === 'prose');
  const removed = removable.map((comment) => {
    const text = comment.body.replace(/^\s*\*+/gm, '').trim();
    const attachedDeclaration = attachedDeclarationAfter(source, comment.end);
    const entry = {
      line: lineNumberAt(source, comment.start),
      text,
      attachedDeclaration,
    };
    return { ...entry, score: scoreComment(entry) };
  });
  const ranges = removable
    .map((comment) => expandRemovalToWholeLines(source, comment))
    .sort((left, right) => left.start - right.start);
  let output = '';
  let cursor = 0;
  for (const range of ranges) {
    if (range.start < cursor) continue;
    output += source.slice(cursor, range.start);
    cursor = range.end;
  }
  output += source.slice(cursor);
  const verification = scanComments(output, extension);
  const literalsUnchanged =
    literals.length === verification.literals.length &&
    literals.every((literal, position) => literal === verification.literals[position]);
  return { output, removed, literalsUnchanged, remaining: verification.comments };
}

function collectSourceFiles(startPaths) {
  const collected = [];
  const walk = (path) => {
    const stats = statSync(path);
    if (stats.isDirectory()) {
      if (EXCLUDED_DIRECTORY_NAMES.has(basename(path))) return;
      for (const entry of readdirSync(path)) walk(join(path, entry));
      return;
    }
    if (EXCLUDED_FILE_NAMES.has(basename(path))) return;
    if (!LANGUAGE_BY_EXTENSION.has(extname(path))) return;
    collected.push(path);
  };
  for (const startPath of startPaths) walk(resolve(startPath));
  return collected.sort();
}

function parseArguments(argv) {
  const options = { mode: 'strip', paths: [], manifest: null, shard: null };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === '--check') options.mode = 'check';
    else if (argument === '--manifest') options.manifest = argv[++index];
    else if (argument === '--shard') options.shard = argv[++index];
    else options.paths.push(argument);
  }
  if (options.paths.length === 0) options.paths.push('.');
  return options;
}

function runCheck(files) {
  const violations = [];
  for (const file of files) {
    const source = readFileSync(file, 'utf8');
    const { comments } = scanComments(source, extname(file));
    for (const comment of comments) {
      const classification = classifyComment(source, comment);
      if (classification === 'directive') continue;
      violations.push({
        file: relative(process.cwd(), file),
        line: lineNumberAt(source, comment.start),
        kind: classification === 'rename-marker' ? 'abandoned RENAME marker' : 'comment',
        text: comment.body.trim().slice(0, 100),
      });
    }
  }
  if (violations.length === 0) {
    process.stdout.write(`comment-strip --check: clean (${files.length} files)\n`);
    return 0;
  }
  for (const violation of violations.slice(0, 50)) {
    process.stdout.write(`${violation.file}:${violation.line}  ${violation.kind}: ${violation.text}\n`);
  }
  if (violations.length > 50) process.stdout.write(`... and ${violations.length - 50} more\n`);
  process.stdout.write(`comment-strip --check: ${violations.length} violation(s) in ${files.length} files\n`);
  return 1;
}

function runStrip(files, options) {
  const manifestEntries = [];
  let filesChanged = 0;
  let commentsRemoved = 0;
  const failures = [];
  for (const file of files) {
    const source = readFileSync(file, 'utf8');
    const result = stripSource(source, extname(file));
    if (!result.literalsUnchanged) {
      failures.push(relative(process.cwd(), file));
      continue;
    }
    if (result.removed.length === 0) continue;
    writeFileSync(file, result.output);
    filesChanged += 1;
    commentsRemoved += result.removed.length;
    for (const entry of result.removed) {
      manifestEntries.push({ file: relative(process.cwd(), file), ...entry });
    }
  }
  manifestEntries.sort((left, right) => right.score - left.score);
  if (options.manifest) {
    mkdirSync(resolve(options.manifest, '..'), { recursive: true });
    writeFileSync(options.manifest, manifestEntries.map((entry) => JSON.stringify(entry)).join('\n') + '\n');
  }
  const aboveThreshold = manifestEntries.filter((entry) => entry.score >= HIGH_SCORE_THRESHOLD).length;
  const summary = {
    shard: options.shard,
    filesScanned: files.length,
    filesChanged,
    commentsRemoved,
    aboveThreshold,
    failures,
  };
  process.stdout.write(JSON.stringify(summary, null, 2) + '\n');
  if (failures.length > 0) {
    process.stderr.write(
      `ABORTED on ${failures.length} file(s): string literals changed, which means the scanner ` +
        `misread the source. These files were left untouched:\n${failures.join('\n')}\n`,
    );
    return 2;
  }
  return 0;
}

function main() {
  const options = parseArguments(process.argv.slice(2));
  const files = collectSourceFiles(options.paths);
  const exitCode = options.mode === 'check' ? runCheck(files) : runStrip(files, options);
  process.exit(exitCode);
}

if (import.meta.url === `file://${process.argv[1]}` || process.argv[1]?.endsWith('strip.mjs')) main();
