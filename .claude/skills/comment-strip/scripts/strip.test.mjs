import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { basename, join } from 'node:path';
import { tmpdir } from 'node:os';
import { stripSource, scanComments, classifyComment, scoreComment, collectSourceFiles } from './strip.mjs';

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

test('shell: heredoc bodies are opaque', () => {
  const source = ['cat <<EOF', '# not a comment', 'body', 'EOF', '# a comment'].join('\n');
  const result = strip(source, '.sh');
  assert.equal(result.output, ['cat <<EOF', '# not a comment', 'body', 'EOF', ''].join('\n'));
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
