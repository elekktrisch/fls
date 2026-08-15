import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { basename, join } from 'node:path';
import { tmpdir } from 'node:os';
import {
  stripSource,
  scanComments,
  classifyComment,
  scoreComment,
  collectSourceFiles,
  inlineLiteralTokenStream,
  mockedSeamDeclarations,
  TOOL_DIRECTIVE_PATTERNS,
} from './strip.mjs';

function strip(source, extension) {
  const result = stripSource(source, extension);
  assert.equal(result.literalsUnchanged, true, 'string literals must survive untouched');
  return result;
}

function withTemporaryTree(build) {
  const root = mkdtempSync(join(tmpdir(), 'comment-strip-'));
  try {
    build(root);
    return collectSourceFiles([root]).map((file) => basename(file));
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

test('java: a URL inside a string literal is not a comment', () => {
  const source = [
    'record Aircraft(',
    '    @Pattern(regexp = "^https://.*", flags = Pattern.Flag.CASE_INSENSITIVE,',
    '            message = "spotLink must start with https:// (A10 SSRF defense-in-depth)")',
    '    String spotLink) {}',
  ].join('\n');
  const result = strip(source, '.java');
  assert.equal(result.output, source);
  assert.equal(result.removed.length, 0);
});

test('java: line and javadoc comments are removed with their whole line', () => {
  const source = [
    '/**',
    ' * Resolves the tenant for the current request.',
    ' */',
    'public Tenant resolve() {',
    '    // fall back to the operator tenant',
    '    return operatorTenant;',
    '}',
  ].join('\n');
  const result = strip(source, '.java');
  assert.equal(result.output, ['public Tenant resolve() {', '    return operatorTenant;', '}'].join('\n'));
  assert.equal(result.removed.length, 2);
  assert.match(result.removed[0].text, /Resolves the tenant/);
});

test('java: a text block containing comment markers survives', () => {
  const source = ['String sql = """', '    -- not a comment', '    SELECT 1; // nor this', '    """;'].join('\n');
  const result = strip(source, '.java');
  assert.equal(result.output, source);
});

test('java: trailing comment keeps the code and drops the trailing whitespace', () => {
  const source = 'int retries = 5; // three was not enough\n';
  const result = strip(source, '.java');
  assert.equal(result.output, 'int retries = 5;\n');
});

test('java: a leading parameter-name comment goes without taking the indentation with it', () => {
  const source = ['void f() {', '    invite(', '        name,', '        /*enabled=*/ true);', '}'].join('\n');
  const result = strip(source, '.java');
  assert.equal(result.output, ['void f() {', '    invite(', '        name,', '        true);', '}'].join('\n'));
});

test('typescript: a regex literal containing a slash is not a comment', () => {
  const source = 'if (/[\\\\\\u0000-\\u001f]/.test(raw)) return fallback;\n';
  const result = strip(source, '.ts');
  assert.equal(result.output, source);
});

test('typescript: division is not mistaken for a regex literal', () => {
  const source = 'const ratio = total / count; // normalise\nconst half = width / 2;\n';
  const result = strip(source, '.ts');
  assert.equal(result.output, 'const ratio = total / count;\nconst half = width / 2;\n');
});

test('typescript: return followed by a regex literal is handled', () => {
  const source = 'function pattern() { return /a\\/b/; }\n';
  const result = strip(source, '.ts');
  assert.equal(result.output, source);
});

test('typescript: template literals and their interpolations are opaque', () => {
  const source = 'const url = `${base}//${path}`; // join\n';
  const result = strip(source, '.ts');
  assert.equal(result.output, 'const url = `${base}//${path}`;\n');
});

test('typescript: eslint directives survive, prose next to them does not', () => {
  const source = ['// eslint-disable-next-line no-console', '// logging here is deliberate', 'console.log(x);'].join('\n');
  const result = strip(source, '.ts');
  assert.equal(result.output, ['// eslint-disable-next-line no-console', 'console.log(x);'].join('\n'));
});

test('typescript: an ext: marker survives', () => {
  const source = ['// ext: Proffix field name', 'artikelnummer: string;'].join('\n');
  const result = strip(source, '.ts');
  assert.equal(result.output, source);
});

test('sql: a double dash inside a string literal is not a comment', () => {
  const source = "INSERT INTO note (body) VALUES ('a -- b');\n";
  const result = strip(source, '.sql');
  assert.equal(result.output, source);
});

test('sql: dollar-quoted bodies are opaque', () => {
  const source = ['CREATE FUNCTION f() RETURNS int AS $$', '  -- inside the body', '  SELECT 1;', '$$ LANGUAGE sql;'].join('\n');
  const result = strip(source, '.sql');
  assert.equal(result.output, source);
});

test('sql: line comments are removed and COMMENT ON is left alone', () => {
  const source = ['-- covers tombstones: CASCADE join target', 'CREATE INDEX ix_a ON a (b);', "COMMENT ON COLUMN a.b IS 'kept';"].join('\n');
  const result = strip(source, '.sql');
  assert.equal(result.output, ['CREATE INDEX ix_a ON a (b);', "COMMENT ON COLUMN a.b IS 'kept';"].join('\n'));
});

test('shell: a hash inside a quoted string is not a comment', () => {
  const source = 'echo "count=#1"\nVAR=${#items}\n';
  const result = strip(source, '.sh');
  assert.equal(result.output, source);
});

test('shell: the shebang survives and prose does not', () => {
  const source = ['#!/usr/bin/env bash', '# bring up the dev stack', 'set -euo pipefail'].join('\n');
  const result = strip(source, '.sh');
  assert.equal(result.output, ['#!/usr/bin/env bash', 'set -euo pipefail'].join('\n'));
});

test('shell: shellcheck directives survive, prose beside them does not', () => {
  const source = [
    '# shellcheck source=lib/fail-loud.sh',
    '# sourced so the bring-up dies at its own failure point',
    'source lib/fail-loud.sh',
    '# shellcheck disable=SC1090',
    'source "${dynamic}"',
  ].join('\n');
  const result = strip(source, '.sh');
  assert.equal(
    result.output,
    [
      '# shellcheck source=lib/fail-loud.sh',
      'source lib/fail-loud.sh',
      '# shellcheck disable=SC1090',
      'source "${dynamic}"',
    ].join('\n'),
  );
});

test('shell: heredoc bodies are opaque', () => {
  const source = ['cat <<EOF', '# not a comment', 'body', 'EOF', '# a comment'].join('\n');
  const result = strip(source, '.sh');
  assert.equal(result.output, ['cat <<EOF', '# not a comment', 'body', 'EOF', ''].join('\n'));
});

test('shell: a python heredoc fed to python3 has its comments stripped', () => {
  const source = [
    "python3 - \"$REALM\" <<'PYEOF'",
    'import json',
    '',
    '# Dev-only passwords baked alongside the realm.',
    "# The realm passwordPolicy demands specialChars(1).",
    "DEV_PASSWORDS = {'sysadmin': 'sysadmin-dev-2026!'}",
    "print(json.dumps(DEV_PASSWORDS))  # emit for the caller",
    'PYEOF',
  ].join('\n');
  const result = strip(source, '.sh');
  assert.equal(
    result.output,
    [
      "python3 - \"$REALM\" <<'PYEOF'",
      'import json',
      '',
      "DEV_PASSWORDS = {'sysadmin': 'sysadmin-dev-2026!'}",
      'print(json.dumps(DEV_PASSWORDS))',
      'PYEOF',
    ].join('\n'),
  );
  assert.equal(result.removedCommentCount, 3);
  assert.deepEqual(
    result.removed.map((entry) => entry.text),
    ['Dev-only passwords baked alongside the realm.\nThe realm passwordPolicy demands specialChars(1).', 'emit for the caller'],
  );
});

test('shell: a hash inside a python heredoc string literal is not a comment', () => {
  const source = ["python3 <<'PYEOF'", "colour = '#ff0000'  # the brand red", 'print(colour)', 'PYEOF'].join('\n');
  const result = strip(source, '.sh');
  assert.equal(result.output, ["python3 <<'PYEOF'", "colour = '#ff0000'", 'print(colour)', 'PYEOF'].join('\n'));
});

test('shell: a python docstring is reported but never stripped', () => {
  const source = [
    "python3 <<'PYEOF'",
    'def sort_string_arrays(obj):',
    '    """Keycloak emits string arrays in non-deterministic order."""',
    '    return obj',
    'PYEOF',
  ].join('\n');
  const result = strip(source, '.sh');
  assert.equal(result.output, source);
  assert.equal(result.removed.length, 0);
  assert.equal(result.reportedOnly.length, 1);
  assert.match(result.reportedOnly[0].kind, /docstring/);
});

test('shell: an INFO heredoc echoed to the terminal is left byte-identical', () => {
  const source = [
    'cat <<INFO',
    '',
    '  Keycloak admin  http://localhost:8090  (admin / admin)',
    '',
    'Tear down (in order):',
    '  docker compose -p alpenflight-dev down [-v]',
    '  docker network rm alpenflight_shared   # only when retiring the dev stack',
    'INFO',
    '# this one is a real comment',
    'exit 0',
  ].join('\n');
  const result = strip(source, '.sh');
  assert.equal(
    result.output,
    [
      'cat <<INFO',
      '',
      '  Keycloak admin  http://localhost:8090  (admin / admin)',
      '',
      'Tear down (in order):',
      '  docker compose -p alpenflight-dev down [-v]',
      '  docker network rm alpenflight_shared   # only when retiring the dev stack',
      'INFO',
      'exit 0',
    ].join('\n'),
  );
  assert.equal(result.reportedOnly.length, 0);
});

test("shell: a heredoc whose interpreter cannot be determined is reported, not stripped", () => {
  const source = [
    '{',
    "    cat <<'STUB'",
    'if [[ "${1:-}" == "network" ]]; then',
    '    # the stub must answer bridge or the caller aborts on driver drift',
    '    echo bridge',
    'fi',
    'STUB',
    '} >"${dir}/docker"',
  ].join('\n');
  const result = strip(source, '.sh');
  assert.equal(result.output, source);
  assert.equal(result.removed.length, 0);
  assert.equal(result.reportedOnly.length, 1);
  assert.match(result.reportedOnly[0].kind, /undetermined interpreter \(cat\)/);
  assert.match(result.reportedOnly[0].body, /answer bridge/);
});

test('shell: an unquoted-delimiter heredoc comment carrying an expansion is reported, not stripped', () => {
  const source = [
    'python3 <<PYEOF',
    '# plain prose about the realm',
    '# resolved from ${REALM_NAME} at expansion time',
    'print(1)',
    'PYEOF',
  ].join('\n');
  const result = strip(source, '.sh');
  assert.equal(
    result.output,
    ['python3 <<PYEOF', '# resolved from ${REALM_NAME} at expansion time', 'print(1)', 'PYEOF'].join('\n'),
  );
  assert.deepEqual(
    result.removed.map((entry) => entry.text),
    ['plain prose about the realm'],
  );
  assert.equal(result.reportedOnly.length, 1);
  assert.match(result.reportedOnly[0].kind, /unquoted delimiter/);
});

test('shell: a generated script fed to bash keeps its shebang and loses its prose', () => {
  const source = ["ssh host bash <<'REMOTE'", '#!/usr/bin/env bash', '# restart the service', 'systemctl restart app', 'REMOTE'].join('\n');
  const result = strip(source, '.sh');
  assert.equal(
    result.output,
    ["ssh host bash <<'REMOTE'", '#!/usr/bin/env bash', 'systemctl restart app', 'REMOTE'].join('\n'),
  );
});

test('shell: a psql heredoc loses its SQL comments and keeps its string literals', () => {
  const source = [
    "psql -v ON_ERROR_STOP=1 <<'SQL'",
    '-- covers tombstones',
    "SELECT 'a -- b';",
    'SQL',
  ].join('\n');
  const result = strip(source, '.sh');
  assert.equal(result.output, ["psql -v ON_ERROR_STOP=1 <<'SQL'", "SELECT 'a -- b';", 'SQL'].join('\n'));
});

test('shell: heredoc comments are visible to a scan of the whole file', () => {
  const source = ["python3 <<'PYEOF'", '# invisible before T-05b', 'print(1)', 'PYEOF'].join('\n');
  const { comments } = scanComments(source, '.sh');
  assert.equal(comments.length, 1);
  assert.equal(classifyComment(source, comments[0]), 'prose');
});

test('yaml: comments go, quoted hashes and block scalars stay', () => {
  const source = [
    '# Flyway settings',
    'flyway:',
    '  clean-disabled: true          # non-negotiable',
    '  token: "abc#def"',
    '  script: |',
    '    # this is data, not a comment',
    '    echo hi',
    '  next: 1',
  ].join('\n');
  const result = strip(source, '.yml');
  assert.equal(
    result.output,
    ['flyway:', '  clean-disabled: true', '  token: "abc#def"', '  script: |', '    # this is data, not a comment', '    echo hi', '  next: 1'].join('\n'),
  );
});

test('angular: comments inside an inline template go and the markup around them is untouched', () => {
  const source = [
    '@Component({',
    "  selector: 'af-nav-bar',",
    '  template: `',
    '    <header>',
    '      <!-- Below-md hamburger -->',
    '      @if (!isWide()) {',
    '        <button [attr.aria-label]="\'Open navigation\'" data-testid="af-nav-burger">',
    '          {{ title() }}',
    '        </button>',
    '      }',
    '      @for (item of items(); track item.path) {',
    '        <a [routerLink]="item.path">{{ item.label }}</a> <!-- one anchor per item -->',
    '      }',
    '    </header>',
    '  `,',
    '})',
    'export class AfNavBarComponent {}',
  ].join('\n');
  const result = strip(source, '.ts');
  assert.equal(
    result.output,
    [
      '@Component({',
      "  selector: 'af-nav-bar',",
      '  template: `',
      '    <header>',
      '      @if (!isWide()) {',
      '        <button [attr.aria-label]="\'Open navigation\'" data-testid="af-nav-burger">',
      '          {{ title() }}',
      '        </button>',
      '      }',
      '      @for (item of items(); track item.path) {',
      '        <a [routerLink]="item.path">{{ item.label }}</a>',
      '      }',
      '    </header>',
      '  `,',
      '})',
      'export class AfNavBarComponent {}',
    ].join('\n'),
  );
  assert.deepEqual(
    result.removed.map((entry) => entry.text),
    ['Below-md hamburger', 'one anchor per item'],
  );
});

test('angular: inline-template comments are visible to a scan of the whole file', () => {
  const source = ['@Component({ template: `<div><!-- invisible before T-08b --></div>` })', 'class A {}'].join('\n');
  const { comments } = scanComments(source, '.ts');
  assert.equal(comments.length, 1);
  assert.equal(classifyComment(source, comments[0]), 'prose');
});

test('angular: a comment inside an inline styles array is lexed as CSS, not as a line comment', () => {
  const source = [
    '@Component({',
    '  styles: [',
    '    `',
    '      /* Maintenance hatch — reference gradient, not expressible as a Tailwind utility. */',
    '      .af-maintenance-band {',
    "        background: url('https://cdn.example/x.png');",
    '      }',
    '    `,',
    '  ],',
    '})',
    'export class ReservationsCalendarPage {}',
  ].join('\n');
  const result = strip(source, '.ts');
  assert.equal(
    result.output,
    [
      '@Component({',
      '  styles: [',
      '    `',
      '      .af-maintenance-band {',
      "        background: url('https://cdn.example/x.png');",
      '      }',
      '    `,',
      '  ],',
      '})',
      'export class ReservationsCalendarPage {}',
    ].join('\n'),
  );
  assert.equal(result.removed.length, 1);
  assert.match(result.removed[0].text, /Maintenance hatch/);
});

test('a template literal that is not an Angular inline template stays opaque', () => {
  const source = ['const page = `<!-- machine-readable build stamp -->`;', '// prose'].join('\n');
  const result = strip(source, '.ts');
  assert.equal(result.output, 'const page = `<!-- machine-readable build stamp -->`;\n');
});

test('angular: an interpolation inside an inline template is opaque, the markup around it is not', () => {
  const source = ['@Component({', '  template: `<div>${wrap(`<!-- inner -->`)}</div><!-- outer -->`,', '})'].join('\n');
  const result = strip(source, '.ts');
  assert.equal(
    result.output,
    ['@Component({', '  template: `<div>${wrap(`<!-- inner -->`)}</div>`,', '})'].join('\n'),
  );
  assert.deepEqual(
    result.removed.map((entry) => entry.text),
    ['outer'],
  );
});

test('the inline-template invariant asserts the non-comment token stream, not the raw literal', () => {
  const withComment = ['`', '  <!-- gone -->', '  <a [routerLink]="\'/x\'">{{ label }}</a>', '`'].join('\n');
  const stripped = ['`', '  <a [routerLink]="\'/x\'">{{ label }}</a>', '`'].join('\n');
  assert.equal(inlineLiteralTokenStream(withComment, 'html'), inlineLiteralTokenStream(stripped, 'html'));
  const reflowed = '` <a [routerLink]="\'/x\'">{{ label }}</a> `';
  assert.equal(inlineLiteralTokenStream(withComment, 'html'), inlineLiteralTokenStream(reflowed, 'html'));
  for (const damaged of [
    ['`', '  <a [routerLink]="\'/x\'">{{ }}</a>', '`'].join('\n'),
    ['`', '  <a routerLink="/x">{{ label }}</a>', '`'].join('\n'),
    ['`', '  <a [routerLink]="\'/x\'">{{ label }}</a><b></b>', '`'].join('\n'),
  ]) {
    assert.notEqual(
      inlineLiteralTokenStream(withComment, 'html'),
      inlineLiteralTokenStream(damaged, 'html'),
      `losing or gaining a token must break the invariant: ${damaged}`,
    );
  }
});

test('html: an unterminated comment is reported, never removed', () => {
  const source = ['<div>', '<!-- opened but never closed', '<span>kept</span>'].join('\n');
  const result = strip(source, '.html');
  assert.equal(result.output, source);
  assert.equal(result.removed.length, 0);
  assert.equal(result.reportedOnly.length, 1);
  assert.match(result.reportedOnly[0].kind, /unterminated HTML comment/);
});

test('angular: an unterminated comment leaves the whole inline template untouched', () => {
  const source = ['@Component({', '  template: `', '    <!-- opened but never closed', '    <p>kept</p>', '  `,', '})'].join('\n');
  const result = strip(source, '.ts');
  assert.equal(result.output, source);
  assert.equal(result.removed.length, 0);
  assert.match(result.reportedOnly[0].kind, /unterminated HTML comment/);
});

test('a @mocked: seam declaration survives the strip; prose beside it does not', () => {
  const source = [
    '// @mocked: http — guard unit test stubs the join-request probe',
    '// the probe is stubbed because Keycloak is not booted here',
    'const probe = of([]);',
  ].join('\n');
  const { comments } = scanComments(source, '.ts');
  assert.equal(classifyComment(source, comments[0]), 'directive');
  const result = strip(source, '.ts');
  assert.equal(
    result.output,
    ['// @mocked: http — guard unit test stubs the join-request probe', 'const probe = of([]);'].join('\n'),
  );
});

test('a prose block that buries an @mocked: seam is reported, never swallowed', () => {
  const source = [
    '/**',
    ' * Drives the mock-auth screen end to end.',
    ' * @mocked: http — mock-auth screen e2e',
    ' */',
    "test('list', async () => {});",
  ].join('\n');
  const result = strip(source, '.ts');
  assert.equal(result.output, source);
  assert.equal(result.removed.length, 0);
  assert.equal(result.reportedOnly.length, 1);
  assert.match(result.reportedOnly[0].kind, /@mocked: seam declaration/);
  assert.deepEqual(mockedSeamDeclarations(source, '.ts'), [
    { line: 3, seam: 'http', reason: 'mock-auth screen e2e' },
  ]);
});

test('--check enumerates every @mocked: seam with its line, seam and reason', () => {
  const source = [
    "import { of } from 'rxjs';",
    '',
    '// @mocked: http — guard unit test stubs the join-request probe',
    'const probe = of([]);',
    '',
    '/**',
    ' * @mocked: keycloak-provision -- no realm is bootable on this box',
    ' */',
    'const realm = null;',
    '',
    '// @mocked: clock',
    'const now = 0;',
    '',
    '// this spec has NO `@mocked:` seams — every seam is the real stack',
    'const real = true;',
  ].join('\n');
  assert.deepEqual(mockedSeamDeclarations(source, '.ts'), [
    { line: 3, seam: 'http', reason: 'guard unit test stubs the join-request probe' },
    { line: 7, seam: 'keycloak-provision', reason: 'no realm is bootable on this box' },
    { line: 11, seam: 'clock', reason: '' },
  ]);
});

test('html: comments are removed', () => {
  const source = ['<!-- the topbar -->', '<div class="topbar"></div>'].join('\n');
  const result = strip(source, '.html');
  assert.equal(result.output, '<div class="topbar"></div>');
});

test('css: a url containing a double slash is not a comment', () => {
  const source = 'a { background: url(https://cdn/x.png); /* tint */ }\n';
  const result = strip(source, '.css');
  assert.equal(result.output, 'a { background: url(https://cdn/x.png); }\n');
});

test('rename markers are classified separately from prose and directives', () => {
  const source = '// RENAME: spotLink -> externalSpotLinkUrl\n';
  const { comments } = scanComments(source, '.ts');
  assert.equal(classifyComment(source, comments[0]), 'rename-marker');
  const result = strip(source, '.ts');
  assert.equal(result.output, source);
});

test('scoring ranks a causal comment on an opaque name above a narration comment', () => {
  const causal = scoreComment({
    text: 'Chromium defaults to navigator.language=en-US, which would make the assertion trivially green.',
    attachedDeclaration: 'const ctx = await browser.newContext({ locale });',
  });
  const narration = scoreComment({ text: 'loop over items', attachedDeclaration: 'for (const aircraft of fleet) {' });
  assert.ok(causal > narration, `${causal} should exceed ${narration}`);
  assert.ok(causal >= 8, 'a causal comment on an opaque name should clear the review threshold');
});

test('a one-line reason for a bare literal outscores a longer comment that explains nothing', () => {
  const bareLiteral = scoreComment({
    text: 'Far beyond the 8 KB BufferedOutputStream + the 1000-row fetch window.',
    attachedDeclaration: 'private static final int ROWS = 5000;',
  });
  assert.ok(
    bareLiteral >= 8,
    `a magic number's only explanation should clear the review threshold, scored ${bareLiteral}`,
  );
  const longNarration = scoreComment({
    text: 'Builds the writer and then hands it to the caller so the caller can write rows to it one at a time.',
    attachedDeclaration: 'public BundleWriter openWriter(Path destination) {',
  });
  assert.ok(bareLiteral > longNarration, `${bareLiteral} should exceed ${longNarration}`);
});

test('a contiguous line-comment block becomes one manifest entry scored on its combined text', () => {
  const source = [
    '// AUTO_CLOSE_TARGET disabled: streamOne creates a JsonGenerator PER ROW wrapping the SAME',
    '// shared per-entity DigestOutputStream, so the generator close() must NOT close the',
    '// underlying stream, which would otherwise die at a buffer-boundary row (J-0c T-13).',
    'private static final JsonFactory JSON_FACTORY = builder().build();',
  ].join('\n');
  const result = strip(source, '.java');
  assert.equal(result.output, 'private static final JsonFactory JSON_FACTORY = builder().build();');
  assert.equal(result.removed.length, 1);
  assert.equal(result.removedCommentCount, 3);
  assert.deepEqual([result.removed[0].line, result.removed[0].endLine], [1, 3]);
  assert.match(result.removed[0].text, /AUTO_CLOSE_TARGET disabled[\s\S]*buffer-boundary row/);
  assert.ok(
    result.removed[0].score >= 8,
    `a block carrying the reason for a disabled flag should clear the review threshold, scored ${result.removed[0].score}`,
  );
});

test('a blank line between two line-comment runs keeps them as separate entries', () => {
  const source = ['// first run', '// still the first run', '', '// second run', 'const a = 1;'].join('\n');
  const result = strip(source, '.ts');
  assert.equal(result.output, ['', 'const a = 1;'].join('\n'));
  assert.deepEqual(
    result.removed.map((entry) => [entry.line, entry.endLine, entry.text]),
    [
      [1, 2, 'first run\nstill the first run'],
      [4, 4, 'second run'],
    ],
  );
});

test('a code line between two line-comment runs keeps them as separate entries', () => {
  const source = ['// before', 'const a = 1;', '// after', 'const b = 2;'].join('\n');
  const result = strip(source, '.ts');
  assert.equal(result.output, ['const a = 1;', 'const b = 2;'].join('\n'));
  assert.deepEqual(
    result.removed.map((entry) => entry.text),
    ['before', 'after'],
  );
});

test('a trailing comment is not folded into the block that follows it', () => {
  const source = ['const a = 1; // narrates the assignment', '// documents the next declaration', 'const b = 2;'].join('\n');
  const result = strip(source, '.ts');
  assert.deepEqual(
    result.removed.map((entry) => entry.text),
    ['narrates the assignment', 'documents the next declaration'],
  );
});

test('grouping applies to hash and dash line comments too', () => {
  const shell = strip(['# first', '# second', 'set -euo pipefail'].join('\n'), '.sh');
  assert.deepEqual(
    shell.removed.map((entry) => entry.text),
    ['first\nsecond'],
  );
  const sql = strip(['-- first', '-- second', 'CREATE INDEX ix_a ON a (b);'].join('\n'), '.sql');
  assert.deepEqual(
    sql.removed.map((entry) => entry.text),
    ['first\nsecond'],
  );
});

test('a directive inside a run splits the prose around it', () => {
  const source = [
    '// the console call below is deliberate',
    '// eslint-disable-next-line no-console',
    '// and the reason it is deliberate',
    'console.log(x);',
  ].join('\n');
  const result = strip(source, '.ts');
  assert.equal(result.output, ['// eslint-disable-next-line no-console', 'console.log(x);'].join('\n'));
  assert.deepEqual(
    result.removed.map((entry) => entry.text),
    ['the console call below is deliberate', 'and the reason it is deliberate'],
  );
});

test('prose that quotes a directive keyword mid-sentence is stripped, not mistaken for the directive', () => {
  const source = [
    '  // `contextLocale` boots the browser context at a NON-`en` navigator locale so',
    "  // the SPA's cold-start resolver genuinely starts non-English (AC2 locale",
    '  // proof). Chromium defaults to `navigator.language=en-US`→`en`, which would',
    "  // make a `<html lang>='en'` assertion trivially green regardless of DB state.",
    '  const context = await browser.newContext({ baseURL });',
  ].join('\n');
  const { comments } = scanComments(source, '.ts');
  assert.deepEqual(
    comments.map((comment) => classifyComment(source, comment)),
    ['prose', 'prose', 'prose', 'prose'],
    'a comment quoting `foo.language=bar` in prose is not an IntelliJ language-injection directive',
  );
  const result = strip(source, '.ts');
  assert.equal(result.output, '  const context = await browser.newContext({ baseURL });');
  assert.equal(result.removedCommentCount, 4);
  assert.equal(result.removed.length, 1);
  assert.equal(scanComments(result.output, '.ts').comments.length, 0);
});

test('a genuine language= injection directive still survives', () => {
  const source = ['// language=SQL', 'const query = "SELECT 1";', '/*language=HTML*/', 'const markup = "<p></p>";'].join('\n');
  const { comments } = scanComments(source, '.ts');
  assert.deepEqual(
    comments.map((comment) => classifyComment(source, comment)),
    ['directive', 'directive'],
  );
  assert.equal(strip(source, '.ts').output, source);
});

test('every tool-directive pattern is anchored at the start of the comment body', () => {
  for (const pattern of TOOL_DIRECTIVE_PATTERNS) {
    assert.ok(
      pattern.source.startsWith('^'),
      `${pattern} matches anywhere in a comment, so prose quoting it would be classified a directive and ` +
        'survive both the strip and --check',
    );
  }
});

test('a file with no comments is left byte-identical', () => {
  const source = 'public class A {\n    private final int b = 1;\n}\n';
  const result = strip(source, '.java');
  assert.equal(result.output, source);
  assert.equal(result.removed.length, 0);
});

test('the final newline is preserved when the last line is a comment', () => {
  const source = 'const a = 1;\n// trailing note\n';
  const result = strip(source, '.ts');
  assert.equal(result.output, 'const a = 1;\n');
});

test('the walk completes past a dangling symlink instead of throwing', () => {
  const collected = withTemporaryTree((root) => {
    writeFileSync(join(root, 'reachable.ts'), 'const a = 1;\n');
    symlinkSync(join(root, 'target-that-does-not-exist'), join(root, 'dangling-link'));
    symlinkSync('/absent-elsewhere/node_modules', join(root, 'dangling-directory-link'));
  });
  assert.deepEqual(collected, ['reachable.ts']);
});

test('the walk terminates on a symlink cycle and visits each file once', () => {
  const collected = withTemporaryTree((root) => {
    mkdirSync(join(root, 'src'));
    writeFileSync(join(root, 'src', 'once.ts'), 'const a = 1;\n');
    symlinkSync(root, join(root, 'src', 'loop-back-to-root'));
  });
  assert.deepEqual(collected, ['once.ts']);
});

test('the walk skips build output but not a source package that happens to be named build', () => {
  const collected = withTemporaryTree((root) => {
    mkdirSync(join(root, 'build'), { recursive: true });
    writeFileSync(join(root, 'build', 'Generated.java'), 'class Generated {}\n');
    mkdirSync(join(root, 'src', 'test', 'java', 'ch', 'alpenflight', 'build'), { recursive: true });
    writeFileSync(join(root, 'src', 'test', 'java', 'ch', 'alpenflight', 'build', 'ToolchainTest.java'), 'class ToolchainTest {}\n');
  });
  assert.deepEqual(collected, ['ToolchainTest.java']);
});

test('the walk skips dependency trees whatever suffix their directory name carries', () => {
  const collected = withTemporaryTree((root) => {
    writeFileSync(join(root, 'app.ts'), 'const a = 1;\n');
    for (const dependencyDirectory of ['node_modules', 'node_modules.windows', 'node_modules_sandbox']) {
      mkdirSync(join(root, dependencyDirectory));
      writeFileSync(join(root, dependencyDirectory, 'vendor.ts'), 'const b = 2;\n');
    }
  });
  assert.deepEqual(collected, ['app.ts']);
});
