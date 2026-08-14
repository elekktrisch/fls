#!/usr/bin/env node
import { readFileSync, writeFileSync, mkdirSync, readdirSync, realpathSync, statSync } from 'node:fs';
import { extname, join, relative, resolve, basename, sep } from 'node:path';

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

const EMBEDDED_LANGUAGE_BY_ANGULAR_INLINE_PROPERTY = new Map([
  ['template', 'html'],
  ['styles', 'css'],
]);

const EXCLUDED_DIRECTORY_NAMES = new Set([
  'node_modules', '.gradle', '.git', '.angular', 'flsserver', 'flsweb',
  '.comment-strip', 'generated',
]);

const EXCLUDED_DIRECTORY_NAME_PREFIXES = ['node_modules'];

const BUILD_OUTPUT_DIRECTORY_NAMES = new Set([
  'build', 'target', 'dist', 'coverage', 'test-results', 'playwright-report',
]);

const SOURCE_ROOT_DIRECTORY_NAME = 'src';

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
  /^\s*shellcheck\s+(source|disable|shell|enable|external-sources)=/,
  /^\s*ext:/,
  /^\s*@mocked:/,
];

const MOCKED_SEAM_DECLARATION = /^\s*\*?\s*@mocked:\s*(.+)$/;
const SEAM_AND_REASON_SEPARATOR = /\s+(?:—|–|--|-)\s+/;
const MOCKED_SEAM_ON_A_LINE_OF_ITS_OWN = /^\s*\*?\s*@mocked:/m;

const RENAME_MARKER_PATTERN = /^\s*RENAME:/;

const HEREDOC_BODY_LANGUAGE_BY_INTERPRETER = new Map([
  ['python', 'python'],
  ['python2', 'python'],
  ['python3', 'python'],
  ['node', 'javascript'],
  ['nodejs', 'javascript'],
  ['psql', 'sql'],
  ['sh', 'shell'],
  ['bash', 'shell'],
  ['zsh', 'shell'],
  ['ksh', 'shell'],
  ['dash', 'shell'],
  ['jq', 'hash'],
  ['awk', 'hash'],
  ['gawk', 'hash'],
  ['ruby', 'hash'],
  ['perl', 'hash'],
]);

const COMMANDS_THAT_PRINT_THE_HEREDOC_FOR_A_HUMAN_TO_READ = new Set([
  'cat', 'echo', 'printf', 'tee', 'column', 'less', 'more', 'head', 'tail',
]);

const COMMAND_PREFIXES_THAT_DELEGATE_TO_THE_NEXT_WORD = new Set([
  'env', 'exec', 'command', 'time', 'sudo', 'nohup', 'nice', 'stdbuf', 'then', 'do', 'else',
]);

const COMMANDS_THAT_RUN_ANOTHER_COMMAND_SOMEWHERE_IN_THEIR_ARGUMENTS = new Set([
  'ssh', 'docker', 'podman', 'kubectl', 'timeout', 'flock', 'su', 'chroot', 'xargs',
]);

const OUTPUT_REDIRECTION_TO_A_FILE = />(?!&)/;
const CLOSING_GROUP_REDIRECTED_TO_A_FILE = /^\s*[)}]\s*\d*>(?!&)/;
const SHELL_EXPANSION_INSIDE_A_HEREDOC_BODY = /[$`\\]/;

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

function scanTemplateLiteral(source, startIndex) {
  let index = startIndex + 1;
  let staticChunkStart = index;
  const staticChunks = [];
  while (index < source.length) {
    const character = source[index];
    if (character === '\\') {
      index += 2;
      continue;
    }
    if (character === '`') {
      staticChunks.push({ start: staticChunkStart, end: index });
      return { end: index + 1, staticChunks };
    }
    if (character === '$' && source[index + 1] === '{') {
      staticChunks.push({ start: staticChunkStart, end: index });
      index = consumeInterpolationBlock(source, index + 2);
      staticChunkStart = index;
      continue;
    }
    index += 1;
  }
  staticChunks.push({ start: staticChunkStart, end: source.length });
  return { end: source.length, staticChunks };
}

function consumeTemplateLiteral(source, startIndex) {
  return scanTemplateLiteral(source, startIndex).end;
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

function embeddedCommentsIn(text, embeddedLanguage) {
  if (embeddedLanguage === 'css') {
    return scanCLike(text, '.css').comments.map((comment) =>
      text.slice(comment.end - 2, comment.end) === '*/'
        ? comment
        : { ...comment, reportOnly: true, kind: 'unterminated comment in an inline styles literal' },
    );
  }
  return scanHtml(text).comments;
}

function commentsInsideAnInlineLiteral(source, staticChunks, embeddedLanguage) {
  const collected = [];
  for (const chunk of staticChunks) {
    const text = source.slice(chunk.start, chunk.end);
    for (const comment of embeddedCommentsIn(text, embeddedLanguage)) {
      collected.push({ ...comment, start: comment.start + chunk.start, end: comment.end + chunk.start });
    }
  }
  return collected;
}

export function inlineLiteralTokenStream(literalText, embeddedLanguage) {
  const { staticChunks } = scanTemplateLiteral(literalText, 0);
  const strippable = commentsInsideAnInlineLiteral(literalText, staticChunks, embeddedLanguage).filter(
    (comment) => comment.reportOnly !== true,
  );
  let withoutComments = '';
  let cursor = 0;
  for (const comment of strippable) {
    withoutComments += literalText.slice(cursor, comment.start);
    cursor = comment.end;
  }
  withoutComments += literalText.slice(cursor);
  return withoutComments.split(/\s+/).filter((token) => token !== '').join(' ');
}

function angularInlineLiteralTracker() {
  let property = null;
  let stage = 'none';
  return {
    sawWord(word) {
      const isAnInlineLiteralProperty = EMBEDDED_LANGUAGE_BY_ANGULAR_INLINE_PROPERTY.has(word);
      property = isAnInlineLiteralProperty ? word : null;
      stage = isAnInlineLiteralProperty ? 'name' : 'none';
    },
    sawPunctuation(character) {
      if (property === null) return;
      if (character === ':' && stage === 'name') stage = 'value';
      else if (character === '[' && stage === 'value') stage = 'array';
      else if (character === ',' && stage === 'array') return;
      else {
        property = null;
        stage = 'none';
      }
    },
    languageOfTheLiteralStartingHere() {
      if (property === null || (stage !== 'value' && stage !== 'array')) return null;
      return EMBEDDED_LANGUAGE_BY_ANGULAR_INLINE_PROPERTY.get(property);
    },
    sawLiteral() {
      if (stage === 'array') return;
      property = null;
      stage = 'none';
    },
  };
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
  const angularInlineLiteral = angularInlineLiteralTracker();
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
      const embeddedLanguage = angularInlineLiteral.languageOfTheLiteralStartingHere();
      const { end, staticChunks } = scanTemplateLiteral(source, index);
      if (embeddedLanguage === null) {
        literals.push(source.slice(index, end));
      } else {
        for (const comment of commentsInsideAnInlineLiteral(source, staticChunks, embeddedLanguage)) {
          comments.push(comment);
        }
        literals.push(inlineLiteralTokenStream(source.slice(index, end), embeddedLanguage));
      }
      angularInlineLiteral.sawLiteral();
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
      angularInlineLiteral.sawWord(lastWord);
      index = end;
      continue;
    }
    if (!/\s/.test(character)) {
      lastCodeCharacter = character;
      lastWord = '';
      angularInlineLiteral.sawPunctuation(character);
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

function consumeTripleQuotedRun(source, startIndex, quoteCharacter) {
  const fence = quoteCharacter.repeat(3);
  let index = startIndex + 3;
  while (index < source.length) {
    if (source[index] === '\\') {
      index += 2;
      continue;
    }
    if (source.startsWith(fence, index)) return index + 3;
    index += 1;
  }
  return source.length;
}

function standsWhereADocstringWould(source, index) {
  const lineStart = source.lastIndexOf('\n', index - 1) + 1;
  if (source.slice(lineStart, index).trim() !== '') return false;
  const precedingLines = source
    .slice(0, lineStart)
    .split('\n')
    .filter((line) => line.trim() !== '');
  if (precedingLines.length === 0) return true;
  return precedingLines[precedingLines.length - 1].trimEnd().endsWith(':');
}

function scanPython(source) {
  const comments = [];
  const literals = [];
  let index = 0;
  while (index < source.length) {
    const character = source[index];
    if (character === '#') {
      let end = source.indexOf('\n', index);
      if (end === -1) end = source.length;
      comments.push({
        start: index,
        end,
        body: source.slice(index + 1, end),
        directive: index === 0 && source.startsWith('#!'),
      });
      index = end;
      continue;
    }
    if (character === '"' || character === "'") {
      const isTripleQuoted = source.startsWith(character.repeat(3), index);
      const end = isTripleQuoted
        ? consumeTripleQuotedRun(source, index, character)
        : consumeQuotedRun(source, index, character, true);
      literals.push(source.slice(index, end));
      if (isTripleQuoted && standsWhereADocstringWould(source, index)) {
        comments.push({
          start: index,
          end,
          body: source.slice(index + 3, Math.max(index + 3, end - 3)),
          reportOnly: true,
          kind: 'docstring (a string literal — removing it is a judgement call, not a lexical one)',
        });
      }
      index = end;
      continue;
    }
    index += 1;
  }
  return { comments, literals };
}

function scanHashCommentProgram(source, { onlyWhenAloneOnItsLine = false } = {}) {
  const comments = [];
  const literals = [];
  let index = 0;
  let previousCharacter = '';
  while (index < source.length) {
    const character = source[index];
    if (character === "'" || character === '"') {
      const end = consumeQuotedRun(source, index, character, false);
      literals.push(source.slice(index, end));
      index = end;
      previousCharacter = character;
      continue;
    }
    if (character === '#' && (index === 0 || /[\s;&|(]/.test(previousCharacter))) {
      const lineStart = source.lastIndexOf('\n', index - 1) + 1;
      const aloneOnItsLine = source.slice(lineStart, index).trim() === '';
      if (onlyWhenAloneOnItsLine && !aloneOnItsLine) {
        previousCharacter = character;
        index += 1;
        continue;
      }
      let end = source.indexOf('\n', index);
      if (end === -1) end = source.length;
      comments.push({
        start: index,
        end,
        body: source.slice(index + 1, end),
        directive: index === 0 && source.startsWith('#!'),
      });
      index = end;
      continue;
    }
    previousCharacter = character;
    index += 1;
  }
  return { comments, literals };
}

function scanHeredocBody(body, language) {
  if (language === 'python') return scanPython(body);
  if (language === 'javascript') return scanCLike(body, '.js');
  if (language === 'sql') return scanSql(body);
  if (language === 'shell') return scanShell(body);
  return scanHashCommentProgram(body);
}

function firstCommandWordBefore(commandText) {
  const words = (commandText.match(/\S+/g) ?? [])
    .map((word) => word.replace(/^[({]+/, ''))
    .filter((word) => word !== '' && !/^[A-Za-z_][A-Za-z0-9_]*=/.test(word))
    .map((word) => word.split('/').pop());
  let wrapperAwaitingItsCommand = '';
  for (const word of words) {
    if (COMMAND_PREFIXES_THAT_DELEGATE_TO_THE_NEXT_WORD.has(word)) continue;
    if (wrapperAwaitingItsCommand === '') {
      if (!COMMANDS_THAT_RUN_ANOTHER_COMMAND_SOMEWHERE_IN_THEIR_ARGUMENTS.has(word)) return word;
      wrapperAwaitingItsCommand = word;
      continue;
    }
    if (
      HEREDOC_BODY_LANGUAGE_BY_INTERPRETER.has(word) ||
      COMMANDS_THAT_PRINT_THE_HEREDOC_FOR_A_HUMAN_TO_READ.has(word)
    ) {
      return word;
    }
  }
  return wrapperAwaitingItsCommand;
}

function heredocOutputLeavesTheTerminal(source, commandText, terminatorEnd) {
  if (OUTPUT_REDIRECTION_TO_A_FILE.test(commandText)) return true;
  const nextMeaningfulLine =
    source
      .slice(terminatorEnd)
      .split('\n')
      .find((line) => line.trim() !== '') ?? '';
  return CLOSING_GROUP_REDIRECTED_TO_A_FILE.test(nextMeaningfulLine);
}

function absorbHeredocBody(source, heredoc, bodyStart, bodyEnd, terminatorEnd, comments, literals) {
  const body = source.slice(bodyStart, bodyEnd);
  const commandWord = firstCommandWordBefore(heredoc.commandText);
  const language = HEREDOC_BODY_LANGUAGE_BY_INTERPRETER.get(commandWord);
  if (language !== undefined) {
    const scanned = scanHeredocBody(body, language);
    for (const literal of scanned.literals) literals.push(literal);
    for (const comment of scanned.comments) {
      const shifted = { ...comment, start: comment.start + bodyStart, end: comment.end + bodyStart };
      if (!heredoc.delimiterIsQuoted && SHELL_EXPANSION_INSIDE_A_HEREDOC_BODY.test(comment.body)) {
        shifted.reportOnly = true;
        shifted.kind = `${commandWord} heredoc comment carrying a shell expansion (unquoted delimiter)`;
      }
      comments.push(shifted);
    }
    literals.push(source.slice(bodyEnd, terminatorEnd));
    return;
  }
  literals.push(source.slice(bodyStart, terminatorEnd));
  const readByAHumanOnTheTerminal =
    COMMANDS_THAT_PRINT_THE_HEREDOC_FOR_A_HUMAN_TO_READ.has(commandWord) &&
    !heredocOutputLeavesTheTerminal(source, heredoc.commandText, terminatorEnd);
  if (readByAHumanOnTheTerminal) return;
  const scanned = scanHashCommentProgram(body, { onlyWhenAloneOnItsLine: true });
  for (const comment of scanned.comments) {
    comments.push({
      ...comment,
      start: comment.start + bodyStart,
      end: comment.end + bodyStart,
      reportOnly: true,
      kind: `heredoc body of an undetermined interpreter (${commandWord || 'unknown'})`,
    });
  }
}

function scanShell(source) {
  const comments = [];
  const literals = [];
  let index = 0;
  let previousCharacter = '';
  let commandStartIndex = 0;
  const pendingHeredocs = [];
  while (index < source.length) {
    const character = source[index];
    if (character === '\n' && pendingHeredocs.length > 0) {
      let cursor = index + 1;
      while (pendingHeredocs.length > 0) {
        const heredoc = pendingHeredocs.shift();
        heredoc.commandText = source.slice(heredoc.commandStartIndex, index);
        const bodyStart = cursor;
        let bodyEnd = source.length;
        let terminatorEnd = source.length;
        while (cursor < source.length) {
          let lineEnd = source.indexOf('\n', cursor);
          if (lineEnd === -1) lineEnd = source.length;
          const line = source.slice(cursor, lineEnd);
          const compared = heredoc.stripsIndentation ? line.replace(/^\t+/, '') : line;
          const lineFollowedByItsNewline = Math.min(lineEnd + 1, source.length);
          if (compared.trimEnd() === heredoc.delimiter) {
            bodyEnd = cursor;
            terminatorEnd = lineFollowedByItsNewline;
            cursor = lineFollowedByItsNewline;
            break;
          }
          cursor = lineFollowedByItsNewline;
          bodyEnd = cursor;
          terminatorEnd = cursor;
        }
        absorbHeredocBody(source, heredoc, bodyStart, bodyEnd, terminatorEnd, comments, literals);
      }
      index = cursor;
      previousCharacter = '\n';
      commandStartIndex = index;
      continue;
    }
    if (character === '<' && source[index + 1] === '<' && source[index + 2] !== '<') {
      const heredocMatch = /^<<(-?)\s*(['"]?)([A-Za-z_][A-Za-z0-9_]*)\2/.exec(source.slice(index));
      if (heredocMatch) {
        pendingHeredocs.push({
          delimiter: heredocMatch[3],
          stripsIndentation: heredocMatch[1] === '-',
          delimiterIsQuoted: heredocMatch[2] !== '',
          commandStartIndex,
        });
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
      comments.push({
        start: index,
        end,
        body: source.slice(index + 1, end),
        directive: index === 0 && source.startsWith('#!'),
      });
      index = end;
      continue;
    }
    if (/[\n;&|()]/.test(character)) commandStartIndex = index + 1;
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
    if (closing === -1) {
      comments.push({
        start: opening,
        end: source.length,
        body: source.slice(opening + 4),
        reportOnly: true,
        kind: 'unterminated HTML comment (no --> bounds it, so removing it would swallow markup)',
      });
      break;
    }
    const end = closing + 3;
    comments.push({ start: opening, end, body: source.slice(opening + 4, end - 3) });
    index = end;
  }
  return { comments, literals: [] };
}

function reportRatherThanSwallowAnEmbeddedMockedSeam(source, comment) {
  if (comment.reportOnly === true) return comment;
  if (classifyComment(source, comment) !== 'prose') return comment;
  if (!MOCKED_SEAM_ON_A_LINE_OF_ITS_OWN.test(comment.body)) return comment;
  return {
    ...comment,
    reportOnly: true,
    kind: 'prose comment carrying an @mocked: seam declaration (hoist the tag onto a comment of its own, then re-run)',
  };
}

function scanByLanguage(source, extension) {
  const language = LANGUAGE_BY_EXTENSION.get(extension);
  if (language === 'clike') return scanCLike(source, extension);
  if (language === 'sql') return scanSql(source);
  if (language === 'shell') return scanShell(source);
  if (language === 'yaml') return scanYaml(source);
  if (language === 'html') return scanHtml(source);
  return { comments: [], literals: [] };
}

export function scanComments(source, extension) {
  const scanned = scanByLanguage(source, extension);
  return {
    ...scanned,
    comments: scanned.comments.map((comment) => reportRatherThanSwallowAnEmbeddedMockedSeam(source, comment)),
  };
}

export function classifyComment(source, comment) {
  const isShebang = comment.directive === true || (comment.start === 0 && source.startsWith('#!'));
  if (isShebang) return 'directive';
  if (RENAME_MARKER_PATTERN.test(comment.body)) return 'rename-marker';
  if (TOOL_DIRECTIVE_PATTERNS.some((pattern) => pattern.test(comment.body))) return 'directive';
  return 'prose';
}

export function mockedSeamDeclarations(source, extension) {
  return mockedSeamsAmong(source, scanComments(source, extension).comments);
}

function mockedSeamsAmong(source, comments) {
  const declarations = [];
  for (const comment of comments) {
    const commentStartLine = lineNumberAt(source, comment.start);
    comment.body.split('\n').forEach((bodyLine, lineOffsetInComment) => {
      const declaration = MOCKED_SEAM_DECLARATION.exec(bodyLine);
      if (declaration === null) return;
      const separator = SEAM_AND_REASON_SEPARATOR.exec(declaration[1]);
      declarations.push({
        line: commentStartLine + lineOffsetInComment,
        seam: (separator === null ? declaration[1] : declaration[1].slice(0, separator.index)).trim(),
        reason: separator === null ? '' : declaration[1].slice(separator.index + separator[0].length).trim(),
      });
    });
  }
  return declarations;
}

function lineNumberAt(source, index) {
  let line = 1;
  for (let cursor = 0; cursor < index; cursor += 1) if (source[cursor] === '\n') line += 1;
  return line;
}

const LINE_COMMENT_OPENERS = ['//', '--', '#'];

function isLineComment(source, comment) {
  return LINE_COMMENT_OPENERS.some((opener) => source.startsWith(opener, comment.start));
}

function startsOnItsOwnLine(source, comment) {
  const lineStart = source.lastIndexOf('\n', comment.start - 1) + 1;
  return source.slice(lineStart, comment.start).trim() === '';
}

function groupContiguousLineComments(source, comments) {
  const groups = [];
  for (const comment of comments) {
    const startLine = lineNumberAt(source, comment.start);
    const joinsAsOwnLineRun = isLineComment(source, comment) && startsOnItsOwnLine(source, comment);
    const previousGroup = groups[groups.length - 1];
    const continuesPreviousRun =
      previousGroup !== undefined &&
      previousGroup.isOwnLineRun &&
      joinsAsOwnLineRun &&
      previousGroup.endLine + 1 === startLine;
    if (continuesPreviousRun) {
      previousGroup.comments.push(comment);
      previousGroup.endLine = startLine;
      continue;
    }
    groups.push({
      comments: [comment],
      startLine,
      endLine: lineNumberAt(source, comment.end),
      isOwnLineRun: joinsAsOwnLineRun,
    });
  }
  return groups;
}

function attachedDeclarationAfter(source, endIndex) {
  const remainder = source.slice(endIndex, endIndex + 800).split('\n');
  for (const line of remainder) {
    const trimmed = line.trim();
    if (trimmed !== '' && !/^(\/\/|\/\*|\*|--|#|<!--)/.test(trimmed)) return trimmed.slice(0, 160);
  }
  return '';
}

const CHARACTERS_PER_LENGTH_POINT = 40;
const MOST_A_COMMENT_EARNS_FOR_BEING_LONG = 6;
const CAUSAL_VOCABULARY_WEIGHT = 4;
const EXTERNAL_FACT_WEIGHT = 3;
const OPAQUE_ATTACHED_IDENTIFIER_WEIGHT = 3;
const BARE_LITERAL_WEIGHT_OUTRANKING_ANY_LENGTH = MOST_A_COMMENT_EARNS_FOR_BEING_LONG + 1;

export function scoreComment(entry) {
  let score = Math.min(entry.text.length / CHARACTERS_PER_LENGTH_POINT, MOST_A_COMMENT_EARNS_FOR_BEING_LONG);
  if (HIGH_INFORMATION_VOCABULARY.test(entry.text)) score += CAUSAL_VOCABULARY_WEIGHT;
  if (EXTERNAL_FACT_PATTERN.test(entry.text)) score += EXTERNAL_FACT_WEIGHT;
  if (GENERIC_IDENTIFIER_PATTERN.test(entry.attachedDeclaration)) score += OPAQUE_ATTACHED_IDENTIFIER_WEIGHT;
  if (BARE_LITERAL_PATTERN.test(entry.attachedDeclaration)) score += BARE_LITERAL_WEIGHT_OUTRANKING_ANY_LENGTH;
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
  if (beforeIsBlank) {
    let end = comment.end;
    while (end < lineEnd && /[ \t]/.test(source[end])) end += 1;
    return { start: comment.start, end };
  }
  let start = comment.start;
  while (start > lineStart && /[ \t]/.test(source[start - 1])) start -= 1;
  return { start, end: comment.end };
}

export function stripSource(source, extension) {
  const { comments, literals } = scanComments(source, extension);
  const removable = comments.filter(
    (comment) => comment.reportOnly !== true && classifyComment(source, comment) === 'prose',
  );
  const removed = groupContiguousLineComments(source, removable).map((group) => {
    const lastComment = group.comments[group.comments.length - 1];
    const text = group.comments
      .map((comment) => comment.body.replace(/^\s*\*+/gm, '').trim())
      .join('\n')
      .trim();
    const entry = {
      line: group.startLine,
      endLine: group.endLine,
      text,
      attachedDeclaration: attachedDeclarationAfter(source, lastComment.end),
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
  const reportedOnly = verification.comments.filter(
    (comment) => comment.reportOnly === true && classifyComment(output, comment) === 'prose',
  );
  return {
    output,
    removed,
    removedCommentCount: removable.length,
    literalsUnchanged,
    remaining: verification.comments,
    reportedOnly,
  };
}

function isExcludedDirectory(path) {
  const name = basename(path);
  if (EXCLUDED_DIRECTORY_NAMES.has(name)) return true;
  if (EXCLUDED_DIRECTORY_NAME_PREFIXES.some((prefix) => name.startsWith(prefix))) return true;
  const liesUnderASourceRoot = path.split(sep).slice(0, -1).includes(SOURCE_ROOT_DIRECTORY_NAME);
  return BUILD_OUTPUT_DIRECTORY_NAMES.has(name) && !liesUnderASourceRoot;
}

function resolveOrSkip(path) {
  try {
    return { stats: statSync(path), canonicalPath: realpathSync(path) };
  } catch {
    return null;
  }
}

export function collectSourceFiles(startPaths) {
  const collected = [];
  const alreadyWalkedDirectories = new Set();
  const walk = (path) => {
    const resolved = resolveOrSkip(path);
    if (resolved === null) return;
    const { stats, canonicalPath } = resolved;
    if (stats.isDirectory()) {
      if (isExcludedDirectory(path)) return;
      if (alreadyWalkedDirectories.has(canonicalPath)) return;
      alreadyWalkedDirectories.add(canonicalPath);
      for (const entry of readdirSync(path)) walk(join(path, entry));
      return;
    }
    if (!stats.isFile()) return;
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

function writeMockedSeamSection(mockedSeams) {
  process.stdout.write(`comment-strip --check: mocked seams (${mockedSeams.length})\n`);
  for (const seam of mockedSeams) {
    process.stdout.write(`  ${seam.file}:${seam.line}  ${seam.seam} — ${seam.reason}\n`);
  }
}

function runCheck(files) {
  const violations = [];
  const mockedSeams = [];
  for (const file of files) {
    const source = readFileSync(file, 'utf8');
    const relativePath = relative(process.cwd(), file);
    const { comments } = scanComments(source, extname(file));
    for (const declaration of mockedSeamsAmong(source, comments)) {
      mockedSeams.push({ file: relativePath, ...declaration });
    }
    for (const comment of comments) {
      const classification = classifyComment(source, comment);
      if (classification === 'directive') continue;
      violations.push({
        file: relativePath,
        line: lineNumberAt(source, comment.start),
        kind:
          classification === 'rename-marker'
            ? 'abandoned RENAME marker'
            : comment.reportOnly === true
              ? `comment in a ${comment.kind}`
              : 'comment',
        text: comment.body.trim().slice(0, 100),
      });
    }
  }
  writeMockedSeamSection(mockedSeams);
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
  const reportedNotStripped = [];
  for (const file of files) {
    const source = readFileSync(file, 'utf8');
    const result = stripSource(source, extname(file));
    if (!result.literalsUnchanged) {
      failures.push(relative(process.cwd(), file));
      continue;
    }
    for (const comment of result.reportedOnly) {
      reportedNotStripped.push(
        `${relative(process.cwd(), file)}:${lineNumberAt(result.output, comment.start)}  ${comment.kind}`,
      );
    }
    if (result.removed.length === 0) continue;
    writeFileSync(file, result.output);
    filesChanged += 1;
    commentsRemoved += result.removedCommentCount;
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
    manifestEntries: manifestEntries.length,
    aboveThreshold,
    failures,
    reportedNotStripped,
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
