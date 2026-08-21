#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
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

PARITY_PROVEN = "PARITY_PROVEN"
PARITY_DEFECT = "PARITY_DEFECT"
UNRELATED_SPEC_RED = "UNRELATED_SPEC_RED"
PARITY_STEP_RED_SPEC_UNNAMED = "PARITY_STEP_RED_SPEC_UNNAMED"
PARITY_PROVEN_RUN_RED_ELSEWHERE = "PARITY_PROVEN_RUN_RED_ELSEWHERE"
COULD_NOT_RUN = "COULD_NOT_RUN"
STILL_RUNNING = "STILL_RUNNING"
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
    JOB_NAMES_DRIFTED: "the verdict reader COULD NOT FIND the fan-out job or its parity step",
    NO_RUN_COVERS_THESE_PRODUCER_FILES: "NO fan-out run covers the producer files this branch changes",
    NO_PRODUCER_FILE_CHANGED: "this branch changes no producer file, so the fan-out gates nothing here",
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
    STILL_RUNNING: "Wait for the run to complete, then push again or re-run this gate.",
    JOB_NAMES_DRIFTED: (
        "Somebody renamed a job or a step of the fan-out workflow. Update the names this script "
        "binds and re-run its selftest, which pins every name against the workflow file."
    ),
    NO_RUN_COVERS_THESE_PRODUCER_FILES: (
        "Dispatch the fan-out on this branch and wait for it: "
        f"gh workflow run {FANOUT_WORKFLOW_FILE_NAME} --ref <this branch>. "
        "A run counts once its commit is an ancestor of this branch head and no producer file "
        "changed after it."
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


def decide(
    api,
    history,
    base_sha: str,
    head_sha: str,
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
    for run in candidates:
        reading = read_run(api, run, api.jobs_of(run.run_id))
        if newest_reading is None:
            newest_run, newest_reading = run, reading
        if reading.verdict == PARITY_PROVEN:
            return Decision(
                PARITY_PROVEN, False, producer_files, run, reading, newest_run, newest_reading
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
    if (
        decision.newest_reading is not None
        and decision.newest_reading is not reading
        and decision.newest_reading.names_a_red()
    ):
        lines.append(
            f"the newest covering run {decision.newest_run.run_id} reads "
            f"{decision.newest_reading.verdict}, and an older covering run proves parity: "
            f"{decision.newest_run.html_url}"
        )
    if decision.verdict in VERDICT_WHAT_TO_DO:
        lines.append(VERDICT_WHAT_TO_DO[decision.verdict])
    lines.append(RESIDUAL_LIMIT)
    return "\n".join(lines)


def write_step_summary(text: str) -> None:
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as summary:
            summary.write(text + "\n\n")


def gate(base_sha: str, head_sha: str, repository: str) -> int:
    decision = decide(GithubActionsApi(repository), ProducerFileHistory(), base_sha, head_sha)
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
            "::warning title=the newest covering fan-out run is red "
            f"({decision.newest_reading.verdict})::" + report.replace("\n", " ")
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
    def __init__(self, captured: dict):
        self.captured = captured

    def fanout_runs_newest_first(self) -> list[FanoutRun]:
        return [FanoutRun(**self.captured["run"])]

    def jobs_of(self, run_id: int) -> list[dict]:
        return self.captured["jobs"]

    def job_log(self, job_id: int) -> str:
        return "\n".join(self.captured["parity_job_log_lines_that_name_a_red_spec_or_an_early_stop"])


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
        captured = fixtures["captured_runs"][input_class["run_id"]]
        head_sha = captured["run"]["head_sha"]
        covers = input_class["the_branch_head_changed_no_producer_file_after_this_run"]
        history = FakeProducerFileHistory(
            {
                ("base000", HEAD): TOUCHED_MAPPER,
                (head_sha, HEAD): () if covers else TOUCHED_MAPPER,
            }
        )
        classes.append(
            (
                input_class["name"],
                decide(CapturedGithubActionsApi(captured), history, "base000", HEAD),
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

    messages_by_verdict = {}
    message_by_input_class = {}
    scored = synthetic_input_classes() + captured_input_classes()
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
        message_by_input_class[name] = render(decision)
        messages_by_verdict.setdefault(decision.verdict, message_by_input_class[name])
        print(f"  ok    {name:<74} {decision.verdict} blocks={decision.blocks}")
    for name in EXPECTED_SELFTEST_VERDICTS:
        if name not in {scored_name for scored_name, _ in scored}:
            failures.append(f"{name}: an expected verdict is recorded, but no input class scores it")

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

    masked_red = message_by_input_class.get(
        "an older covering run proves parity while the newest one is red at a later step", ""
    )
    if masked_red and PARITY_PROVEN_RUN_RED_ELSEWHERE not in masked_red:
        failures.append(
            "an older green run hides the newest covering run's red without naming it, so a red "
            "that arrived after the green never reaches a human"
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
            "run_id": str(run_id),
            "the_branch_head_changed_no_producer_file_after_this_run": run_still_covers_the_branch_head,
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
    return gate(arguments.base_sha, arguments.head_sha, arguments.repository)


if __name__ == "__main__":
    sys.exit(main())
