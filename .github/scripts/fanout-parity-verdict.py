#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass, replace
from datetime import datetime, timezone
from pathlib import Path

FANOUT_WORKFLOW = Path(".github/workflows/alpenflight-proof-fanout.yml")
FANOUT_WORKFLOW_FILE_NAME = "alpenflight-proof-fanout.yml"
RUNS_CAPTURED_FROM_THE_REAL_API = Path(".github/scripts/fanout-parity-verdict-fixtures.json")
PARITY_SPEC_ROOT_THE_STEP_RUNS_FROM = Path("alpenflight/web")
PARITY_SPEC_PLAYWRIGHT_CONFIG = Path("alpenflight/web/e2e/playwright.config.ts")
REPORTER_THAT_PRINTS_THE_RED_SPEC_PATH_INTO_THE_RUN_LOG = "['github']"

LEGACY_SERVER_BUILD_JOB_NAME = "legacy server build"
LEGACY_WEB_BUILD_JOB_NAME = "legacy web build"
FANOUT_PARITY_JOB_NAME_PREFIX = "fan-out parity"
ALPENFLIGHT_PARITY_SPECS_STEP_NAME_PREFIX = "Run AlpenFlight parity specs"
LEGACY_CREATE_FLOW_SPEC_STEP_NAME_PREFIX = "Run T-04 legacy create-flow spec"

PRODUCER_PATHS_ONLY_THE_REAL_FANOUT_VALIDATES = (
    "alpenflight/migration-bundle",
    "alpenflight/migration-tool",
)

BRANCHES_A_PUSH_MUST_ARM_THE_FANOUT_ON = ("main", "integration/**")
BRANCH_PREVIEW_STEP_NAMES_A_PUSH_RUN_MUST_ALSO_REACH = (
    "Compute fan-out branch-preview destination",
    "Deploy fan-out gallery to gh-pages (branch preview)",
    "Emit fan-out preview URL",
    "Link-check the DEPLOYED branch-preview gallery",
)
EVENT_CONDITION_THE_BRANCH_PREVIEW_STEPS_MUST_CARRY = (
    "(github.event_name == 'workflow_dispatch' || github.event_name == 'push')"
)

SPECS_OF_THE_PARITY_STEP_THAT_ASSERT_MIGRATED_DATA = (
    "e2e/tests/real-idp/fan-out-migration-parity.spec.ts",
    "e2e/tests/real-idp/aircraft-migration-parity.spec.ts",
    "e2e/tests/real-idp/flight-migration-parity.spec.ts",
    "e2e/tests/real-idp/reservations-migration-parity.spec.ts",
    "e2e/tests/real-idp/planning-migration-parity.spec.ts",
    "e2e/tests/real-idp/accounting-rules-parity.spec.ts",
    "e2e/tests/real-idp/delivery-creation-test-parity.spec.ts",
)

SPECS_OF_THE_PARITY_STEP_THAT_ASSERT_NO_MIGRATED_DATA = (
    "e2e/tests/real-idp/deliveries-write-parity.spec.ts",
    "e2e/tests/real-idp/email-templates.spec.ts",
    "e2e/tests/real-idp/public-registration-parity.spec.ts",
    "e2e/tests/real-idp/account-recovery.spec.ts",
    "e2e/tests/real-idp/audit-log-two-club.spec.ts",
)

SPEC_PATH_ROOT_THE_WORKFLOW_AND_THE_RUN_LOG_SHARE = "e2e/tests/"
SPEC_PATH_IN_THE_WORKFLOW_STEP = re.compile(r"(e2e/tests/\S+?\.spec\.ts)")
RED_SPEC_PATTERNS_OF_THE_PLAYWRIGHT_REPORTERS = (
    re.compile(r"::error file=(\S+?\.spec\.ts),"),
    re.compile(r"\d+\)\s+\[[^\]]+\] › (\S+?\.spec\.ts):\d+"),
    re.compile(r"✘\s+\d+\s+\[[^\]]+\] › (\S+?\.spec\.ts):\d+"),
)
PLAYWRIGHT_ABANDONED_THE_REMAINING_SPECS = "Testing stopped early after"
CAPTURED_LOG_LINE_PREFIX_THE_SPEC_PATH_FITS_IN = 240

RESIDUAL_LIMIT = (
    "RESIDUAL LIMIT — this gate arms only on "
    f"{' and '.join(PRODUCER_PATHS_ONLY_THE_REAL_FANOUT_VALIDATES)}, the two trees whose legacy "
    "SELECT no synthetic bundle exercises. A change to the server-side ingest, to a Flyway "
    "migration or to a parity spec also reaches the real fan-out and does NOT arm this gate. The "
    "gate names the red spec out of the run log, so it cannot name a spec whose log GitHub already "
    "deleted, and it cannot see a spec the fan-out's command never selected or a step the fan-out "
    "swallowed with continue-on-error. Playwright stops a serial suite at its first red test, so a "
    "spec that never ran also never went red: this verdict names the specs that DID go red, and it "
    "certifies no spec that never ran."
)

PRECEDENCE_WHEN_COVERING_RUNS_DISAGREE = (
    "PRECEDENCE WHEN COVERING RUNS DISAGREE — the newest covering run whose parity step went red "
    "decides, and this gate refuses the merge. An older green run answers only for a newer run "
    "whose parity step passed, or for a newer run that never reached the parity step, such as a "
    "chain that could not build the legacy stack. A run that is still in flight supersedes every "
    "older red run, because the commit under test can repair a defect outside the producer trees; "
    "this gate then waits for the run in flight instead of naming the older red. A newer green run "
    "over the same producer files replaces an older red one, so a red never blocks this branch "
    "permanently: repair the cause and push, and the fan-out arms itself again. RESIDUAL LIMIT of "
    "this rule — a second attempt that passes clears a red the first attempt found, so an "
    "intermittent parity defect can still reach main."
)

HOW_THE_FANOUT_ARMS_ITSELF = (
    "HOW THE FAN-OUT ARMS — a git push arms it, and nobody dispatches it by hand. A push to main "
    "or to an integration branch that changes "
    f"{' or '.join(PRODUCER_PATHS_ONLY_THE_REAL_FANOUT_VALIDATES)} starts the fan-out, and this "
    "gate waits for that run to finish before it reports a verdict. When the branch head moves "
    "past the covering run that refuses the merge, this gate starts a fresh fan-out on the branch "
    "head and waits for that one, so a repair outside the producer trees also re-proves itself. A "
    "push that changes no producer file starts no fan-out, and this gate then reports that the "
    "fan-out is NOT REQUIRED for that change. It never reports a proven parity for a change no "
    "fan-out judged."
)

PARITY_PROVEN = "PARITY_PROVEN"
PARITY_DEFECT = "PARITY_DEFECT"
UNRELATED_SPEC_RED = "UNRELATED_SPEC_RED"
PARITY_STEP_RED_SPEC_UNNAMED = "PARITY_STEP_RED_SPEC_UNNAMED"
PARITY_PROVEN_RUN_RED_ELSEWHERE = "PARITY_PROVEN_RUN_RED_ELSEWHERE"
COULD_NOT_RUN = "COULD_NOT_RUN"
STILL_RUNNING = "STILL_RUNNING"
A_RUN_IN_FLIGHT_SUPERSEDES_AN_OLDER_RED = "A_RUN_IN_FLIGHT_SUPERSEDES_AN_OLDER_RED"
JOB_NAMES_DRIFTED = "JOB_NAMES_DRIFTED"
NO_RUN_COVERS_THESE_PRODUCER_FILES = "NO_RUN_COVERS_THESE_PRODUCER_FILES"
NO_PRODUCER_FILE_CHANGED = "NO_PRODUCER_FILE_CHANGED"

VERDICT_HEADLINE = {
    PARITY_PROVEN: "the fan-out proved parity over exactly these producer files",
    PARITY_DEFECT: "the fan-out FOUND A PARITY DEFECT — a spec that asserts migrated data went red",
    UNRELATED_SPEC_RED: (
        "the parity step went red ONLY on specs that assert no migrated data — no producer spec "
        "went red"
    ),
    PARITY_STEP_RED_SPEC_UNNAMED: (
        "the parity step went red and the reader CANNOT NAME the spec that failed"
    ),
    PARITY_PROVEN_RUN_RED_ELSEWHERE: (
        "the parity specs PASSED and the fan-out job is red at another step, so this red is not a "
        "parity verdict"
    ),
    COULD_NOT_RUN: "the fan-out COULD NOT RUN — it never reached the AlpenFlight parity specs",
    STILL_RUNNING: "the fan-out is STILL RUNNING over these producer files",
    A_RUN_IN_FLIGHT_SUPERSEDES_AN_OLDER_RED: (
        "a fan-out is STILL RUNNING over these producer files and it supersedes an older red run, "
        "so no verdict is final yet"
    ),
    JOB_NAMES_DRIFTED: "the verdict reader COULD NOT FIND the fan-out job or its parity step",
    NO_RUN_COVERS_THESE_PRODUCER_FILES: "NO fan-out run covers the producer files this branch changes",
    NO_PRODUCER_FILE_CHANGED: (
        "this branch changes no producer file, so the fan-out is NOT REQUIRED for this change and "
        "this gate proves nothing about it"
    ),
}

VERDICT_WHAT_TO_DO = {
    PARITY_DEFECT: (
        "Read the named specs in the failed run. A real legacy export drove them, so the red is a "
        "producer binding, drift, dedupe or foreign-key defect, not a harness fault."
    ),
    UNRELATED_SPEC_RED: (
        "Repair the named specs. They drive no migrated data, so this red is NOT a producer "
        "binding, drift, dedupe or foreign-key defect. Do not hunt a mapper defect. The gate still "
        "refuses the merge, because a red fan-out must reach a human."
    ),
    PARITY_STEP_RED_SPEC_UNNAMED: (
        "Open the failed run and read which spec went red. The reader found no red spec of the "
        "parity step in the run log, so it cannot separate a producer defect from an unrelated "
        "spec. Treat the red as a possible producer defect until you read it."
    ),
    PARITY_PROVEN_RUN_RED_ELSEWHERE: (
        "Parity holds over these producer files: the parity step passed. Repair the red steps this "
        "message names. The gate still refuses the merge, because a red fan-out step must reach a "
        "human, but the red is NOT a parity defect and no mapper is at fault."
    ),
    COULD_NOT_RUN: (
        "The chain broke before it could judge parity, so this is NOT a parity verdict and it is "
        "NOT a green. A cold NuGet cache that cannot restore the old legacy packages lands here. "
        "Read the failed run, repair the chain, then dispatch it again."
    ),
    STILL_RUNNING: (
        "This gate waited for the run this message names and its wait budget ran out before the "
        "run finished. The run is not red: it has not answered yet. Push again, or re-run this "
        "job, once the run this message links has completed."
    ),
    A_RUN_IN_FLIGHT_SUPERSEDES_AN_OLDER_RED: (
        "Read the run in flight this message names, NOT the older red one. The older red run ran "
        "over the same producer files, and the commit under test can repair its cause outside the "
        "producer trees, so that older red decides nothing. This gate waited for the run in flight "
        "and its wait budget ran out. Push again, or re-run this job, once that run has completed."
    ),
    JOB_NAMES_DRIFTED: (
        "Somebody renamed a job or a step of the fan-out workflow. Update the names this script "
        "binds and re-run its selftest, which pins every name against the workflow file."
    ),
    NO_RUN_COVERS_THESE_PRODUCER_FILES: (
        "This gate started a fan-out on the branch head and no covering run appeared inside its "
        "wait budget, or it holds no permission to start one. Push again to arm the fan-out. As a "
        f"last resort, start it by hand: gh workflow run {FANOUT_WORKFLOW_FILE_NAME} --ref <this "
        "branch>. A run counts once its commit is an ancestor of this branch head and no producer "
        "file changed after it."
    ),
    NO_PRODUCER_FILE_CHANGED: (
        "Do nothing. This is NOT a proven parity: no fan-out judged this change, and none is "
        "required, because the change touches no tree whose legacy SELECT only the real fan-out "
        "exercises."
    ),
}


@dataclass(frozen=True)
class FanoutRun:
    run_id: int
    head_sha: str
    status: str
    conclusion: str
    created_at: str
    html_url: str
    head_branch: str


@dataclass(frozen=True)
class RunReading:
    verdict: str
    red_producer_specs: tuple[str, ...] = ()
    red_specs_that_assert_no_migrated_data: tuple[str, ...] = ()
    red_specs_no_parity_step_drives: tuple[str, ...] = ()
    red_steps_beside_the_parity_step: tuple[str, ...] = ()

    def names_a_red(self) -> bool:
        return bool(
            self.red_producer_specs
            or self.red_specs_that_assert_no_migrated_data
            or self.red_steps_beside_the_parity_step
        )


@dataclass(frozen=True)
class Decision:
    verdict: str
    blocks: bool
    producer_files: tuple[str, ...]
    run: FanoutRun | None = None
    reading: RunReading | None = None
    newest_run: FanoutRun | None = None
    newest_reading: RunReading | None = None
    superseded_red_run: FanoutRun | None = None
    superseded_red_reading: RunReading | None = None
    the_gate_armed_a_fresh_fanout: str = ""


class GithubActionsApi:
    def __init__(self, repository: str):
        self.repository = repository

    def _api(self, path: str) -> dict:
        completed = self._gh(path)
        if completed.returncode != 0:
            raise SystemExit(
                f"::error title=fan-out verdict unreadable::gh api {path} exited "
                f"{completed.returncode}: {completed.stderr.strip()}"
            )
        return json.loads(completed.stdout)

    def _gh(self, path: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["gh", "api", "-H", "Accept: application/vnd.github+json", path],
            capture_output=True,
            text=True,
        )

    def fanout_runs_newest_first(self) -> list[FanoutRun]:
        payload = self._api(
            f"repos/{self.repository}/actions/workflows/{FANOUT_WORKFLOW_FILE_NAME}"
            "/runs?per_page=100"
        )
        return [self._as_fanout_run(run) for run in payload.get("workflow_runs", [])]

    def run_by_id(self, run_id: int) -> FanoutRun:
        return self._as_fanout_run(self._api(f"repos/{self.repository}/actions/runs/{run_id}"))

    @staticmethod
    def _as_fanout_run(run: dict) -> FanoutRun:
        return FanoutRun(
            run_id=run["id"],
            head_sha=run["head_sha"],
            status=run.get("status", ""),
            conclusion=run.get("conclusion") or "",
            created_at=run.get("created_at", ""),
            html_url=run.get("html_url", ""),
            head_branch=run.get("head_branch", ""),
        )

    def jobs_of(self, run_id: int) -> list[dict]:
        return self._api(f"repos/{self.repository}/actions/runs/{run_id}/jobs?per_page=100").get(
            "jobs", []
        )

    def job_log(self, job_id: int) -> str:
        completed = self._gh(f"repos/{self.repository}/actions/jobs/{job_id}/logs")
        return completed.stdout if completed.returncode == 0 else ""


class ProducerFileHistory:
    @staticmethod
    def _git(*arguments: str) -> subprocess.CompletedProcess:
        return subprocess.run(["git", *arguments], capture_output=True, text=True)

    def commit_is_reachable_from_head(self, sha: str, head_sha: str) -> bool:
        if self._git("cat-file", "-e", f"{sha}^{{commit}}").returncode != 0:
            return False
        return self._git("merge-base", "--is-ancestor", sha, head_sha).returncode == 0

    def producer_files_changed_between(self, earlier: str, later: str) -> tuple[str, ...]:
        completed = self._git(
            "diff",
            "--name-only",
            earlier,
            later,
            "--",
            *PRODUCER_PATHS_ONLY_THE_REAL_FANOUT_VALIDATES,
        )
        if completed.returncode != 0:
            raise SystemExit(
                f"::error title=fan-out gate cannot read git history::git diff {earlier} {later} "
                f"failed: {completed.stderr.strip()}"
            )
        return tuple(
            path
            for path in completed.stdout.split("\n")
            if path.strip() and not path.endswith(".md")
        )

    def producer_files_changed_since_merge_base(self, base: str, head: str) -> tuple[str, ...]:
        merge_base = self._git("merge-base", base, head)
        if merge_base.returncode != 0:
            return self.producer_files_changed_between(base, head)
        return self.producer_files_changed_between(merge_base.stdout.strip(), head)


def spec_path_under_the_shared_root(path: str) -> str:
    root = SPEC_PATH_ROOT_THE_WORKFLOW_AND_THE_RUN_LOG_SHARE
    return path[path.index(root):] if root in path else path


def red_specs_named_by(log: str) -> tuple[str, ...]:
    named: list[str] = []
    for pattern in RED_SPEC_PATTERNS_OF_THE_PLAYWRIGHT_REPORTERS:
        for match in pattern.finditer(log):
            spec = spec_path_under_the_shared_root(match.group(1))
            if spec not in named:
                named.append(spec)
    return tuple(named)


def read_run(api, run: FanoutRun, jobs: list[dict]) -> RunReading:
    if run.status != "completed":
        return RunReading(STILL_RUNNING)
    parity_job = next(
        (job for job in jobs if str(job.get("name", "")).startswith(FANOUT_PARITY_JOB_NAME_PREFIX)),
        None,
    )
    if parity_job is None:
        return RunReading(JOB_NAMES_DRIFTED)
    steps = parity_job.get("steps", [])
    parity_step = next(
        (
            step
            for step in steps
            if str(step.get("name", "")).startswith(ALPENFLIGHT_PARITY_SPECS_STEP_NAME_PREFIX)
        ),
        None,
    )
    if parity_job.get("conclusion") == "success":
        if parity_step is None:
            return RunReading(JOB_NAMES_DRIFTED)
        if parity_step.get("conclusion") != "success":
            return RunReading(COULD_NOT_RUN)
        return RunReading(PARITY_PROVEN)
    if parity_job.get("conclusion") != "failure":
        return RunReading(COULD_NOT_RUN)
    conclusion_by_job_name = {str(job.get("name", "")): job.get("conclusion") for job in jobs}
    for build_job_name in (LEGACY_SERVER_BUILD_JOB_NAME, LEGACY_WEB_BUILD_JOB_NAME):
        if conclusion_by_job_name.get(build_job_name) != "success":
            return RunReading(COULD_NOT_RUN)
    if parity_step is None:
        return RunReading(JOB_NAMES_DRIFTED)

    red_steps_beside_the_parity_step = tuple(
        f"#{step.get('number')} {step.get('name')}"
        for step in steps
        if step.get("conclusion") == "failure" and step is not parity_step
    )
    if parity_step.get("conclusion") == "success":
        return RunReading(
            PARITY_PROVEN_RUN_RED_ELSEWHERE,
            red_steps_beside_the_parity_step=red_steps_beside_the_parity_step,
        )
    if parity_step.get("conclusion") != "failure":
        return RunReading(
            COULD_NOT_RUN, red_steps_beside_the_parity_step=red_steps_beside_the_parity_step
        )

    log = api.job_log(parity_job.get("id"))
    if PLAYWRIGHT_ABANDONED_THE_REMAINING_SPECS in log:
        return RunReading(
            PARITY_STEP_RED_SPEC_UNNAMED,
            red_steps_beside_the_parity_step=red_steps_beside_the_parity_step,
        )
    red_specs = red_specs_named_by(log)
    red_producer_specs = tuple(
        spec for spec in red_specs if spec in SPECS_OF_THE_PARITY_STEP_THAT_ASSERT_MIGRATED_DATA
    )
    red_specs_that_assert_no_migrated_data = tuple(
        spec for spec in red_specs if spec in SPECS_OF_THE_PARITY_STEP_THAT_ASSERT_NO_MIGRATED_DATA
    )
    red_specs_no_parity_step_drives = tuple(
        spec
        for spec in red_specs
        if spec not in red_producer_specs and spec not in red_specs_that_assert_no_migrated_data
    )
    if red_producer_specs:
        verdict = PARITY_DEFECT
    elif red_specs_that_assert_no_migrated_data:
        verdict = UNRELATED_SPEC_RED
    else:
        verdict = PARITY_STEP_RED_SPEC_UNNAMED
    return RunReading(
        verdict,
        red_producer_specs=red_producer_specs,
        red_specs_that_assert_no_migrated_data=red_specs_that_assert_no_migrated_data,
        red_specs_no_parity_step_drives=red_specs_no_parity_step_drives,
        red_steps_beside_the_parity_step=red_steps_beside_the_parity_step,
    )


MOST_RECENT_RUNS_THE_GATE_ASKS_THE_API_ABOUT = 12

READINGS_NO_OLDER_GREEN_RUN_ANSWERS_FOR = (
    PARITY_DEFECT,
    UNRELATED_SPEC_RED,
    PARITY_STEP_RED_SPEC_UNNAMED,
)


def decide(
    api,
    history,
    base_sha: str,
    head_sha: str,
    readings_of_completed_runs: dict[int, RunReading] | None = None,
) -> Decision:
    producer_files = history.producer_files_changed_since_merge_base(base_sha, head_sha)
    if not producer_files:
        return Decision(NO_PRODUCER_FILE_CHANGED, False, producer_files)

    candidates = [
        run
        for run in api.fanout_runs_newest_first()
        if history.commit_is_reachable_from_head(run.head_sha, head_sha)
        and not history.producer_files_changed_between(run.head_sha, head_sha)
    ][:MOST_RECENT_RUNS_THE_GATE_ASKS_THE_API_ABOUT]

    if not candidates:
        return Decision(NO_RUN_COVERS_THESE_PRODUCER_FILES, True, producer_files)

    newest_run, newest_reading = None, None
    run_in_flight, reading_in_flight = None, None
    for run in candidates:
        remembered = (
            readings_of_completed_runs.get(run.run_id)
            if readings_of_completed_runs is not None
            else None
        )
        reading = remembered or read_run(api, run, api.jobs_of(run.run_id))
        if (
            readings_of_completed_runs is not None
            and remembered is None
            and run.status == "completed"
        ):
            readings_of_completed_runs[run.run_id] = reading
        if newest_reading is None:
            newest_run, newest_reading = run, reading
        if reading.verdict == STILL_RUNNING and reading_in_flight is None:
            run_in_flight, reading_in_flight = run, reading
        if reading.verdict in READINGS_NO_OLDER_GREEN_RUN_ANSWERS_FOR:
            if reading_in_flight is not None:
                return Decision(
                    A_RUN_IN_FLIGHT_SUPERSEDES_AN_OLDER_RED,
                    True,
                    producer_files,
                    run_in_flight,
                    reading_in_flight,
                    newest_run,
                    newest_reading,
                    superseded_red_run=run,
                    superseded_red_reading=reading,
                )
            return Decision(
                reading.verdict, True, producer_files, run, reading, newest_run, newest_reading
            )
        if reading.verdict == PARITY_PROVEN:
            return Decision(
                PARITY_PROVEN, False, producer_files, run, reading, newest_run, newest_reading
            )
    if reading_in_flight is not None:
        return Decision(
            STILL_RUNNING,
            True,
            producer_files,
            run_in_flight,
            reading_in_flight,
            newest_run,
            newest_reading,
        )
    return Decision(
        newest_reading.verdict,
        True,
        producer_files,
        newest_run,
        newest_reading,
        newest_run,
        newest_reading,
    )


def render(decision: Decision) -> str:
    lines = [f"fan-out parity verdict: {decision.verdict} — {VERDICT_HEADLINE[decision.verdict]}"]
    if decision.producer_files:
        lines.append(
            f"producer files this branch changes ({len(decision.producer_files)}): "
            + ", ".join(decision.producer_files[:10])
        )
    if decision.run is not None:
        lines.append(
            f"run {decision.run.run_id} on {decision.run.head_branch} at {decision.run.head_sha[:12]} "
            f"({decision.run.created_at}): {decision.run.html_url}"
        )
    reading = decision.reading
    if reading is not None:
        if reading.red_producer_specs:
            lines.append(
                "red specs that assert migrated data: " + ", ".join(reading.red_producer_specs)
            )
        if reading.red_specs_that_assert_no_migrated_data:
            lines.append(
                "red specs that assert no migrated data: "
                + ", ".join(reading.red_specs_that_assert_no_migrated_data)
            )
        if reading.red_specs_no_parity_step_drives and decision.verdict in (
            PARITY_STEP_RED_SPEC_UNNAMED,
            UNRELATED_SPEC_RED,
        ):
            lines.append(
                "red specs the parity step does not drive: "
                + ", ".join(reading.red_specs_no_parity_step_drives)
            )
        if reading.red_steps_beside_the_parity_step:
            lines.append(
                "red steps beside the parity step: "
                + ", ".join(reading.red_steps_beside_the_parity_step)
            )
        elif decision.verdict == PARITY_PROVEN_RUN_RED_ELSEWHERE:
            lines.append(
                "red steps beside the parity step: none — the API names no red step, so the job "
                "itself timed out or was cancelled after the parity step passed"
            )
    if decision.superseded_red_reading is not None:
        superseded = decision.superseded_red_reading
        lines.append(
            f"the older covering run {decision.superseded_red_run.run_id} at "
            f"{decision.superseded_red_run.head_sha[:12]} reads {superseded.verdict}, and the run "
            f"in flight above supersedes it: {decision.superseded_red_run.html_url}"
        )
        if superseded.red_producer_specs:
            lines.append(
                "red specs of the superseded run that assert migrated data: "
                + ", ".join(superseded.red_producer_specs)
            )
        if superseded.red_specs_that_assert_no_migrated_data:
            lines.append(
                "red specs of the superseded run that assert no migrated data: "
                + ", ".join(superseded.red_specs_that_assert_no_migrated_data)
            )
    elif decision.newest_reading is not None and decision.newest_reading is not reading:
        lines.append(
            f"the newest covering run {decision.newest_run.run_id} reads "
            f"{decision.newest_reading.verdict}, and the older covering run {decision.run.run_id} "
            f"decides this verdict: {decision.newest_run.html_url}"
        )
    if decision.the_gate_armed_a_fresh_fanout:
        lines.append(decision.the_gate_armed_a_fresh_fanout)
    if decision.verdict in VERDICT_WHAT_TO_DO:
        lines.append(VERDICT_WHAT_TO_DO[decision.verdict])
    lines.append(RESIDUAL_LIMIT)
    lines.append(HOW_THE_FANOUT_ARMS_ITSELF)
    if decision.newest_reading is not None:
        lines.append(PRECEDENCE_WHEN_COVERING_RUNS_DISAGREE)
    return "\n".join(lines)


def write_step_summary(text: str) -> None:
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as summary:
            summary.write(text + "\n\n")


@dataclass(frozen=True)
class WaitBudget:
    minutes_until_a_covering_run_must_appear: float
    minutes_until_the_covering_run_must_finish: float
    seconds_between_polls: float


VERDICTS_A_FINISHING_RUN_RESOLVES = (STILL_RUNNING, A_RUN_IN_FLIGHT_SUPERSEDES_AN_OLDER_RED)

VERDICTS_A_FRESH_FANOUT_ON_THE_BRANCH_HEAD_CAN_ANSWER = (
    NO_RUN_COVERS_THESE_PRODUCER_FILES,
    PARITY_DEFECT,
    UNRELATED_SPEC_RED,
    PARITY_STEP_RED_SPEC_UNNAMED,
    PARITY_PROVEN_RUN_RED_ELSEWHERE,
    COULD_NOT_RUN,
)


def a_fresh_fanout_on_the_branch_head_can_answer(decision: Decision, head_sha: str) -> bool:
    if decision.verdict not in VERDICTS_A_FRESH_FANOUT_ON_THE_BRANCH_HEAD_CAN_ANSWER:
        return False
    return decision.run is None or decision.run.head_sha != head_sha


class FanoutStarter:
    def __init__(self, repository: str, branch: str):
        self.repository = repository
        self.branch = branch

    def start(self) -> str:
        completed = subprocess.run(
            [
                "gh",
                "workflow",
                "run",
                FANOUT_WORKFLOW_FILE_NAME,
                "--repo",
                self.repository,
                "--ref",
                self.branch,
            ],
            capture_output=True,
            text=True,
        )
        if completed.returncode != 0:
            return (
                f"this gate could not start a fan-out on {self.branch}: gh workflow run exited "
                f"{completed.returncode}: {completed.stderr.strip()}"
            )
        return (
            f"this gate started a fan-out on {self.branch}, because the branch head moved past the "
            "covering run that refuses the merge"
        )


def resolve(
    api,
    history,
    base_sha: str,
    head_sha: str,
    budget: WaitBudget,
    starter=None,
    now=time.monotonic,
    sleep=time.sleep,
) -> Decision:
    started_at = now()
    polls = 0
    started_a_fanout = ""
    readings_of_completed_runs: dict[int, RunReading] = {}
    while True:
        decision = decide(api, history, base_sha, head_sha, readings_of_completed_runs)
        if started_a_fanout:
            decision = replace(decision, the_gate_armed_a_fresh_fanout=started_a_fanout)
        if decision.verdict in VERDICTS_A_FINISHING_RUN_RESOLVES:
            deadline = budget.minutes_until_the_covering_run_must_finish
        elif starter is not None and a_fresh_fanout_on_the_branch_head_can_answer(
            decision, head_sha
        ):
            if not started_a_fanout:
                if polls > 0:
                    started_a_fanout = starter.start()
                polls += 1
                sleep(budget.seconds_between_polls)
                continue
            deadline = budget.minutes_until_a_covering_run_must_appear
        else:
            return decision
        if (now() - started_at) / 60.0 >= deadline:
            return decision
        polls += 1
        sleep(budget.seconds_between_polls)


def gate(
    base_sha: str,
    head_sha: str,
    repository: str,
    budget: WaitBudget,
    branch_a_fresh_fanout_starts_on: str,
) -> int:
    decision = resolve(
        GithubActionsApi(repository),
        ProducerFileHistory(),
        base_sha,
        head_sha,
        budget,
        FanoutStarter(repository, branch_a_fresh_fanout_starts_on)
        if branch_a_fresh_fanout_starts_on
        else None,
    )
    report = render(decision)
    print(report)
    write_step_summary(f"### fan-out parity gate\n\n```\n{report}\n```")
    if decision.blocks:
        print(
            f"::error title=fan-out parity is red ({decision.verdict})::"
            + report.replace("\n", " ")
        )
        return 1
    if (
        decision.newest_reading is not None
        and decision.newest_reading is not decision.reading
        and decision.newest_reading.names_a_red()
    ):
        print(
            "::warning title=the newest covering fan-out run is red and its parity step did not go "
            f"red ({decision.newest_reading.verdict})::" + report.replace("\n", " ")
        )
    return 0


SCHEDULED_FANOUT_IS_STALE_AFTER_DAYS = 3


def days_since(created_at: str, now: datetime) -> float:
    return (now - datetime.fromisoformat(created_at.replace("Z", "+00:00"))).total_seconds() / 86400


def report_latest_verdict_on_main(repository: str) -> int:
    api = GithubActionsApi(repository)
    latest = next(
        (
            run
            for run in api.fanout_runs_newest_first()
            if run.head_branch == "main" and run.status == "completed"
        ),
        None,
    )
    if latest is None:
        message = "no completed fan-out run on main is visible to the API"
        print(f"::warning title=fan-out on main is unknown::{message}")
        write_step_summary(f"### fan-out on main\n\n{message}")
        return 0
    reading = read_run(api, latest, api.jobs_of(latest.run_id))
    age_in_days = days_since(latest.created_at, datetime.now(timezone.utc))
    line = (
        f"newest fan-out on main: {reading.verdict} — {VERDICT_HEADLINE[reading.verdict]} "
        f"(run {latest.run_id}, {latest.created_at}, {age_in_days:.1f} days old, {latest.html_url})"
    )
    if reading.red_producer_specs or reading.red_specs_that_assert_no_migrated_data:
        line += " red specs: " + ", ".join(
            reading.red_producer_specs + reading.red_specs_that_assert_no_migrated_data
        )
    if reading.red_steps_beside_the_parity_step:
        line += " red steps beside the parity step: " + ", ".join(
            reading.red_steps_beside_the_parity_step
        )
    print(line)
    write_step_summary(f"### fan-out on main\n\n{line}")
    if reading.verdict != PARITY_PROVEN:
        print(
            f"::warning title=the fan-out on main is not green ({reading.verdict})::{line} "
            f"{VERDICT_WHAT_TO_DO.get(reading.verdict, '')}"
        )
    if age_in_days > SCHEDULED_FANOUT_IS_STALE_AFTER_DAYS:
        print(
            f"::warning title=the scheduled fan-out stopped running::{line} The cron of "
            f"{FANOUT_WORKFLOW_FILE_NAME} should produce a run on main every day. GitHub disables "
            "a scheduled workflow after 60 days without repository activity, and a disabled "
            "schedule reads as no red at all. Dispatch it and re-enable the schedule."
        )
    return 0


def job_and_step_names_of_the_fanout_workflow() -> tuple[list[str], list[str]]:
    if not FANOUT_WORKFLOW.is_file():
        raise SystemExit(f"::error::run this from the repository root; {FANOUT_WORKFLOW} not found")
    job_names, step_names = [], []
    for line in FANOUT_WORKFLOW.read_text(encoding="utf-8").splitlines():
        job_name = re.match(r"^    name: (.+)$", line)
        if job_name:
            job_names.append(job_name.group(1).strip())
        step_name = re.match(r"^      - name: (.+)$", line)
        if step_name:
            step_names.append(step_name.group(1).strip())
    return job_names, step_names


def specs_the_parity_step_drives() -> tuple[str, ...]:
    if not FANOUT_WORKFLOW.is_file():
        raise SystemExit(f"::error::run this from the repository root; {FANOUT_WORKFLOW} not found")
    specs: list[str] = []
    inside_the_parity_step = False
    for line in FANOUT_WORKFLOW.read_text(encoding="utf-8").splitlines():
        step_name = re.match(r"^      - name: (.+)$", line)
        if step_name:
            inside_the_parity_step = step_name.group(1).strip().startswith(
                ALPENFLIGHT_PARITY_SPECS_STEP_NAME_PREFIX
            )
            continue
        if inside_the_parity_step:
            specs.extend(match.group(1) for match in SPEC_PATH_IN_THE_WORKFLOW_STEP.finditer(line))
    return tuple(specs)


def push_trigger_of_the_fanout_workflow() -> tuple[tuple[str, ...], tuple[str, ...]]:
    branches: list[str] = []
    paths: list[str] = []
    under_the_trigger_block = False
    inside_the_push_trigger = False
    key = ""
    for line in FANOUT_WORKFLOW.read_text(encoding="utf-8").splitlines():
        if line.strip().startswith("#") or not line.strip():
            continue
        if re.match(r"^\S", line):
            under_the_trigger_block = line.startswith("on:")
            inside_the_push_trigger = False
            key = ""
            continue
        if not under_the_trigger_block:
            continue
        trigger = re.match(r"^  (\S.*)$", line)
        if trigger:
            inside_the_push_trigger = trigger.group(1).strip() == "push:"
            key = ""
            continue
        if not inside_the_push_trigger:
            continue
        filter_key = re.match(r"^    (\S+):\s*$", line)
        if filter_key:
            key = filter_key.group(1)
            continue
        item = re.match(r"^      - (.+)$", line)
        if item and key in ("branches", "paths"):
            value = item.group(1).strip().strip("'\"")
            (branches if key == "branches" else paths).append(value)
    return tuple(branches), tuple(paths)


def if_conditions_by_step_name_of_the_fanout_workflow() -> dict[str, str]:
    conditions: dict[str, str] = {}
    step_name = ""
    for line in FANOUT_WORKFLOW.read_text(encoding="utf-8").splitlines():
        named_step = re.match(r"^      - name: (.+)$", line)
        if named_step:
            step_name = named_step.group(1).strip()
            continue
        if re.match(r"^      - ", line):
            step_name = ""
            continue
        condition = re.match(r"^        if: (.+)$", line)
        if condition and step_name:
            conditions[step_name] = condition.group(1).strip()
    return conditions


def the_fanout_arms_itself_failures() -> list[str]:
    failures: list[str] = []
    branches, paths = push_trigger_of_the_fanout_workflow()
    if not paths:
        failures.append(
            f"{FANOUT_WORKFLOW} declares no on.push trigger with a paths filter, so a push arms no "
            "fan-out and a human must dispatch it by hand; this gate then refuses every merge that "
            "changes a producer file until somebody remembers"
        )
    for producer_path in PRODUCER_PATHS_ONLY_THE_REAL_FANOUT_VALIDATES:
        if f"{producer_path}/**" not in paths:
            failures.append(
                f"{FANOUT_WORKFLOW} on.push.paths does not arm on {producer_path}/**, which this "
                "gate reads as a producer tree; a push that changes that tree would start no "
                "fan-out and this gate would refuse the merge with no run to point at"
            )
    for path in paths:
        if path.startswith("!"):
            continue
        if not any(
            path == producer_path or path.startswith(producer_path + "/")
            for producer_path in PRODUCER_PATHS_ONLY_THE_REAL_FANOUT_VALIDATES
        ):
            failures.append(
                f"{FANOUT_WORKFLOW} on.push.paths arms on {path}, which lies outside the producer "
                "trees this gate reads; the fan-out costs a full legacy chain, so it must arm only "
                "on the trees whose legacy SELECT no synthetic bundle exercises"
            )
    for branch in BRANCHES_A_PUSH_MUST_ARM_THE_FANOUT_ON:
        if branch not in branches:
            failures.append(
                f"{FANOUT_WORKFLOW} on.push.branches does not list {branch}, so a push there arms "
                "no fan-out"
            )
    conditions = if_conditions_by_step_name_of_the_fanout_workflow()
    for step_name in BRANCH_PREVIEW_STEP_NAMES_A_PUSH_RUN_MUST_ALSO_REACH:
        condition = conditions.get(step_name)
        if condition is None:
            failures.append(
                f"{FANOUT_WORKFLOW} has no step named {step_name!r} carrying an if condition; the "
                "branch-preview gallery deploy is what a reviewer reads, and this guard can no "
                "longer tell whether a push-armed run reaches it"
            )
        elif EVENT_CONDITION_THE_BRANCH_PREVIEW_STEPS_MUST_CARRY not in condition:
            failures.append(
                f"{FANOUT_WORKFLOW} step {step_name!r} does not carry "
                f"{EVENT_CONDITION_THE_BRANCH_PREVIEW_STEPS_MUST_CARRY}, so a push-armed fan-out "
                "publishes no branch-preview gallery and a human must dispatch the run by hand to "
                "see the proof"
            )
    return failures


class FakeGithubActionsApi:
    def __init__(
        self,
        runs: list[FanoutRun],
        jobs_by_run_id: dict[int, list[dict]],
        log_by_job_id: dict[int, str] | None = None,
    ):
        self.runs = runs
        self.jobs_by_run_id = jobs_by_run_id
        self.log_by_job_id = log_by_job_id or {}

    def fanout_runs_newest_first(self) -> list[FanoutRun]:
        return self.runs

    def jobs_of(self, run_id: int) -> list[dict]:
        return self.jobs_by_run_id[run_id]

    def job_log(self, job_id: int) -> str:
        return self.log_by_job_id.get(job_id, "")


class CapturedGithubActionsApi:
    def __init__(self, captured_runs_newest_first: list[dict]):
        self.captured_runs_newest_first = captured_runs_newest_first

    def fanout_runs_newest_first(self) -> list[FanoutRun]:
        return [FanoutRun(**captured["run"]) for captured in self.captured_runs_newest_first]

    def _captured(self, run_id: int) -> dict:
        return next(
            captured
            for captured in self.captured_runs_newest_first
            if captured["run"]["run_id"] == run_id
        )

    def jobs_of(self, run_id: int) -> list[dict]:
        return self._captured(run_id)["jobs"]

    def job_log(self, job_id: int) -> str:
        for captured in self.captured_runs_newest_first:
            if any(job.get("id") == job_id for job in captured["jobs"]):
                return "\n".join(
                    captured["parity_job_log_lines_that_name_a_red_spec_or_an_early_stop"]
                )
        return ""


class FakeProducerFileHistory:
    def __init__(self, producer_files_by_pair: dict[tuple[str, str], tuple[str, ...]]):
        self.producer_files_by_pair = producer_files_by_pair

    def commit_is_reachable_from_head(self, sha: str, head_sha: str) -> bool:
        return (sha, head_sha) in self.producer_files_by_pair

    def producer_files_changed_between(self, earlier: str, later: str) -> tuple[str, ...]:
        return self.producer_files_by_pair[(earlier, later)]

    def producer_files_changed_since_merge_base(self, base: str, head: str) -> tuple[str, ...]:
        return self.producer_files_by_pair[(base, head)]


PARITY_JOB_ID_IN_THE_FAKES = 900
A_PRODUCER_SPEC = SPECS_OF_THE_PARITY_STEP_THAT_ASSERT_MIGRATED_DATA[6]
A_SPEC_THAT_ASSERTS_NO_MIGRATED_DATA = SPECS_OF_THE_PARITY_STEP_THAT_ASSERT_NO_MIGRATED_DATA[2]


def playwright_log_naming(*red_specs: str, stopped_early: bool = False) -> str:
    lines = [
        f"::error file=alpenflight/web/{spec},title=[real-idp] › {spec}:1:1 › a case::  1) x"
        for spec in red_specs
    ]
    if stopped_early:
        lines.append(f"  {PLAYWRIGHT_ABANDONED_THE_REMAINING_SPECS} 10 maximum allowed failures.")
    return "\n".join(lines)


def fanout_jobs(
    parity_conclusion: str,
    parity_step_conclusion: str = "success",
    legacy_server_build_conclusion: str = "success",
    legacy_web_build_conclusion: str = "success",
    parity_job_name: str = "fan-out parity (legacy → migrate+Keycloak → AlpenFlight)",
    parity_step_name: str = "Run AlpenFlight parity specs (J-0c real-bundle + J-1 aircraft)",
    red_step_beside_the_parity_step: str | None = None,
) -> list[dict]:
    steps = [
        {"number": 1, "name": LEGACY_CREATE_FLOW_SPEC_STEP_NAME_PREFIX, "conclusion": "success"},
        {"number": 2, "name": parity_step_name, "conclusion": parity_step_conclusion},
    ]
    if red_step_beside_the_parity_step:
        steps.append(
            {"number": 3, "name": red_step_beside_the_parity_step, "conclusion": "failure"}
        )
    return [
        {"name": LEGACY_SERVER_BUILD_JOB_NAME, "conclusion": legacy_server_build_conclusion},
        {"name": LEGACY_WEB_BUILD_JOB_NAME, "conclusion": legacy_web_build_conclusion},
        {
            "name": parity_job_name,
            "id": PARITY_JOB_ID_IN_THE_FAKES,
            "conclusion": parity_conclusion,
            "steps": steps,
        },
    ]


def fanout_run(
    run_id: int, head_sha: str, status: str = "completed", conclusion: str = "failure"
) -> FanoutRun:
    return FanoutRun(run_id, head_sha, status, conclusion, "2026-08-21T05:00:00Z", "https://run", "main")


HEAD = "head000"
PROVEN_SHA = "proven0"
TOUCHED_MAPPER = ("alpenflight/migration-bundle/src/main/java/AccountingRuleFilterMapper.java",)


def synthetic_input_classes() -> list[tuple[str, Decision]]:
    classes = []

    def scored(name: str, api, history) -> None:
        classes.append((name, decide(api, history, "base000", HEAD)))

    def covering(sha: str = PROVEN_SHA) -> FakeProducerFileHistory:
        return FakeProducerFileHistory({("base000", HEAD): TOUCHED_MAPPER, (sha, HEAD): ()})

    def two_covering_runs(newer_sha: str, older_sha: str) -> FakeProducerFileHistory:
        return FakeProducerFileHistory(
            {("base000", HEAD): TOUCHED_MAPPER, (newer_sha, HEAD): (), (older_sha, HEAD): ()}
        )

    scored(
        "a genuine parity mismatch",
        FakeGithubActionsApi(
            [fanout_run(1, PROVEN_SHA)],
            {1: fanout_jobs("failure", "failure")},
            {PARITY_JOB_ID_IN_THE_FAKES: playwright_log_naming(A_PRODUCER_SPEC)},
        ),
        covering(),
    )
    scored(
        "a fan-out that could not build the legacy stack",
        FakeGithubActionsApi(
            [fanout_run(2, PROVEN_SHA)],
            {2: fanout_jobs("skipped", "skipped", legacy_server_build_conclusion="failure")},
        ),
        covering(),
    )
    scored(
        "a fan-out that broke before the parity specs",
        FakeGithubActionsApi([fanout_run(3, PROVEN_SHA)], {3: fanout_jobs("failure", "skipped")}),
        covering(),
    )
    scored(
        "a fan-out that did not run at all",
        FakeGithubActionsApi([], {}),
        FakeProducerFileHistory({("base000", HEAD): TOUCHED_MAPPER}),
    )
    scored(
        "a green fan-out that predates the producer edit",
        FakeGithubActionsApi([fanout_run(4, PROVEN_SHA, conclusion="success")], {4: fanout_jobs("success")}),
        FakeProducerFileHistory(
            {("base000", HEAD): TOUCHED_MAPPER, (PROVEN_SHA, HEAD): TOUCHED_MAPPER}
        ),
    )
    scored(
        "a fan-out still running",
        FakeGithubActionsApi([fanout_run(5, PROVEN_SHA, status="in_progress", conclusion="")], {5: []}),
        covering(),
    )
    scored(
        "a renamed fan-out job the reader cannot find",
        FakeGithubActionsApi(
            [fanout_run(6, PROVEN_SHA)], {6: fanout_jobs("failure", "failure", parity_job_name="proof chain")}
        ),
        covering(),
    )
    scored(
        "a renamed parity step the reader cannot find",
        FakeGithubActionsApi(
            [fanout_run(7, PROVEN_SHA)],
            {7: fanout_jobs("failure", "failure", parity_step_name="Run the specs")},
        ),
        covering(),
    )
    scored(
        "a green fan-out over exactly these producer files",
        FakeGithubActionsApi([fanout_run(8, PROVEN_SHA, conclusion="success")], {8: fanout_jobs("success")}),
        covering(),
    )
    scored(
        "a branch that changes no producer file",
        FakeGithubActionsApi([], {}),
        FakeProducerFileHistory({("base000", HEAD): ()}),
    )
    scored(
        "a parity step that stopped early at the failure cap",
        FakeGithubActionsApi(
            [fanout_run(9, PROVEN_SHA)],
            {9: fanout_jobs("failure", "failure")},
            {
                PARITY_JOB_ID_IN_THE_FAKES: playwright_log_naming(
                    A_SPEC_THAT_ASSERTS_NO_MIGRATED_DATA, stopped_early=True
                )
            },
        ),
        covering(),
    )
    scored(
        "a parity step red on a spec and on a producer spec together",
        FakeGithubActionsApi(
            [fanout_run(10, PROVEN_SHA)],
            {10: fanout_jobs("failure", "failure")},
            {
                PARITY_JOB_ID_IN_THE_FAKES: playwright_log_naming(
                    A_SPEC_THAT_ASSERTS_NO_MIGRATED_DATA, A_PRODUCER_SPEC
                )
            },
        ),
        covering(),
    )
    scored(
        "a parity step red with an unreadable run log",
        FakeGithubActionsApi(
            [fanout_run(11, PROVEN_SHA)], {11: fanout_jobs("failure", "failure")}, {}
        ),
        covering(),
    )
    scored(
        "an older covering run proves parity while the newest one is red at a later step",
        FakeGithubActionsApi(
            [fanout_run(13, "newer00"), fanout_run(14, PROVEN_SHA, conclusion="success")],
            {
                13: fanout_jobs(
                    "failure",
                    "success",
                    red_step_beside_the_parity_step="Link-check the DEPLOYED branch-preview gallery",
                ),
                14: fanout_jobs("success"),
            },
        ),
        FakeProducerFileHistory(
            {("base000", HEAD): TOUCHED_MAPPER, ("newer00", HEAD): (), (PROVEN_SHA, HEAD): ()}
        ),
    )
    scored(
        "a green fan-out job whose parity step never ran",
        FakeGithubActionsApi(
            [fanout_run(12, PROVEN_SHA, conclusion="success")],
            {12: fanout_jobs("success", "skipped")},
        ),
        covering(),
    )
    scored(
        "both covering runs found a parity defect",
        FakeGithubActionsApi(
            [fanout_run(15, "newer00"), fanout_run(16, PROVEN_SHA)],
            {15: fanout_jobs("failure", "failure"), 16: fanout_jobs("failure", "failure")},
            {PARITY_JOB_ID_IN_THE_FAKES: playwright_log_naming(A_PRODUCER_SPEC)},
        ),
        two_covering_runs("newer00", PROVEN_SHA),
    )
    scored(
        "the newest covering run is red on a spec it cannot name and an older covering run proves parity",
        FakeGithubActionsApi(
            [fanout_run(17, "newer00"), fanout_run(18, PROVEN_SHA, conclusion="success")],
            {17: fanout_jobs("failure", "failure"), 18: fanout_jobs("success")},
            {},
        ),
        two_covering_runs("newer00", PROVEN_SHA),
    )
    scored(
        "the newest covering run is still running and an older covering run found a parity defect",
        FakeGithubActionsApi(
            [
                fanout_run(19, "newer00", status="in_progress", conclusion=""),
                fanout_run(20, PROVEN_SHA),
            ],
            {19: [], 20: fanout_jobs("failure", "failure")},
            {PARITY_JOB_ID_IN_THE_FAKES: playwright_log_naming(A_PRODUCER_SPEC)},
        ),
        two_covering_runs("newer00", PROVEN_SHA),
    )
    scored(
        "the newest covering run is still running and an older covering run proves parity",
        FakeGithubActionsApi(
            [
                fanout_run(21, "newer00", status="in_progress", conclusion=""),
                fanout_run(22, PROVEN_SHA, conclusion="success"),
            ],
            {21: [], 22: fanout_jobs("success")},
        ),
        two_covering_runs("newer00", PROVEN_SHA),
    )
    return classes


class ClockTheSelftestAdvancesOnEverySleep:
    def __init__(self):
        self.seconds = 0.0

    def now(self) -> float:
        return self.seconds

    def sleep(self, seconds: float) -> None:
        self.seconds += seconds


class ApiThatReplaysOneRunListPerDecision:
    def __init__(
        self,
        run_lists_newest_first: list[list[FanoutRun]],
        jobs_by_run_id: dict[int, list[dict]],
        log_by_job_id: dict[int, str] | None = None,
    ):
        self.run_lists_newest_first = run_lists_newest_first
        self.jobs_by_run_id = jobs_by_run_id
        self.log_by_job_id = log_by_job_id or {}
        self.decisions = 0
        self.jobs_read_by_run_id: dict[int, int] = {}

    def fanout_runs_newest_first(self) -> list[FanoutRun]:
        runs = self.run_lists_newest_first[
            min(self.decisions, len(self.run_lists_newest_first) - 1)
        ]
        self.decisions += 1
        return runs

    def jobs_of(self, run_id: int) -> list[dict]:
        self.jobs_read_by_run_id[run_id] = self.jobs_read_by_run_id.get(run_id, 0) + 1
        return self.jobs_by_run_id.get(run_id, [])

    def job_log(self, job_id: int) -> str:
        return self.log_by_job_id.get(job_id, "")


class StarterTheSelftestCounts:
    def __init__(self):
        self.starts = 0

    def start(self) -> str:
        self.starts += 1
        return "this gate started a fan-out on integration/J-32, because the branch head moved past the covering run that refuses the merge"


SELFTEST_WAIT_BUDGET = WaitBudget(
    minutes_until_a_covering_run_must_appear=2.0,
    minutes_until_the_covering_run_must_finish=5.0,
    seconds_between_polls=60.0,
)

OLDER_RED_SHA = "oldred0"
IN_FLIGHT_SHA = "newer00"


POLLS_A_FULL_WAIT_BUDGET_SPENDS = int(
    SELFTEST_WAIT_BUDGET.minutes_until_the_covering_run_must_finish
    * 60
    / SELFTEST_WAIT_BUDGET.seconds_between_polls
)


def the_gate_asks_the_api_about_a_completed_run_once_failures() -> list[str]:
    in_flight = fanout_run(41, IN_FLIGHT_SHA, status="in_progress", conclusion="")
    completed_red = fanout_run(42, OLDER_RED_SHA)
    api = ApiThatReplaysOneRunListPerDecision(
        [[in_flight, completed_red]],
        {41: [], 42: fanout_jobs("failure", "failure")},
        {PARITY_JOB_ID_IN_THE_FAKES: playwright_log_naming(A_PRODUCER_SPEC)},
    )
    clock = ClockTheSelftestAdvancesOnEverySleep()
    decision = resolve(
        api,
        FakeProducerFileHistory(
            {
                ("base000", HEAD): TOUCHED_MAPPER,
                (IN_FLIGHT_SHA, HEAD): (),
                (OLDER_RED_SHA, HEAD): (),
            }
        ),
        "base000",
        HEAD,
        SELFTEST_WAIT_BUDGET,
        None,
        clock.now,
        clock.sleep,
    )
    failures = []
    if decision.verdict != A_RUN_IN_FLIGHT_SUPERSEDES_AN_OLDER_RED:
        failures.append(
            f"the gate that waits reads {decision.verdict} while a run is in flight over an older "
            f"red, expected {A_RUN_IN_FLIGHT_SUPERSEDES_AN_OLDER_RED}"
        )
    if api.jobs_read_by_run_id.get(42) != 1:
        failures.append(
            f"the gate asked the API about completed run 42 "
            f"{api.jobs_read_by_run_id.get(42)} times over "
            f"{POLLS_A_FULL_WAIT_BUDGET_SPENDS} polls; a completed run never changes its answer, "
            "and re-reading it spends the GITHUB_TOKEN rate limit that the wait already stretches"
        )
    if api.jobs_read_by_run_id.get(41, 0) <= 1:
        failures.append(
            "the gate asked the API about the run in flight only once, so it caches a run that has "
            "not answered yet and would wait out its whole budget over a stale reading"
        )
    return failures


def wait_input_classes() -> list[tuple[str, Decision, int]]:
    classes = []

    def resolved(name: str, api, history, starter=None) -> None:
        clock = ClockTheSelftestAdvancesOnEverySleep()
        decision = resolve(
            api,
            history,
            "base000",
            HEAD,
            SELFTEST_WAIT_BUDGET,
            starter,
            clock.now,
            clock.sleep,
        )
        classes.append((name, decision, starter.starts if starter else 0))

    in_flight = fanout_run(31, IN_FLIGHT_SHA, status="in_progress", conclusion="")
    proven = fanout_run(31, IN_FLIGHT_SHA, conclusion="success")
    covering_the_run_in_flight = FakeProducerFileHistory(
        {("base000", HEAD): TOUCHED_MAPPER, (IN_FLIGHT_SHA, HEAD): ()}
    )

    resolved(
        "the gate waits for the run in flight and reports the parity it proved",
        ApiThatReplaysOneRunListPerDecision(
            [[in_flight], [in_flight], [proven]], {31: fanout_jobs("success")}
        ),
        covering_the_run_in_flight,
    )
    resolved(
        "the gate waits for the run in flight and reports the parity defect it found",
        ApiThatReplaysOneRunListPerDecision(
            [[in_flight], [in_flight], [fanout_run(31, IN_FLIGHT_SHA)]],
            {31: fanout_jobs("failure", "failure")},
            {PARITY_JOB_ID_IN_THE_FAKES: playwright_log_naming(A_PRODUCER_SPEC)},
        ),
        covering_the_run_in_flight,
    )
    resolved(
        "the gate gives up on the run in flight when its wait budget runs out",
        ApiThatReplaysOneRunListPerDecision([[in_flight]], {31: []}),
        covering_the_run_in_flight,
    )

    older_red = fanout_run(32, OLDER_RED_SHA)
    fresh_in_flight = fanout_run(33, IN_FLIGHT_SHA, status="in_progress", conclusion="")
    fresh_proven = fanout_run(33, IN_FLIGHT_SHA, conclusion="success")
    resolved(
        "the gate starts a fan-out when the branch head moved past the run that refuses the merge",
        ApiThatReplaysOneRunListPerDecision(
            [
                [older_red],
                [older_red],
                [fresh_in_flight, older_red],
                [fresh_proven, older_red],
            ],
            {32: fanout_jobs("failure", "failure"), 33: fanout_jobs("success")},
            {PARITY_JOB_ID_IN_THE_FAKES: playwright_log_naming(A_PRODUCER_SPEC)},
        ),
        FakeProducerFileHistory(
            {
                ("base000", HEAD): TOUCHED_MAPPER,
                (OLDER_RED_SHA, HEAD): (),
                (IN_FLIGHT_SHA, HEAD): (),
            }
        ),
        StarterTheSelftestCounts(),
    )
    resolved(
        "the gate starts no fan-out when the run that refuses the merge ran on the branch head",
        ApiThatReplaysOneRunListPerDecision(
            [[fanout_run(34, HEAD)]],
            {34: fanout_jobs("failure", "failure")},
            {PARITY_JOB_ID_IN_THE_FAKES: playwright_log_naming(A_PRODUCER_SPEC)},
        ),
        FakeProducerFileHistory({("base000", HEAD): TOUCHED_MAPPER, (HEAD, HEAD): ()}),
        StarterTheSelftestCounts(),
    )
    resolved(
        "the gate starts a fan-out when no run covers, and refuses the merge when none appears",
        ApiThatReplaysOneRunListPerDecision([[]], {}),
        FakeProducerFileHistory({("base000", HEAD): TOUCHED_MAPPER}),
        StarterTheSelftestCounts(),
    )
    return classes


def captured_input_classes() -> list[tuple[str, Decision]]:
    if not RUNS_CAPTURED_FROM_THE_REAL_API.is_file():
        raise SystemExit(
            f"::error::{RUNS_CAPTURED_FROM_THE_REAL_API} is missing; the selftest scores real "
            "captured runs, and without them the reader has no regression case. Re-capture with "
            "--capture-fixture <run id> --input-class <name>."
        )
    fixtures = json.loads(RUNS_CAPTURED_FROM_THE_REAL_API.read_text(encoding="utf-8"))
    classes = []
    for input_class in fixtures["input_classes"]:
        captured_runs_newest_first = [
            fixtures["captured_runs"][run_id] for run_id in input_class["run_ids_newest_first"]
        ]
        covers = input_class["the_branch_head_changed_no_producer_file_after_these_runs"]
        producer_files_by_pair = {("base000", HEAD): TOUCHED_MAPPER}
        for captured in captured_runs_newest_first:
            producer_files_by_pair[(captured["run"]["head_sha"], HEAD)] = (
                () if covers else TOUCHED_MAPPER
            )
        classes.append(
            (
                input_class["name"],
                decide(
                    CapturedGithubActionsApi(captured_runs_newest_first),
                    FakeProducerFileHistory(producer_files_by_pair),
                    "base000",
                    HEAD,
                ),
            )
        )
    return classes


EXPECTED_SELFTEST_VERDICTS = {
    "a genuine parity mismatch": (PARITY_DEFECT, True),
    "a fan-out that could not build the legacy stack": (COULD_NOT_RUN, True),
    "a fan-out that broke before the parity specs": (COULD_NOT_RUN, True),
    "a fan-out that did not run at all": (NO_RUN_COVERS_THESE_PRODUCER_FILES, True),
    "a green fan-out that predates the producer edit": (NO_RUN_COVERS_THESE_PRODUCER_FILES, True),
    "a fan-out still running": (STILL_RUNNING, True),
    "a renamed fan-out job the reader cannot find": (JOB_NAMES_DRIFTED, True),
    "a renamed parity step the reader cannot find": (JOB_NAMES_DRIFTED, True),
    "a green fan-out over exactly these producer files": (PARITY_PROVEN, False),
    "a branch that changes no producer file": (NO_PRODUCER_FILE_CHANGED, False),
    "a parity step that stopped early at the failure cap": (PARITY_STEP_RED_SPEC_UNNAMED, True),
    "a parity step red on a spec and on a producer spec together": (PARITY_DEFECT, True),
    "a parity step red with an unreadable run log": (PARITY_STEP_RED_SPEC_UNNAMED, True),
    "an older covering run proves parity while the newest one is red at a later step": (
        PARITY_PROVEN,
        False,
    ),
    "a green fan-out job whose parity step never ran": (COULD_NOT_RUN, True),
    "both covering runs found a parity defect": (PARITY_DEFECT, True),
    "the newest covering run is red on a spec it cannot name and an older covering run proves parity": (
        PARITY_STEP_RED_SPEC_UNNAMED,
        True,
    ),
    "run 32102550688 — the parity step red on a producer spec": (PARITY_DEFECT, True),
    "run 32456112094 — the parity step red on a spec that asserts no migrated data": (
        UNRELATED_SPEC_RED,
        True,
    ),
    "run 30756910798 — the parity specs passed and a later step went red": (
        PARITY_PROVEN_RUN_RED_ELSEWHERE,
        True,
    ),
    "run 31123658151 — the legacy builds never finished": (COULD_NOT_RUN, True),
    "run 32450447026 — a green fan-out over these producer files": (PARITY_PROVEN, False),
    "run 32450447026 — a green fan-out that predates the producer edit": (
        NO_RUN_COVERS_THESE_PRODUCER_FILES,
        True,
    ),
    "run 30760024409 — a green fan-out that predates a chain that could not run": (
        PARITY_PROVEN,
        False,
    ),
    "run 31127036858 — a green fan-out over these producer files that predates a later defect": (
        PARITY_PROVEN,
        False,
    ),
    "runs 32102550688 over 31127036858 — the newest covering run found a parity defect and an "
    "older covering run proved parity": (PARITY_DEFECT, True),
    "runs 32450447026 over 32102550688 — the newest covering run proves parity and an older "
    "covering run found a parity defect": (PARITY_PROVEN, False),
    "runs 32450447026 over 31127036858 — both covering runs prove parity": (PARITY_PROVEN, False),
    "runs 32456112094 over 32450447026 — the newest covering run is red on a spec that asserts no "
    "migrated data and an older covering run proves parity": (UNRELATED_SPEC_RED, True),
    "runs 31123658151 over 30760024409 — the newest covering run could not run and an older "
    "covering run proves parity": (PARITY_PROVEN, False),
    "the newest covering run is still running and an older covering run found a parity defect": (
        A_RUN_IN_FLIGHT_SUPERSEDES_AN_OLDER_RED,
        True,
    ),
    "the newest covering run is still running and an older covering run proves parity": (
        PARITY_PROVEN,
        False,
    ),
    "the gate waits for the run in flight and reports the parity it proved": (PARITY_PROVEN, False),
    "the gate waits for the run in flight and reports the parity defect it found": (
        PARITY_DEFECT,
        True,
    ),
    "the gate gives up on the run in flight when its wait budget runs out": (STILL_RUNNING, True),
    "the gate starts a fan-out when the branch head moved past the run that refuses the merge": (
        PARITY_PROVEN,
        False,
    ),
    "the gate starts no fan-out when the run that refuses the merge ran on the branch head": (
        PARITY_DEFECT,
        True,
    ),
    "the gate starts a fan-out when no run covers, and refuses the merge when none appears": (
        NO_RUN_COVERS_THESE_PRODUCER_FILES,
        True,
    ),
}

EXPECTED_FAN_OUTS_THE_GATE_STARTS = {
    "the gate waits for the run in flight and reports the parity it proved": 0,
    "the gate waits for the run in flight and reports the parity defect it found": 0,
    "the gate gives up on the run in flight when its wait budget runs out": 0,
    "the gate starts a fan-out when the branch head moved past the run that refuses the merge": 1,
    "the gate starts no fan-out when the run that refuses the merge ran on the branch head": 0,
    "the gate starts a fan-out when no run covers, and refuses the merge when none appears": 1,
}


def selftest() -> int:
    failures = []

    job_names, step_names = job_and_step_names_of_the_fanout_workflow()
    bindings = [
        ("job", LEGACY_SERVER_BUILD_JOB_NAME, [n for n in job_names if n == LEGACY_SERVER_BUILD_JOB_NAME]),
        ("job", LEGACY_WEB_BUILD_JOB_NAME, [n for n in job_names if n == LEGACY_WEB_BUILD_JOB_NAME]),
        (
            "job",
            FANOUT_PARITY_JOB_NAME_PREFIX,
            [n for n in job_names if n.startswith(FANOUT_PARITY_JOB_NAME_PREFIX)],
        ),
        (
            "step",
            ALPENFLIGHT_PARITY_SPECS_STEP_NAME_PREFIX,
            [n for n in step_names if n.startswith(ALPENFLIGHT_PARITY_SPECS_STEP_NAME_PREFIX)],
        ),
        (
            "step",
            LEGACY_CREATE_FLOW_SPEC_STEP_NAME_PREFIX,
            [n for n in step_names if n.startswith(LEGACY_CREATE_FLOW_SPEC_STEP_NAME_PREFIX)],
        ),
    ]
    for kind, expected, matches in bindings:
        if len(matches) != 1:
            failures.append(
                f"{FANOUT_WORKFLOW} has {len(matches)} {kind}s named {expected!r}; the verdict "
                "reader needs exactly one, else it cannot tell a parity defect from a broken chain"
            )
        else:
            print(f"  ok    {kind} name bound to the workflow: {matches[0]}")

    driven = set(specs_the_parity_step_drives())
    classified = set(SPECS_OF_THE_PARITY_STEP_THAT_ASSERT_MIGRATED_DATA) | set(
        SPECS_OF_THE_PARITY_STEP_THAT_ASSERT_NO_MIGRATED_DATA
    )
    for spec in sorted(driven - classified):
        failures.append(
            f"the parity step drives {spec}, which this reader classifies neither as asserting "
            "migrated data nor as asserting none; until somebody classifies it, its red reads as a "
            "spec no parity step drives and the verdict names no cause"
        )
    for spec in sorted(classified - driven):
        failures.append(
            f"this reader classifies {spec}, which the parity step no longer drives; a stale "
            "classification makes the verdict name a spec the fan-out never ran"
        )
    for spec in sorted(classified):
        if not (PARITY_SPEC_ROOT_THE_STEP_RUNS_FROM / spec).is_file():
            failures.append(f"{PARITY_SPEC_ROOT_THE_STEP_RUNS_FROM / spec} does not exist")
    if set(SPECS_OF_THE_PARITY_STEP_THAT_ASSERT_MIGRATED_DATA) & set(
        SPECS_OF_THE_PARITY_STEP_THAT_ASSERT_NO_MIGRATED_DATA
    ):
        failures.append("a spec is classified as both asserting migrated data and asserting none")
    if not PARITY_SPEC_PLAYWRIGHT_CONFIG.is_file():
        failures.append(f"{PARITY_SPEC_PLAYWRIGHT_CONFIG} not found")
    elif REPORTER_THAT_PRINTS_THE_RED_SPEC_PATH_INTO_THE_RUN_LOG not in (
        PARITY_SPEC_PLAYWRIGHT_CONFIG.read_text(encoding="utf-8")
    ):
        failures.append(
            f"{PARITY_SPEC_PLAYWRIGHT_CONFIG} no longer configures the "
            f"{REPORTER_THAT_PRINTS_THE_RED_SPEC_PATH_INTO_THE_RUN_LOG} reporter, whose "
            "annotations name the red spec in the run log; without them every red parity step "
            "reads as PARITY_STEP_RED_SPEC_UNNAMED and the verdict names no cause"
        )
    else:
        print(
            "  ok    the reporter that prints the red spec path into the run log is configured: "
            f"{REPORTER_THAT_PRINTS_THE_RED_SPEC_PATH_INTO_THE_RUN_LOG} in "
            f"{PARITY_SPEC_PLAYWRIGHT_CONFIG}"
        )
    if driven and driven == classified:
        print(
            f"  ok    every one of the {len(driven)} specs the parity step drives is classified "
            f"({len(SPECS_OF_THE_PARITY_STEP_THAT_ASSERT_MIGRATED_DATA)} assert migrated data)"
        )

    arming_failures = the_fanout_arms_itself_failures()
    failures.extend(arming_failures)
    if not arming_failures:
        branches, paths = push_trigger_of_the_fanout_workflow()
        print(
            f"  ok    a push arms the fan-out on {', '.join(branches)} over "
            f"{len([path for path in paths if not path.startswith('!')])} producer path(s), and a "
            f"push-armed run reaches all {len(BRANCH_PREVIEW_STEP_NAMES_A_PUSH_RUN_MUST_ALSO_REACH)} "
            "branch-preview gallery steps"
        )

    rereading_failures = the_gate_asks_the_api_about_a_completed_run_once_failures()
    failures.extend(rereading_failures)
    if not rereading_failures:
        print(
            f"  ok    over {POLLS_A_FULL_WAIT_BUDGET_SPENDS} polls the gate asks the API about a "
            "completed run once and about the run in flight every time"
        )

    fan_outs_started_by_input_class = {}
    messages_by_verdict = {}
    message_by_input_class = {}
    waited = wait_input_classes()
    for name, _, fan_outs_started in waited:
        fan_outs_started_by_input_class[name] = fan_outs_started
    scored = (
        synthetic_input_classes()
        + captured_input_classes()
        + [(name, decision) for name, decision, _ in waited]
    )
    for name, decision in scored:
        if name not in EXPECTED_SELFTEST_VERDICTS:
            failures.append(f"{name}: no expected verdict is recorded for this input class")
            continue
        expected_verdict, expected_blocks = EXPECTED_SELFTEST_VERDICTS[name]
        if decision.verdict != expected_verdict:
            failures.append(f"{name}: verdict {decision.verdict}, expected {expected_verdict}")
            continue
        if decision.blocks != expected_blocks:
            failures.append(
                f"{name}: blocks={decision.blocks}, expected blocks={expected_blocks}"
            )
            continue
        expected_fan_outs = EXPECTED_FAN_OUTS_THE_GATE_STARTS.get(name)
        if expected_fan_outs is not None and (
            fan_outs_started_by_input_class.get(name) != expected_fan_outs
        ):
            failures.append(
                f"{name}: the gate started {fan_outs_started_by_input_class.get(name)} fan-out(s), "
                f"expected {expected_fan_outs}"
            )
            continue
        message_by_input_class[name] = render(decision)
        messages_by_verdict.setdefault(decision.verdict, message_by_input_class[name])
        print(f"  ok    {name:<74} {decision.verdict} blocks={decision.blocks}")
    for name in EXPECTED_SELFTEST_VERDICTS:
        if name not in {scored_name for scored_name, _ in scored}:
            failures.append(f"{name}: an expected verdict is recorded, but no input class scores it")
    for name in EXPECTED_FAN_OUTS_THE_GATE_STARTS:
        if name not in fan_outs_started_by_input_class:
            failures.append(
                f"{name}: an expected fan-out count is recorded, but no input class scores it"
            )

    distinct_headlines = {VERDICT_HEADLINE[v] for v in messages_by_verdict}
    if len(distinct_headlines) != len(messages_by_verdict):
        failures.append(
            "two verdicts render the same headline, so a developer cannot tell a parity defect "
            "from a chain that could not run"
        )
    for verdict in (
        PARITY_DEFECT,
        UNRELATED_SPEC_RED,
        PARITY_STEP_RED_SPEC_UNNAMED,
        PARITY_PROVEN_RUN_RED_ELSEWHERE,
        COULD_NOT_RUN,
        A_RUN_IN_FLIGHT_SUPERSEDES_AN_OLDER_RED,
    ):
        if verdict in messages_by_verdict and RESIDUAL_LIMIT not in messages_by_verdict[verdict]:
            failures.append(f"{verdict} renders no residual limit")
    if UNRELATED_SPEC_RED in messages_by_verdict:
        message = messages_by_verdict[UNRELATED_SPEC_RED]
        if "producer binding" not in message or "Do not hunt a mapper defect" not in message:
            failures.append(
                "the unrelated-spec verdict does not tell the developer that no mapper is at fault"
            )
    if PARITY_PROVEN_RUN_RED_ELSEWHERE in messages_by_verdict:
        message = messages_by_verdict[PARITY_PROVEN_RUN_RED_ELSEWHERE]
        if "red steps beside the parity step:" not in message:
            failures.append(
                "the parity-proven-run-red-elsewhere verdict names no red step, so the red that "
                "blocks the merge stays invisible"
            )
        if "the parity step passed" not in message:
            failures.append(
                "the parity-proven-run-red-elsewhere verdict does not state that parity holds, so "
                "it still denies a proven parity"
            )

    for name, message in message_by_input_class.items():
        if HOW_THE_FANOUT_ARMS_ITSELF not in message:
            failures.append(
                f"{name}: the message does not state that a git push arms the fan-out, so a reader "
                "who is blocked cannot tell whether somebody must dispatch a run by hand"
            )
    not_required = message_by_input_class.get("a branch that changes no producer file", "")
    if not_required:
        if "NOT REQUIRED" not in not_required:
            failures.append(
                "a branch that changes no producer file does not read as NOT REQUIRED, so a "
                "skipped fan-out can pass for a proven parity"
            )
        for claim in ("proved parity", "PARITY_PROVEN"):
            if claim in not_required:
                failures.append(
                    f"a branch that changes no producer file claims {claim!r}; no fan-out judged "
                    "this change, so the gate must report that it proves nothing, never a green"
                )
    superseded = message_by_input_class.get(
        "the newest covering run is still running and an older covering run found a parity defect",
        "",
    )
    if superseded:
        if "supersedes" not in superseded or "still running" not in superseded.lower():
            failures.append(
                "the transient verdict does not name the run in flight, so the reader is sent to "
                "an older failed run over a defect the commit under test may already have repaired"
            )
        if A_PRODUCER_SPEC not in superseded:
            failures.append(
                "the transient verdict hides what the superseded run found, so a real parity "
                "defect becomes invisible while the run in flight finishes"
            )

    masked_red = message_by_input_class.get(
        "an older covering run proves parity while the newest one is red at a later step", ""
    )
    if masked_red and PARITY_PROVEN_RUN_RED_ELSEWHERE not in masked_red:
        failures.append(
            "an older green run hides the newest covering run's red without naming it, so a red "
            "that arrived after the green never reaches a human"
        )

    newest_run_found_a_defect = message_by_input_class.get(
        "runs 32102550688 over 31127036858 — the newest covering run found a parity defect and an "
        "older covering run proved parity",
        "",
    )
    if newest_run_found_a_defect and (
        PRECEDENCE_WHEN_COVERING_RUNS_DISAGREE not in newest_run_found_a_defect
    ):
        failures.append(
            "the message that refuses the merge does not state which covering run decides, so a "
            "developer cannot tell whether a newer green fan-out clears the red"
        )

    now = datetime(2026, 8, 21, 12, 0, tzinfo=timezone.utc)
    yesterdays_run_age = days_since("2026-08-20T05:22:38Z", now)
    week_old_run_age = days_since("2026-08-14T06:07:37Z", now)
    if yesterdays_run_age > SCHEDULED_FANOUT_IS_STALE_AFTER_DAYS:
        failures.append("a run from yesterday reads as a stopped schedule")
    if week_old_run_age <= SCHEDULED_FANOUT_IS_STALE_AFTER_DAYS:
        failures.append("a week-old newest run on main does not read as a stopped schedule")
    else:
        print(f"  ok    a stopped schedule reads stale at {week_old_run_age:.1f} days")

    if failures:
        print("::error title=fan-out verdict selftest failed::" + failures[0], file=sys.stderr)
        for failure in failures:
            print(f"  FAIL  {failure}", file=sys.stderr)
        return 1
    print(
        f"fan-out verdict selftest: ok ({len(scored)} input classes, of which "
        f"{len(captured_input_classes())} score runs captured from the real API; "
        f"{len(bindings)} names and {len(driven)} spec paths bound to {FANOUT_WORKFLOW})"
    )
    return 0


def capture_fixture(
    repository: str, run_id: int, input_class: str, run_still_covers_the_branch_head: bool
) -> int:
    api = GithubActionsApi(repository)
    run = api.run_by_id(run_id)
    jobs = api.jobs_of(run_id)
    trimmed_jobs = [
        {
            "name": job.get("name"),
            "id": job.get("id"),
            "conclusion": job.get("conclusion"),
            "steps": [
                {
                    "number": step.get("number"),
                    "name": step.get("name"),
                    "conclusion": step.get("conclusion"),
                }
                for step in job.get("steps", [])
            ],
        }
        for job in jobs
    ]
    parity_job = next(
        (job for job in jobs if str(job.get("name", "")).startswith(FANOUT_PARITY_JOB_NAME_PREFIX)),
        None,
    )
    log_lines = []
    if parity_job is not None:
        for line in api.job_log(parity_job["id"]).splitlines():
            names_a_red = any(
                pattern.search(line) for pattern in RED_SPEC_PATTERNS_OF_THE_PLAYWRIGHT_REPORTERS
            )
            if names_a_red or PLAYWRIGHT_ABANDONED_THE_REMAINING_SPECS in line:
                log_lines.append(line[:CAPTURED_LOG_LINE_PREFIX_THE_SPEC_PATH_FITS_IN])

    fixtures = (
        json.loads(RUNS_CAPTURED_FROM_THE_REAL_API.read_text(encoding="utf-8"))
        if RUNS_CAPTURED_FROM_THE_REAL_API.is_file()
        else {"captured_runs": {}, "input_classes": []}
    )
    fixtures["captured_runs"][str(run_id)] = {
        "run": {
            "run_id": run.run_id,
            "head_sha": run.head_sha,
            "status": run.status,
            "conclusion": run.conclusion,
            "created_at": run.created_at,
            "html_url": run.html_url,
            "head_branch": run.head_branch,
        },
        "jobs": trimmed_jobs,
        "parity_job_log_lines_that_name_a_red_spec_or_an_early_stop": log_lines,
    }
    fixtures["input_classes"] = [
        entry for entry in fixtures["input_classes"] if entry["name"] != input_class
    ] + [
        {
            "name": input_class,
            "run_ids_newest_first": [str(run_id)],
            "the_branch_head_changed_no_producer_file_after_these_runs": run_still_covers_the_branch_head,
        }
    ]
    fixtures["input_classes"].sort(key=lambda entry: entry["name"])
    RUNS_CAPTURED_FROM_THE_REAL_API.write_text(
        json.dumps(fixtures, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(
        f"captured run {run_id} as input class {input_class!r} "
        f"({len(trimmed_jobs)} jobs, {len(log_lines)} log lines) into "
        f"{RUNS_CAPTURED_FROM_THE_REAL_API}"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("--report-latest-verdict-on-main", action="store_true")
    parser.add_argument("--capture-fixture", type=int)
    parser.add_argument("--input-class")
    parser.add_argument("--the-run-still-covers-the-branch-head", action="store_true")
    parser.add_argument("--base-sha")
    parser.add_argument("--head-sha")
    parser.add_argument("--start-a-fanout-on-branch", default="")
    parser.add_argument("--minutes-until-a-covering-run-must-appear", type=float, default=8.0)
    parser.add_argument("--minutes-until-the-covering-run-must-finish", type=float, default=45.0)
    parser.add_argument("--seconds-between-polls", type=float, default=30.0)
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY", ""))
    arguments = parser.parse_args()

    if arguments.selftest:
        return selftest()
    if not arguments.repository:
        print("::error::pass --repository or set GITHUB_REPOSITORY", file=sys.stderr)
        return 1
    if arguments.capture_fixture:
        if not arguments.input_class:
            print("::error::pass --input-class with --capture-fixture", file=sys.stderr)
            return 1
        return capture_fixture(
            arguments.repository,
            arguments.capture_fixture,
            arguments.input_class,
            arguments.the_run_still_covers_the_branch_head,
        )
    if arguments.report_latest_verdict_on_main:
        return report_latest_verdict_on_main(arguments.repository)
    if not arguments.base_sha or not arguments.head_sha:
        print("::error::pass --base-sha and --head-sha", file=sys.stderr)
        return 1
    return gate(
        arguments.base_sha,
        arguments.head_sha,
        arguments.repository,
        WaitBudget(
            arguments.minutes_until_a_covering_run_must_appear,
            arguments.minutes_until_the_covering_run_must_finish,
            arguments.seconds_between_polls,
        ),
        arguments.start_a_fanout_on_branch,
    )


if __name__ == "__main__":
    sys.exit(main())
