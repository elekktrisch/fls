#!/usr/bin/env python3

from __future__ import annotations

import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

CI_WORKFLOW = Path(".github/workflows/ci.yml")
AGGREGATION_STEP_NAME = "Check upstream job results"
AGGREGATOR_JOB_ID = "required"

FALSY_VALUES = ("", "false", "0")

PRE_FIX_AGGREGATION_THAT_SCORED_EVERY_SKIP_AS_SUCCESS = """
fail=0
for pair in \
    "changes:$R_CHANGES" \
    "alpenflight-build-server:$R_NEXT_SERVER" \
    "alpenflight-build-web:$R_NEXT_WEB" \
    "alpenflight-auth-realm-shape:$R_AUTH" \
    "alpenflight-proof:$R_PROOF" \
    "alpenflight-profile-proof:$R_PROFILE_PROOF" \
    "alpenflight-mock-e2e-merge:$R_MOCK_E2E" ; do
  name="${pair%%:*}"
  result="${pair##*:}"
  case "$result" in
    success|skipped) printf "  %-28s %s\\n" "$name" "$result" ;;
    *)               printf "  %-28s %s  <-- failing\\n" "$name" "$result"; fail=1 ;;
  esac
done
[ "$fail" = "0" ]
"""


class GithubExpression:
    TOKEN = re.compile(
        r"\s*(\(|\)|&&|\|\||==|!=|!|'(?:[^']|'')*'|[A-Za-z_][A-Za-z0-9_.\-]*(?:\(\))?)"
    )

    def __init__(self, text: str, context: dict):
        self.text = text
        self.context = context
        self.tokens = self._tokenize(text)
        self.position = 0

    @classmethod
    def _tokenize(cls, text: str) -> list[str]:
        tokens, cursor = [], 0
        while cursor < len(text):
            match = cls.TOKEN.match(text, cursor)
            if not match:
                if text[cursor:].strip() == "":
                    break
                raise ValueError(f"cannot tokenize {text[cursor:]!r} in {text!r}")
            tokens.append(match.group(1))
            cursor = match.end()
        return tokens

    def evaluate(self):
        value = self._or_expression()
        if self.position != len(self.tokens):
            raise ValueError(f"trailing tokens in {self.text!r}: {self.tokens[self.position:]}")
        return value

    def _peek(self):
        return self.tokens[self.position] if self.position < len(self.tokens) else None

    def _take(self, token: str) -> bool:
        if self._peek() == token:
            self.position += 1
            return True
        return False

    def _or_expression(self):
        value = self._and_expression()
        while self._take("||"):
            right = self._and_expression()
            value = value if truthy(value) else right
        return value

    def _and_expression(self):
        value = self._comparison()
        while self._take("&&"):
            right = self._comparison()
            value = right if truthy(value) else value
        return value

    def _comparison(self):
        left = self._unary()
        for operator in ("==", "!="):
            if self._take(operator):
                right = self._unary()
                equal = left == right
                return equal if operator == "==" else not equal
        return left

    def _unary(self):
        if self._take("!"):
            return not truthy(self._unary())
        return self._primary()

    def _primary(self):
        token = self._peek()
        if token is None:
            raise ValueError(f"unexpected end of {self.text!r}")
        self.position += 1
        if token == "(":
            value = self._or_expression()
            if not self._take(")"):
                raise ValueError(f"missing ')' in {self.text!r}")
            return value
        if token.startswith("'"):
            return token[1:-1].replace("''", "'")
        if token == "always()":
            return True
        if token in ("true", "false"):
            return token == "true"
        if token.endswith("()"):
            raise ValueError(f"unsupported function {token} in {self.text!r}")
        return self._resolve_path(token)

    def _resolve_path(self, path: str):
        value = self.context
        for segment in path.split("."):
            if not isinstance(value, dict) or segment not in value:
                return ""
            value = value[segment]
        return value


def truthy(value) -> bool:
    if isinstance(value, bool):
        return value
    if value is None:
        return False
    return str(value).lower() not in FALSY_VALUES


def interpolate(raw: str, context: dict) -> str:
    def substitute(match: re.Match) -> str:
        value = GithubExpression(match.group(1), context).evaluate()
        if isinstance(value, bool):
            return "true" if value else "false"
        return "" if value is None else str(value)

    return re.sub(r"\$\{\{(.*?)\}\}", substitute, raw, flags=re.DOTALL).strip()


def indent_of(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


def block_below(lines: list[str], start: int, outer_indent: int) -> list[str]:
    body, index = [], start
    while index < len(lines):
        line = lines[index]
        if line.strip() and indent_of(line) <= outer_indent:
            break
        body.append(line)
        index += 1
    return body


def job_blocks(lines: list[str]) -> dict[str, list[str]]:
    blocks, index = {}, 0
    inside_jobs = False
    while index < len(lines):
        line = lines[index]
        if line.rstrip() == "jobs:":
            inside_jobs = True
            index += 1
            continue
        header = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", line)
        if inside_jobs and header:
            blocks[header.group(1)] = block_below(lines, index + 1, 2)
        index += 1
    return blocks


def scalar_or_block(block: list[str], key: str) -> str | None:
    for index, line in enumerate(block):
        match = re.match(rf"^(\s*){key}:\s*(.*)$", line)
        if not match or indent_of(line) != 4:
            continue
        value = match.group(2).strip()
        if value in (">-", ">", "|", "|-"):
            return " ".join(
                folded.strip() for folded in block_below(block, index + 1, 4) if folded.strip()
            )
        return value
    return None


def needs_of(block: list[str]) -> list[str]:
    raw = scalar_or_block(block, "needs")
    if raw is None:
        return []
    return [item.strip() for item in raw.strip("[]").split(",") if item.strip()]


def aggregation_step(block: list[str]) -> tuple[dict[str, str], str]:
    for index, line in enumerate(block):
        if line.strip() == f"- name: {AGGREGATION_STEP_NAME}":
            step = block_below(block, index + 1, indent_of(line))
            return step_env(step), step_run(step)
    raise SystemExit(f"::error::{CI_WORKFLOW} has no step named {AGGREGATION_STEP_NAME!r}")


def step_env(step: list[str]) -> dict[str, str]:
    for index, line in enumerate(step):
        if line.strip() == "env:":
            env = {}
            for entry in block_below(step, index + 1, indent_of(line)):
                pair = re.match(r"^\s*([A-Z0-9_]+):\s*(.*)$", entry)
                if pair:
                    env[pair.group(1)] = pair.group(2).strip()
            return env
    return {}


def step_run(step: list[str]) -> str:
    for index, line in enumerate(step):
        if re.match(r"^\s*run:\s*\|\s*$", line):
            body = block_below(step, index + 1, indent_of(line))
            margin = min((indent_of(l) for l in body if l.strip()), default=0)
            return "\n".join(l[margin:] for l in body)
    raise SystemExit(f"::error::step {AGGREGATION_STEP_NAME!r} has no literal `run:` block")


@dataclass
class Scenario:
    name: str
    event_name: str
    changes_outputs: dict[str, str]
    job_results: dict[str, str]
    expect_required_red: bool
    changes_result: str = "success"
    permits_pre_fix_gate_to_pass: bool = field(default=False)


LANE_LIVE = {"next": "true", "docs_only": "false", "troubleshooting": "false"}
LANE_DOCS_ONLY = {"next": "true", "docs_only": "true", "troubleshooting": "false"}
LANE_NO_NEXT = {"next": "false", "docs_only": "false", "troubleshooting": "false"}

SCENARIOS = [
    Scenario("live lane, every required job green", "pull_request", LANE_LIVE, {}, False),
    Scenario(
        "live lane, alpenflight-profile-proof FAILED",
        "pull_request",
        LANE_LIVE,
        {"alpenflight-profile-proof": "failure"},
        True,
    ),
    Scenario(
        "live lane, alpenflight-profile-proof SKIPPED",
        "pull_request",
        LANE_LIVE,
        {"alpenflight-profile-proof": "skipped"},
        True,
        permits_pre_fix_gate_to_pass=True,
    ),
    Scenario(
        "live lane, alpenflight-proof SKIPPED",
        "pull_request",
        LANE_LIVE,
        {"alpenflight-proof": "skipped"},
        True,
        permits_pre_fix_gate_to_pass=True,
    ),
    Scenario(
        "live lane, alpenflight-proof FAILED",
        "pull_request",
        LANE_LIVE,
        {"alpenflight-proof": "failure"},
        True,
    ),
    Scenario(
        "live lane, alpenflight-build-server FAILED",
        "pull_request",
        LANE_LIVE,
        {"next-build-server": "failure"},
        True,
    ),
    Scenario(
        "live lane, a required job CANCELLED",
        "pull_request",
        LANE_LIVE,
        {"alpenflight-profile-proof": "cancelled"},
        True,
    ),
    Scenario("docs-only push stands the heavy lane down", "pull_request", LANE_DOCS_ONLY, {}, False),
    Scenario("no alpenflight change stands the heavy lane down", "pull_request", LANE_NO_NEXT, {}, False),
    Scenario("main push, mock-e2e is pull-request-only", "push", LANE_LIVE, {}, False),
    Scenario(
        "main push, alpenflight-profile-proof SKIPPED",
        "push",
        LANE_LIVE,
        {"alpenflight-profile-proof": "skipped"},
        True,
        permits_pre_fix_gate_to_pass=True,
    ),
    Scenario("detect changes FAILED", "pull_request", LANE_LIVE, {}, True, changes_result="failure"),
    Scenario(
        "detect changes SKIPPED",
        "pull_request",
        LANE_LIVE,
        {},
        True,
        changes_result="skipped",
        permits_pre_fix_gate_to_pass=True,
    ),
]


def simulate(
    scenario: Scenario, blocks: dict[str, list[str]], required_needs: list[str]
) -> tuple[dict[str, str], dict]:
    context = {
        "github": {"event_name": scenario.event_name},
        "needs": {
            job_id: {"result": scenario.job_results.get(job_id, "success")} for job_id in blocks
        },
    }
    context["needs"]["changes"] = {
        "result": scenario.changes_result,
        "outputs": scenario.changes_outputs,
    }
    results = {"changes": scenario.changes_result}
    for job_id in required_needs:
        if job_id == "changes":
            continue
        condition = scalar_or_block(blocks[job_id], "if")
        runs = True if condition is None else truthy(GithubExpression(condition, context).evaluate())
        forced = scenario.job_results.get(job_id)
        results[job_id] = forced if forced else ("success" if runs else "skipped")
        context["needs"][job_id] = {"result": results[job_id]}
    return results, context


def run_gate(body: str, env: dict[str, str]) -> tuple[bool, str]:
    completed = subprocess.run(
        ["bash", "-c", body], env={"PATH": "/usr/bin:/bin", **env}, capture_output=True, text=True
    )
    return completed.returncode == 0, completed.stdout + completed.stderr


def main() -> int:
    if not CI_WORKFLOW.is_file():
        print(f"::error::run this from the repository root; {CI_WORKFLOW} not found", file=sys.stderr)
        return 1
    lines = CI_WORKFLOW.read_text().splitlines()
    blocks = job_blocks(lines)
    required_needs = needs_of(blocks[AGGREGATOR_JOB_ID])
    env_expressions, body = aggregation_step(blocks[AGGREGATOR_JOB_ID])

    failures = []
    live_lane_context = simulate(SCENARIOS[0], blocks, required_needs)[1]
    for job_id in required_needs:
        if job_id == "changes":
            continue
        condition = scalar_or_block(blocks[job_id], "if")
        if condition is not None and not truthy(GithubExpression(condition, live_lane_context).evaluate()):
            failures.append(
                f"{job_id} is in `{AGGREGATOR_JOB_ID}.needs` but its `if:` is false on a live lane "
                f"(pull_request, next=true, docs_only=false), so it gates nothing: {condition}"
            )

    for scenario in SCENARIOS:
        results, context = simulate(scenario, blocks, required_needs)
        env = {name: interpolate(raw, context) for name, raw in env_expressions.items()}
        passed, output = run_gate(body, env)
        went_red = not passed
        verdict = "red" if went_red else "green"
        if went_red != scenario.expect_required_red:
            failures.append(
                f"{scenario.name}: required went {verdict}, expected "
                f"{'red' if scenario.expect_required_red else 'green'}\n{output}"
            )
            continue
        print(f"  ok    {scenario.name:<52} required={verdict}")

        pre_fix_passed, _ = run_gate(PRE_FIX_AGGREGATION_THAT_SCORED_EVERY_SKIP_AS_SUCCESS, env)
        if scenario.permits_pre_fix_gate_to_pass and not pre_fix_passed:
            failures.append(
                f"{scenario.name}: the pre-fix aggregation was expected to score this input class "
                "as success; the scenario no longer proves the class was uncovered"
            )
        if not scenario.permits_pre_fix_gate_to_pass and pre_fix_passed != (not scenario.expect_required_red):
            failures.append(f"{scenario.name}: the pre-fix aggregation disagrees outside the skip class")

    if failures:
        print("::error title=required-gate selftest failed::" + failures[0].splitlines()[0], file=sys.stderr)
        for failure in failures:
            print(f"  FAIL  {failure}", file=sys.stderr)
        return 1
    print(
        f"required-gate selftest: ok ({len(SCENARIOS)} input classes; every job in "
        f"`{AGGREGATOR_JOB_ID}.needs` runs on a live lane)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
