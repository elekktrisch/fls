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

LEGACY_SERVER_BUILD_JOB_NAME = "legacy server build"
LEGACY_WEB_BUILD_JOB_NAME = "legacy web build"
FANOUT_PARITY_JOB_NAME_PREFIX = "fan-out parity"
ALPENFLIGHT_PARITY_SPECS_STEP_NAME_PREFIX = "Run AlpenFlight parity specs"
LEGACY_CREATE_FLOW_SPEC_STEP_NAME_PREFIX = "Run T-04 legacy create-flow spec"

PRODUCER_PATHS_ONLY_THE_REAL_FANOUT_VALIDATES = (
    "alpenflight/migration-bundle",
    "alpenflight/migration-tool",
)

RESIDUAL_LIMIT = (
    "RESIDUAL LIMIT — this gate arms only on "
    f"{' and '.join(PRODUCER_PATHS_ONLY_THE_REAL_FANOUT_VALIDATES)}, the two trees whose legacy "
    "SELECT no synthetic bundle exercises. A change to the server-side ingest, to a Flyway "
    "migration or to a parity spec also reaches the real fan-out and does NOT arm this gate. The "
    "gate reads job and step results only: it cannot see a spec the fan-out's command never "
    "selected, and it cannot see a step the fan-out swallowed with continue-on-error."
)

PARITY_PROVEN = "PARITY_PROVEN"
PARITY_DEFECT = "PARITY_DEFECT"
COULD_NOT_RUN = "COULD_NOT_RUN"
STILL_RUNNING = "STILL_RUNNING"
JOB_NAMES_DRIFTED = "JOB_NAMES_DRIFTED"
NO_RUN_COVERS_THESE_PRODUCER_FILES = "NO_RUN_COVERS_THESE_PRODUCER_FILES"
NO_PRODUCER_FILE_CHANGED = "NO_PRODUCER_FILE_CHANGED"

VERDICT_HEADLINE = {
    PARITY_PROVEN: "the fan-out proved parity over exactly these producer files",
    PARITY_DEFECT: "the fan-out FOUND A PARITY DEFECT — the AlpenFlight parity specs went red",
    COULD_NOT_RUN: "the fan-out COULD NOT RUN — it never reached the AlpenFlight parity specs",
    STILL_RUNNING: "the fan-out is STILL RUNNING over these producer files",
    JOB_NAMES_DRIFTED: "the verdict reader COULD NOT FIND the fan-out job or its parity step",
    NO_RUN_COVERS_THESE_PRODUCER_FILES: "NO fan-out run covers the producer files this branch changes",
    NO_PRODUCER_FILE_CHANGED: "this branch changes no producer file, so the fan-out gates nothing here",
}

VERDICT_WHAT_TO_DO = {
    PARITY_DEFECT: (
        "Read the failed run's AlpenFlight parity specs. A real legacy export drove them, so the "
        "red is a producer binding, drift, dedupe or foreign-key defect, not a harness fault."
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
class Decision:
    verdict: str
    blocks: bool
    producer_files: tuple[str, ...]
    run: FanoutRun | None


class GithubActionsApi:
    def __init__(self, repository: str):
        self.repository = repository

    def _api(self, path: str) -> dict:
        completed = subprocess.run(
            ["gh", "api", "-H", "Accept: application/vnd.github+json", path],
            capture_output=True,
            text=True,
        )
        if completed.returncode != 0:
            raise SystemExit(
                f"::error title=fan-out verdict unreadable::gh api {path} exited "
                f"{completed.returncode}: {completed.stderr.strip()}"
            )
        return json.loads(completed.stdout)

    def fanout_runs_newest_first(self) -> list[FanoutRun]:
        payload = self._api(
            f"repos/{self.repository}/actions/workflows/{FANOUT_WORKFLOW_FILE_NAME}"
            "/runs?per_page=100"
        )
        return [
            FanoutRun(
                run_id=run["id"],
                head_sha=run["head_sha"],
                status=run.get("status", ""),
                conclusion=run.get("conclusion") or "",
                created_at=run.get("created_at", ""),
                html_url=run.get("html_url", ""),
                head_branch=run.get("head_branch", ""),
            )
            for run in payload.get("workflow_runs", [])
        ]

    def jobs_of(self, run_id: int) -> list[dict]:
        return self._api(f"repos/{self.repository}/actions/runs/{run_id}/jobs?per_page=100").get(
            "jobs", []
        )


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


def classify_run(run: FanoutRun, jobs: list[dict]) -> str:
    if run.status != "completed":
        return STILL_RUNNING
    parity_job = next(
        (job for job in jobs if str(job.get("name", "")).startswith(FANOUT_PARITY_JOB_NAME_PREFIX)),
        None,
    )
    if parity_job is None:
        return JOB_NAMES_DRIFTED
    if parity_job.get("conclusion") == "success":
        return PARITY_PROVEN
    if parity_job.get("conclusion") != "failure":
        return COULD_NOT_RUN
    conclusion_by_job_name = {str(job.get("name", "")): job.get("conclusion") for job in jobs}
    for build_job_name in (LEGACY_SERVER_BUILD_JOB_NAME, LEGACY_WEB_BUILD_JOB_NAME):
        if conclusion_by_job_name.get(build_job_name) != "success":
            return COULD_NOT_RUN
    parity_step = next(
        (
            step
            for step in parity_job.get("steps", [])
            if str(step.get("name", "")).startswith(ALPENFLIGHT_PARITY_SPECS_STEP_NAME_PREFIX)
        ),
        None,
    )
    if parity_step is None:
        return JOB_NAMES_DRIFTED
    if parity_step.get("conclusion") == "failure":
        return PARITY_DEFECT
    return COULD_NOT_RUN


MOST_RECENT_RUNS_THE_GATE_ASKS_THE_API_ABOUT = 12


def decide(
    api: GithubActionsApi,
    history: ProducerFileHistory,
    base_sha: str,
    head_sha: str,
) -> Decision:
    producer_files = history.producer_files_changed_since_merge_base(base_sha, head_sha)
    if not producer_files:
        return Decision(NO_PRODUCER_FILE_CHANGED, False, producer_files, None)

    candidates = [
        run
        for run in api.fanout_runs_newest_first()
        if history.commit_is_reachable_from_head(run.head_sha, head_sha)
        and not history.producer_files_changed_between(run.head_sha, head_sha)
    ][:MOST_RECENT_RUNS_THE_GATE_ASKS_THE_API_ABOUT]

    if not candidates:
        return Decision(NO_RUN_COVERS_THESE_PRODUCER_FILES, True, producer_files, None)

    newest_verdict, newest_run = None, None
    for run in candidates:
        verdict = classify_run(run, api.jobs_of(run.run_id))
        if verdict == PARITY_PROVEN:
            return Decision(PARITY_PROVEN, False, producer_files, run)
        if newest_verdict is None:
            newest_verdict, newest_run = verdict, run
    return Decision(newest_verdict, True, producer_files, newest_run)


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
    verdict = classify_run(latest, api.jobs_of(latest.run_id))
    age_in_days = days_since(latest.created_at, datetime.now(timezone.utc))
    line = (
        f"newest fan-out on main: {verdict} — {VERDICT_HEADLINE[verdict]} "
        f"(run {latest.run_id}, {latest.created_at}, {age_in_days:.1f} days old, {latest.html_url})"
    )
    print(line)
    write_step_summary(f"### fan-out on main\n\n{line}")
    if verdict != PARITY_PROVEN:
        print(
            f"::warning title=the fan-out on main is not green ({verdict})::{line} "
            f"{VERDICT_WHAT_TO_DO.get(verdict, '')}"
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


class FakeGithubActionsApi:
    def __init__(self, runs: list[FanoutRun], jobs_by_run_id: dict[int, list[dict]]):
        self.runs = runs
        self.jobs_by_run_id = jobs_by_run_id

    def fanout_runs_newest_first(self) -> list[FanoutRun]:
        return self.runs

    def jobs_of(self, run_id: int) -> list[dict]:
        return self.jobs_by_run_id[run_id]


class FakeProducerFileHistory:
    def __init__(self, producer_files_by_pair: dict[tuple[str, str], tuple[str, ...]]):
        self.producer_files_by_pair = producer_files_by_pair

    def commit_is_reachable_from_head(self, sha: str, head_sha: str) -> bool:
        return (sha, head_sha) in self.producer_files_by_pair

    def producer_files_changed_between(self, earlier: str, later: str) -> tuple[str, ...]:
        return self.producer_files_by_pair[(earlier, later)]

    def producer_files_changed_since_merge_base(self, base: str, head: str) -> tuple[str, ...]:
        return self.producer_files_by_pair[(base, head)]


def fanout_jobs(
    parity_conclusion: str,
    parity_step_conclusion: str = "success",
    legacy_server_build_conclusion: str = "success",
    legacy_web_build_conclusion: str = "success",
    parity_job_name: str = "fan-out parity (legacy → migrate+Keycloak → AlpenFlight)",
    parity_step_name: str = "Run AlpenFlight parity specs (J-0c real-bundle + J-1 aircraft)",
) -> list[dict]:
    return [
        {"name": LEGACY_SERVER_BUILD_JOB_NAME, "conclusion": legacy_server_build_conclusion},
        {"name": LEGACY_WEB_BUILD_JOB_NAME, "conclusion": legacy_web_build_conclusion},
        {
            "name": parity_job_name,
            "conclusion": parity_conclusion,
            "steps": [
                {"name": LEGACY_CREATE_FLOW_SPEC_STEP_NAME_PREFIX, "conclusion": "success"},
                {"name": parity_step_name, "conclusion": parity_step_conclusion},
            ],
        },
    ]


def fanout_run(
    run_id: int, head_sha: str, status: str = "completed", conclusion: str = "failure"
) -> FanoutRun:
    return FanoutRun(run_id, head_sha, status, conclusion, "2026-08-21T05:00:00Z", "https://run", "main")


HEAD = "head000"
PROVEN_SHA = "proven0"
TOUCHED_MAPPER = ("alpenflight/migration-bundle/src/main/java/AccountingRuleFilterMapper.java",)


def selftest_input_classes() -> list[tuple[str, Decision]]:
    classes = []

    def scored(name: str, api, history) -> None:
        classes.append((name, decide(api, history, "base000", HEAD)))

    scored(
        "a genuine parity mismatch",
        FakeGithubActionsApi([fanout_run(1, PROVEN_SHA)], {1: fanout_jobs("failure", "failure")}),
        FakeProducerFileHistory({("base000", HEAD): TOUCHED_MAPPER, (PROVEN_SHA, HEAD): ()}),
    )
    scored(
        "a fan-out that could not build the legacy stack",
        FakeGithubActionsApi(
            [fanout_run(2, PROVEN_SHA)],
            {2: fanout_jobs("skipped", "skipped", legacy_server_build_conclusion="failure")},
        ),
        FakeProducerFileHistory({("base000", HEAD): TOUCHED_MAPPER, (PROVEN_SHA, HEAD): ()}),
    )
    scored(
        "a fan-out that broke before the parity specs",
        FakeGithubActionsApi([fanout_run(3, PROVEN_SHA)], {3: fanout_jobs("failure", "skipped")}),
        FakeProducerFileHistory({("base000", HEAD): TOUCHED_MAPPER, (PROVEN_SHA, HEAD): ()}),
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
        FakeProducerFileHistory({("base000", HEAD): TOUCHED_MAPPER, (PROVEN_SHA, HEAD): ()}),
    )
    scored(
        "a renamed fan-out job the reader cannot find",
        FakeGithubActionsApi(
            [fanout_run(6, PROVEN_SHA)], {6: fanout_jobs("failure", "failure", parity_job_name="proof chain")}
        ),
        FakeProducerFileHistory({("base000", HEAD): TOUCHED_MAPPER, (PROVEN_SHA, HEAD): ()}),
    )
    scored(
        "a renamed parity step the reader cannot find",
        FakeGithubActionsApi(
            [fanout_run(7, PROVEN_SHA)],
            {7: fanout_jobs("failure", "failure", parity_step_name="Run the specs")},
        ),
        FakeProducerFileHistory({("base000", HEAD): TOUCHED_MAPPER, (PROVEN_SHA, HEAD): ()}),
    )
    scored(
        "a green fan-out over exactly these producer files",
        FakeGithubActionsApi([fanout_run(8, PROVEN_SHA, conclusion="success")], {8: fanout_jobs("success")}),
        FakeProducerFileHistory({("base000", HEAD): TOUCHED_MAPPER, (PROVEN_SHA, HEAD): ()}),
    )
    scored(
        "a branch that changes no producer file",
        FakeGithubActionsApi([], {}),
        FakeProducerFileHistory({("base000", HEAD): ()}),
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

    messages_by_verdict = {}
    for name, decision in selftest_input_classes():
        expected_verdict, expected_blocks = EXPECTED_SELFTEST_VERDICTS[name]
        if decision.verdict != expected_verdict:
            failures.append(f"{name}: verdict {decision.verdict}, expected {expected_verdict}")
            continue
        if decision.blocks != expected_blocks:
            failures.append(
                f"{name}: blocks={decision.blocks}, expected blocks={expected_blocks}"
            )
            continue
        messages_by_verdict.setdefault(decision.verdict, render(decision))
        print(f"  ok    {name:<52} {decision.verdict} blocks={decision.blocks}")

    distinct_headlines = {VERDICT_HEADLINE[v] for v in messages_by_verdict}
    if len(distinct_headlines) != len(messages_by_verdict):
        failures.append(
            "two verdicts render the same headline, so a developer cannot tell a parity defect "
            "from a chain that could not run"
        )
    for verdict in (PARITY_DEFECT, COULD_NOT_RUN):
        if verdict in messages_by_verdict and RESIDUAL_LIMIT not in messages_by_verdict[verdict]:
            failures.append(f"{verdict} renders no residual limit")

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
        f"fan-out verdict selftest: ok ({len(EXPECTED_SELFTEST_VERDICTS)} input classes; "
        f"{len(bindings)} names bound to {FANOUT_WORKFLOW})"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("--report-latest-verdict-on-main", action="store_true")
    parser.add_argument("--base-sha")
    parser.add_argument("--head-sha")
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY", ""))
    arguments = parser.parse_args()

    if arguments.selftest:
        return selftest()
    if not arguments.repository:
        print("::error::pass --repository or set GITHUB_REPOSITORY", file=sys.stderr)
        return 1
    if arguments.report_latest_verdict_on_main:
        return report_latest_verdict_on_main(arguments.repository)
    if not arguments.base_sha or not arguments.head_sha:
        print("::error::pass --base-sha and --head-sha", file=sys.stderr)
        return 1
    return gate(arguments.base_sha, arguments.head_sha, arguments.repository)


if __name__ == "__main__":
    sys.exit(main())
