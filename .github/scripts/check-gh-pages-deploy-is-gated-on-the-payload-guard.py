#!/usr/bin/env python3

import argparse
import re
import sys
from pathlib import Path

WORKFLOW_THAT_PUBLISHES_THE_PROOF_GALLERY = ".github/workflows/ci.yml"
GH_PAGES_DEPLOY_ACTION = "peaceiris/actions-gh-pages"
PAYLOAD_SIZE_GUARD_SCRIPT = "check-gh-pages-payload-size.py"
SELFTEST_FLAG = "--selftest"

JOB_HEADER = re.compile(r"^  ([A-Za-z0-9_-]+):\s*$")
STEP_HEADER = re.compile(r"^      - (\S.*)$")
STEP_KEY = re.compile(r"^        [A-Za-z_-]+:")
KEYS_THIS_RULE_READS = ("id", "if", "uses")
INTERPOLATION = re.compile(r"^\$\{\{(.*)\}\}$", re.DOTALL)
TOKEN = re.compile(
    r"\s*(&&|\|\||==|!=|!|\(|\)|,|'(?:[^']|'')*'|[A-Za-z_][A-Za-z0-9_.-]*|-?\d+(?:\.\d+)?)"
)
NUMBER = re.compile(r"^-?\d+(?:\.\d+)?$")

GITHUB_CONTEXT_OF_A_PUSH_TO_MAIN = {
    "ref": "refs/heads/main",
    "event_name": "push",
    "head_ref": "",
    "event": {},
}
GITHUB_CONTEXT_OF_A_PULL_REQUEST = {
    "ref": "refs/pull/7/merge",
    "event_name": "pull_request",
    "head_ref": "integration/J-99",
    "event": {"pull_request": {"head": {"repo": {"fork": False}}}},
}
PUBLISH_CONTEXTS = (
    ("a push to main", GITHUB_CONTEXT_OF_A_PUSH_TO_MAIN),
    ("a pull request", GITHUB_CONTEXT_OF_A_PULL_REQUEST),
)


def truthy(value) -> bool:
    if value is None:
        return False
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    return value != ""


def as_number(value) -> float:
    if value is None:
        return 0.0
    if isinstance(value, bool):
        return 1.0 if value else 0.0
    if isinstance(value, (int, float)):
        return float(value)
    if value == "":
        return 0.0
    try:
        return float(value)
    except ValueError:
        return float("nan")


def loosely_equal(left, right) -> bool:
    if isinstance(left, str) and isinstance(right, str):
        return left == right
    if isinstance(left, bool) and isinstance(right, bool):
        return left == right
    return as_number(left) == as_number(right)


class StepOutcomesAJobReportsToItsConditions:
    def __init__(self, outcome_of_each_step, github, cancelled, a_previous_step_failed):
        self.contexts = {
            "steps": {
                step_id: {"outcome": outcome, "conclusion": outcome}
                for step_id, outcome in outcome_of_each_step.items()
            },
            "github": github,
            "needs": {},
            "env": {},
            "inputs": {},
        }
        self.cancelled = cancelled
        self.a_previous_step_failed = a_previous_step_failed

    def context_value(self, path: str):
        cursor = self.contexts
        for part in path.split("."):
            if not isinstance(cursor, dict) or part not in cursor:
                return None
            cursor = cursor[part]
        return cursor

    def function_value(self, name: str):
        if name == "always":
            return True
        if name == "cancelled":
            return self.cancelled
        if name == "success":
            return not self.cancelled and not self.a_previous_step_failed
        if name == "failure":
            return not self.cancelled and self.a_previous_step_failed
        raise SystemExit(
            f"FAIL: this rule cannot evaluate the function {name}(). Add it to "
            f"{Path(__file__).name}, or the rule reports a deploy condition it never read."
        )


class ConditionAsGithubActionsEvaluatesIt:
    def __init__(self, condition: str, state: StepOutcomesAJobReportsToItsConditions):
        interpolated = INTERPOLATION.match(condition.strip())
        expression = interpolated.group(1) if interpolated else condition
        self.tokens = TOKEN.findall(expression)
        self.position = 0
        self.state = state

    def value(self):
        result = self.any_of()
        if self.position != len(self.tokens):
            raise SystemExit(
                f"FAIL: this rule stopped at token {self.position} of {len(self.tokens)} in a "
                f"deploy condition. Extend {Path(__file__).name} to read it."
            )
        return result

    def peek(self):
        return self.tokens[self.position] if self.position < len(self.tokens) else None

    def take(self, expected: str | None = None) -> str:
        token = self.peek()
        if token is None or (expected is not None and token != expected):
            raise SystemExit(
                f"FAIL: this rule expected {expected or 'a token'} in a deploy condition and "
                f"found {token}."
            )
        self.position += 1
        return token

    def any_of(self):
        value = self.all_of()
        while self.peek() == "||":
            self.take()
            alternative = self.all_of()
            value = value if truthy(value) else alternative
        return value

    def all_of(self):
        value = self.compared()
        while self.peek() == "&&":
            self.take()
            other = self.compared()
            value = other if truthy(value) else value
        return value

    def compared(self):
        left = self.negated()
        while self.peek() in ("==", "!="):
            operator = self.take()
            equal = loosely_equal(left, self.negated())
            left = equal if operator == "==" else not equal
        return left

    def negated(self):
        if self.peek() == "!":
            self.take()
            return not truthy(self.negated())
        return self.term()

    def term(self):
        token = self.take()
        if token == "(":
            value = self.any_of()
            self.take(")")
            return value
        if token.startswith("'"):
            return token[1:-1].replace("''", "'")
        if NUMBER.match(token):
            return float(token)
        if token in ("true", "false"):
            return token == "true"
        if token == "null":
            return None
        if self.peek() == "(":
            self.take("(")
            self.take(")")
            return self.state.function_value(token)
        return self.state.context_value(token)


def step_runs(condition: str, state: StepOutcomesAJobReportsToItsConditions) -> bool:
    return truthy(ConditionAsGithubActionsEvaluatesIt(condition, state).value())


def read_step_key(step: dict, text: str) -> None:
    key, separator, value = text.partition(":")
    if separator and key in KEYS_THIS_RULE_READS and key not in step:
        step[key] = value.strip()


def steps_of_each_job(workflow_text: str) -> dict[str, list[dict]]:
    jobs: dict[str, list[dict]] = {}
    job: str | None = None
    step: dict | None = None
    reached_the_jobs_block = False
    for number, line in enumerate(workflow_text.splitlines(), start=1):
        if line.rstrip() == "jobs:":
            reached_the_jobs_block = True
            continue
        if not reached_the_jobs_block:
            continue
        header = JOB_HEADER.match(line)
        if header:
            job = header.group(1)
            jobs[job] = []
            step = None
            continue
        if job is None:
            continue
        started = STEP_HEADER.match(line)
        if started:
            step = {"job": job, "line": number, "body": [line]}
            jobs[job].append(step)
            read_step_key(step, started.group(1))
            continue
        if step is None:
            continue
        if line.strip() and not line.startswith("        "):
            step = None
            continue
        step["body"].append(line)
        if STEP_KEY.match(line):
            read_step_key(step, line.strip())
    return jobs


def step_asserts_the_payload_size(step: dict) -> bool:
    body = "\n".join(step["body"])
    return PAYLOAD_SIZE_GUARD_SCRIPT in body and SELFTEST_FLAG not in body


def step_publishes_to_gh_pages(step: dict) -> bool:
    return GH_PAGES_DEPLOY_ACTION in step.get("uses", "")


def outcomes_where(step_ids: set[str], failing: set[str]) -> dict[str, str]:
    return {
        step_id: "failure" if step_id in failing else "success" for step_id in step_ids | failing
    }


def violations_of_one_deploy(
    deploy: dict, guard_id: str, step_ids: set[str], where: str
) -> list[str]:
    at = f"{where}:{deploy['line']}"
    if "if" not in deploy:
        return [
            f"{at}: the gh-pages deploy step carries no `if:`, so it publishes even when the "
            f"payload-size guard reds. Gate it on steps.{guard_id}.outcome == 'success'."
        ]
    condition = deploy["if"]
    found: list[str] = []

    for situation, github in PUBLISH_CONTEXTS:
        after_a_red_guard = StepOutcomesAJobReportsToItsConditions(
            outcomes_where(step_ids, {guard_id}), github, False, True
        )
        if step_runs(condition, after_a_red_guard):
            found.append(
                f"{at}: the gh-pages deploy runs on {situation} after the payload-size guard "
                f"({guard_id}) failed. `!cancelled()` is TRUE after a failed step, so the guard "
                f"changes the log and not the outcome. Add "
                f"steps.{guard_id}.outcome == 'success' to the condition."
            )
        after_a_cancelled_run = StepOutcomesAJobReportsToItsConditions(
            outcomes_where(step_ids, set()), github, True, False
        )
        if step_runs(condition, after_a_cancelled_run):
            found.append(
                f"{at}: the gh-pages deploy runs on {situation} after the run was cancelled. "
                f"Keep `!cancelled()` in the condition."
            )

    publishes_a_red_proof_run = [
        situation
        for situation, github in PUBLISH_CONTEXTS
        if step_runs(
            condition,
            StepOutcomesAJobReportsToItsConditions(
                outcomes_where(step_ids, set()), github, False, True
            ),
        )
    ]
    if not publishes_a_red_proof_run:
        found.append(
            f"{at}: the gh-pages deploy is skipped when a proof test case reds, on a push to "
            f"main and on a pull request. The operator reads the gallery to see the red run, so "
            f"the page must still publish. Use `!cancelled()` and not `success()`, and gate only "
            f"on steps.{guard_id}.outcome == 'success'."
        )
    return found


def violations_in_job(job: str, steps: list[dict], where: str) -> list[str]:
    deploys = [step for step in steps if step_publishes_to_gh_pages(step)]
    if not deploys:
        return []
    guards = [step for step in steps if step_asserts_the_payload_size(step)]
    if not guards:
        return [
            f"{where}:{deploys[0]['line']}: the job {job} publishes to gh-pages and runs no "
            f"{PAYLOAD_SIZE_GUARD_SCRIPT}. Over the threshold GitHub Pages errors the build, the "
            f"CDN keeps the previous copy, and every deployed-page guard then reads a stale page."
        ]
    guard = guards[0]
    if "id" not in guard:
        return [
            f"{where}:{guard['line']}: the {PAYLOAD_SIZE_GUARD_SCRIPT} step of the job {job} "
            f"carries no `id:`, so no deploy step can read its result. Give the step an id."
        ]
    step_ids = {step["id"] for step in steps if "id" in step}
    found: list[str] = []
    for deploy in deploys:
        found.extend(violations_of_one_deploy(deploy, guard["id"], step_ids, where))
    return found


def jobs_that_pair_a_guard_with_a_deploy(jobs: dict[str, list[dict]]) -> list[str]:
    return [
        job
        for job, steps in jobs.items()
        if any(step_publishes_to_gh_pages(step) for step in steps)
        and any(step_asserts_the_payload_size(step) for step in steps)
    ]


def violations(workflow_text: str, where: str) -> list[str]:
    jobs = steps_of_each_job(workflow_text)
    found: list[str] = []
    for job, steps in jobs.items():
        found.extend(violations_in_job(job, steps, where))
    if not found and not jobs_that_pair_a_guard_with_a_deploy(jobs):
        found.append(
            f"{where}: no job pairs a gh-pages deploy with {PAYLOAD_SIZE_GUARD_SCRIPT}, so this "
            f"rule read nothing and reports green for a workflow it never gated."
        )
    return found


CONDITION_THAT_PUBLISHES_AFTER_A_RED_GUARD = (
    "${{ !cancelled() && steps.gallery.outcome == 'success' "
    "&& github.ref == 'refs/heads/main' }}"
)
CONDITION_THAT_SKIPS_THE_PUBLISH_ON_A_RED_TEST_CASE = (
    "${{ success() && steps.gallery.outcome == 'success' "
    "&& steps.payload_size_guard.outcome == 'success' }}"
)
CONDITION_THAT_PUBLISHES_A_CANCELLED_RUN = (
    "${{ always() && steps.gallery.outcome == 'success' "
    "&& steps.payload_size_guard.outcome == 'success' }}"
)
CONDITION_THAT_GATES_THE_DEPLOY_CORRECTLY = (
    "${{ !cancelled() && steps.gallery.outcome == 'success' "
    "&& steps.payload_size_guard.outcome == 'success' }}"
)


def a_workflow_whose_deploy_carries(condition: str | None, guard_id: str | None) -> str:
    identified = f"        id: {guard_id}\n" if guard_id else ""
    gated = f"        if: {condition}\n" if condition else ""
    return (
        "jobs:\n"
        "  alpenflight-proof:\n"
        "    runs-on: ubuntu-22.04\n"
        "    steps:\n"
        "      - name: Build the proof gallery\n"
        "        id: gallery\n"
        "        run: node e2e/proof-gallery/generate-gallery.mjs\n"
        "      - name: Payload-size guard\n"
        f"{identified}"
        "        if: ${{ !cancelled() && steps.gallery.outcome == 'success' }}\n"
        f"        run: python3 .github/scripts/{PAYLOAD_SIZE_GUARD_SCRIPT}\n"
        "      - name: Deploy proof gallery to gh-pages\n"
        f"{gated}"
        f"        uses: {GH_PAGES_DEPLOY_ACTION}@v4\n"
        "        with:\n"
        "          publish_branch: gh-pages\n"
    )


def selftest_the_condition_that_ships_today_passes() -> None:
    found = violations(
        a_workflow_whose_deploy_carries(
            CONDITION_THAT_GATES_THE_DEPLOY_CORRECTLY, "payload_size_guard"
        ),
        "<selftest>",
    )
    assert not found, f"selftest: the rule rejected a correctly gated deploy: {found}"


def selftest_a_deploy_that_publishes_after_a_red_payload_guard_reds() -> None:
    found = violations(
        a_workflow_whose_deploy_carries(
            CONDITION_THAT_PUBLISHES_AFTER_A_RED_GUARD, "payload_size_guard"
        ),
        "<selftest>",
    )
    assert any("after the payload-size guard" in violation for violation in found), (
        f"selftest: the rule passed a deploy that publishes after the payload-size guard "
        f"failed — the exact condition that shipped on main: {found}"
    )


def selftest_a_deploy_that_skips_a_red_proof_run_reds() -> None:
    found = violations(
        a_workflow_whose_deploy_carries(
            CONDITION_THAT_SKIPS_THE_PUBLISH_ON_A_RED_TEST_CASE, "payload_size_guard"
        ),
        "<selftest>",
    )
    assert any("when a proof test case reds" in violation for violation in found), (
        f"selftest: the rule passed a deploy that hides the gallery of a red proof run: {found}"
    )


def selftest_a_deploy_that_publishes_a_cancelled_run_reds() -> None:
    found = violations(
        a_workflow_whose_deploy_carries(
            CONDITION_THAT_PUBLISHES_A_CANCELLED_RUN, "payload_size_guard"
        ),
        "<selftest>",
    )
    assert any("after the run was cancelled" in violation for violation in found), (
        f"selftest: the rule passed a deploy that publishes a cancelled run: {found}"
    )


def selftest_a_deploy_without_a_condition_reds() -> None:
    found = violations(
        a_workflow_whose_deploy_carries(None, "payload_size_guard"), "<selftest>"
    )
    assert any("carries no `if:`" in violation for violation in found), (
        f"selftest: the rule passed a deploy that publishes unconditionally: {found}"
    )


def selftest_a_payload_guard_without_an_id_reds() -> None:
    found = violations(
        a_workflow_whose_deploy_carries(CONDITION_THAT_GATES_THE_DEPLOY_CORRECTLY, None),
        "<selftest>",
    )
    assert any("carries no `id:`" in violation for violation in found), (
        f"selftest: the rule passed a payload-size guard no deploy can read: {found}"
    )


def selftest_a_deploy_in_a_job_without_a_payload_guard_reds() -> None:
    without_a_guard = a_workflow_whose_deploy_carries(
        CONDITION_THAT_GATES_THE_DEPLOY_CORRECTLY, "payload_size_guard"
    ).replace(
        f"        run: python3 .github/scripts/{PAYLOAD_SIZE_GUARD_SCRIPT}\n",
        "        run: true\n",
    )
    found = violations(without_a_guard, "<selftest>")
    assert any("runs no check-gh-pages-payload-size.py" in violation for violation in found), (
        f"selftest: the rule passed a job that publishes to gh-pages with no payload-size "
        f"guard: {found}"
    )


def selftest_a_workflow_the_rule_reads_nothing_in_reds() -> None:
    found = violations("jobs:\n  build:\n    steps:\n      - run: true\n", "<selftest>")
    assert any("read nothing" in violation for violation in found), (
        f"selftest: the rule reported green for a workflow it never gated: {found}"
    )


def selftest_the_selftest_invocation_never_counts_as_the_guard() -> None:
    with_only_a_selftest = a_workflow_whose_deploy_carries(
        CONDITION_THAT_GATES_THE_DEPLOY_CORRECTLY, "payload_size_guard"
    ).replace(
        f"        run: python3 .github/scripts/{PAYLOAD_SIZE_GUARD_SCRIPT}\n",
        f"        run: python3 .github/scripts/{PAYLOAD_SIZE_GUARD_SCRIPT} {SELFTEST_FLAG}\n",
    )
    found = violations(with_only_a_selftest, "<selftest>")
    assert any("runs no check-gh-pages-payload-size.py" in violation for violation in found), (
        f"selftest: the rule counted a {SELFTEST_FLAG} run as the guard that measures the real "
        f"payload: {found}"
    )


def run_selftest_so_an_ungated_deploy_cannot_report_green() -> None:
    selftest_the_condition_that_ships_today_passes()
    selftest_a_deploy_that_publishes_after_a_red_payload_guard_reds()
    selftest_a_deploy_that_skips_a_red_proof_run_reds()
    selftest_a_deploy_that_publishes_a_cancelled_run_reds()
    selftest_a_deploy_without_a_condition_reds()
    selftest_a_payload_guard_without_an_id_reds()
    selftest_a_deploy_in_a_job_without_a_payload_guard_reds()
    selftest_a_workflow_the_rule_reads_nothing_in_reds()
    selftest_the_selftest_invocation_never_counts_as_the_guard()
    print("PASS")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--workflow", default=WORKFLOW_THAT_PUBLISHES_THE_PROOF_GALLERY)
    parser.add_argument("--selftest", action="store_true")
    arguments = parser.parse_args()

    if arguments.selftest:
        run_selftest_so_an_ungated_deploy_cannot_report_green()
        return 0

    workflow = Path(arguments.workflow)
    found = violations(workflow.read_text(encoding="utf8"), str(workflow))
    for violation in found:
        print(violation, file=sys.stderr)
    if found:
        print(
            f"\n{len(found)} gh-pages deploy(s) that the payload-size guard cannot stop.",
            file=sys.stderr,
        )
        return 1
    print(
        f"gh-pages deploy gating: every deploy in {workflow} waits for the payload-size guard, "
        f"and still publishes the gallery of a red proof run"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
