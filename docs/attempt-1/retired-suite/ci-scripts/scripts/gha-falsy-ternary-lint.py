#!/usr/bin/env python3
"""Reject GitHub Actions ternaries whose then-branch is a falsy literal.

`${{ cond && '' || x }}` always yields `x`: GHA `&&` returns its right operand
only when that operand is truthy, and `''`/`false`/`0`/`null` are falsy, so the
`||` fallback swallows the intended branch. A possibly-empty value therefore has
to sit on the `||` side.
"""

from __future__ import annotations

import glob
import re
import sys

FALSY_THEN = re.compile(
    r"&&\s*(?:''|\"\"|false|null|-?0)(?![\w.\-])\s*(?:\|\||$)",
    re.MULTILINE,
)
INTERPOLATION = re.compile(r"\$\{\{(.*?)\}\}", re.DOTALL)
IF_KEY = re.compile(r"^(\s*)if:[ \t]*(\S.*)$")
BLOCK_SCALAR = re.compile(r"^[|>][+-]?$")

BLOCK_LINE = re.compile(r"^(\s*)(\S.*)$")


def _expressions(src: str):
    """Yield (offset, expression-text) for every GHA expression context."""
    for match in INTERPOLATION.finditer(src):
        yield match.start(1), match.group(1)

    lines = src.splitlines(keepends=True)
    offsets, cursor = [], 0
    for line in lines:
        offsets.append(cursor)
        cursor += len(line)

    index = 0
    while index < len(lines):
        key = IF_KEY.match(lines[index].rstrip("\n"))
        index += 1
        if not key:
            continue
        indent, value = key.group(1), key.group(2)
        if "${{" in value:
            continue
        if not BLOCK_SCALAR.match(value):
            yield offsets[index - 1], value
            continue
        start = index
        folded = []
        while index < len(lines):
            body = BLOCK_LINE.match(lines[index].rstrip("\n"))
            if body and len(body.group(1)) <= len(indent):
                break
            folded.append(lines[index])
            index += 1
        yield offsets[start], "".join(folded)


def violations(src: str, path: str):
    found = []
    for offset, expression in _expressions(src):
        for hit in FALSY_THEN.finditer(expression):
            line = src.count("\n", 0, offset + hit.start()) + 1
            found.append((path, line, " ".join(expression.split())[:200]))
    return found


BAD_SAMPLE = """
jobs:
  a:
    steps:
      - env:
          X: ${{ github.ref == 'refs/heads/main' && '' || needs.x.outputs.j }}
          Y: ${{ inputs.skip && false || 'run' }}
        if: steps.a.outcome == 'success' && 0 || always()
"""

GOOD_SAMPLE = """
jobs:
  a:
    steps:
      - env:
          X: ${{ github.ref != 'refs/heads/main' && needs.x.outputs.j || '' }}
          Y: ${{ inputs.skip && 'skip' || 'run' }}
        if: ${{ !cancelled() && steps.a.outcome == 'success' }}
        run: docker network rm shared || true
"""


def selftest() -> int:
    bad = violations(BAD_SAMPLE, "<bad>")
    good = violations(GOOD_SAMPLE, "<good>")
    if len(bad) != 3:
        print(f"selftest: expected 3 detections, got {len(bad)}: {bad}", file=sys.stderr)
        return 1
    if good:
        print(f"selftest: false positive on correct expressions: {good}", file=sys.stderr)
        return 1
    print("selftest: ok (3 falsy then-branches detected, 0 false positives)")
    return 0


def main(argv: list[str]) -> int:
    if "--selftest" in argv:
        return selftest()
    paths = [a for a in argv if not a.startswith("-")] or sorted(
        glob.glob(".github/workflows/*.yml") + glob.glob(".github/workflows/*.yaml")
    )
    if not paths:
        print("no workflow files to scan", file=sys.stderr)
        return 1
    found = []
    for path in paths:
        with open(path, encoding="utf-8") as handle:
            found.extend(violations(handle.read(), path))
    for path, line, expression in found:
        print(f"{path}:{line}: falsy then-branch is unreachable: {expression}", file=sys.stderr)
    if found:
        print(
            f"\n{len(found)} unreachable ternary branch(es). "
            "Invert the condition so the possibly-empty value sits after `||`.",
            file=sys.stderr,
        )
        return 1
    print(f"gha-falsy-ternary-lint: {len(paths)} workflow file(s) clean")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
