#!/usr/bin/env python3

import argparse
import base64
import io
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

from gh_pages_payload import (
    megabytes,
    payload_bytes_of_published_checkout,
    plant_git_metadata_github_pages_never_publishes,
)

LEGACY_REPORT_DIR = "legacy/report"
LEGACY_REPORT_ATTACHMENT_DIR = "legacy/report/data"
PROOF_PREVIEW_DIR = "alpenflight/proof-preview"
RETIRED_FANOUT_DESTINATIONS_NO_WORKFLOW_WRITES_ANYMORE = (
    "alpenflight/proof/legacy-parity",
    "alpenflight/proof/j-0c-fanout",
)
BRANCH_NAME_TO_PREVIEW_SUBDIR_SANITIZER = re.compile(r"[^A-Za-z0-9._-]")
PLAYWRIGHT_REPORT_BASE64_PAYLOAD = re.compile(
    r'id="playwrightReportBase64"\s*>\s*data:application/zip;base64,([A-Za-z0-9+/=]+)'
)
SHA1_NAMED_ATTACHMENT_FILE = re.compile(r"[0-9a-f]{40}\.[A-Za-z0-9]+")


def attachment_names_the_published_report_still_references(site: Path) -> set[str]:
    referenced: set[str] = set()
    report_dir = site / LEGACY_REPORT_DIR
    html_files = [
        f
        for f in report_dir.glob("*.html")
        if f.is_file()
    ]
    if not html_files:
        raise FileNotFoundError(
            f"{report_dir}/*.html is absent, so no attachment can be proven unreachable"
        )
    for html_file in html_files:
        html = html_file.read_text(encoding="utf8", errors="replace")
        referenced.update(SHA1_NAMED_ATTACHMENT_FILE.findall(html))
        payload = PLAYWRIGHT_REPORT_BASE64_PAYLOAD.search(html)
        if not payload:
            continue
        report_zip = zipfile.ZipFile(io.BytesIO(base64.b64decode(payload.group(1))))
        for entry in report_zip.namelist():
            entry_text = report_zip.read(entry).decode("utf8", errors="replace")
            referenced.update(SHA1_NAMED_ATTACHMENT_FILE.findall(entry_text))
    return referenced


def delete_legacy_report_attachments_no_published_report_references(
    site: Path, deletions: list[str]
) -> None:
    attachment_dir = site / LEGACY_REPORT_ATTACHMENT_DIR
    if not attachment_dir.is_dir():
        return
    try:
        referenced = attachment_names_the_published_report_still_references(site)
    except (FileNotFoundError, zipfile.BadZipFile, ValueError) as unreadable_report:
        print(
            f"::warning::keeping every {LEGACY_REPORT_ATTACHMENT_DIR} file — {unreadable_report}"
        )
        return
    for attachment in sorted(attachment_dir.iterdir()):
        if attachment.is_file() and attachment.name not in referenced:
            attachment.unlink()
            deletions.append(f"{LEGACY_REPORT_ATTACHMENT_DIR}/{attachment.name}")


def sanitized_names_of_every_branch_on_origin(repo_with_the_branches: Path) -> set[str]:
    listed = subprocess.run(
        ["git", "ls-remote", "--heads", "origin"],
        cwd=repo_with_the_branches,
        capture_output=True,
        text=True,
        check=True,
    )
    return {
        BRANCH_NAME_TO_PREVIEW_SUBDIR_SANITIZER.sub("-", line.split("refs/heads/", 1)[1])
        for line in listed.stdout.splitlines()
        if "refs/heads/" in line
    }


def delete_previews_whose_branch_no_longer_exists(
    site: Path, repo_with_the_branches: Path, deletions: list[str]
) -> None:
    preview_dir = site / PROOF_PREVIEW_DIR
    if not preview_dir.is_dir():
        return
    try:
        live = sanitized_names_of_every_branch_on_origin(repo_with_the_branches)
    except subprocess.CalledProcessError as unreachable_origin:
        print(f"::warning::keeping every preview — origin is unreachable: {unreachable_origin}")
        return
    if not live:
        print("::warning::keeping every preview — origin listed no branch at all")
        return
    for preview in sorted(preview_dir.iterdir()):
        if preview.is_dir() and preview.name not in live:
            shutil.rmtree(preview)
            deletions.append(f"{PROOF_PREVIEW_DIR}/{preview.name}/")


def workflows_that_still_publish(destination: str, workflow_dir: Path) -> list[str]:
    if not workflow_dir.is_dir():
        return []
    return [
        str(workflow)
        for workflow in sorted(workflow_dir.glob("*.yml"))
        if f"destination_dir: {destination}" in workflow.read_text(encoding="utf8")
    ]


def delete_retired_fanout_destinations(
    site: Path, workflow_dir: Path, deletions: list[str]
) -> None:
    for destination in RETIRED_FANOUT_DESTINATIONS_NO_WORKFLOW_WRITES_ANYMORE:
        still_written_by = workflows_that_still_publish(destination, workflow_dir)
        if still_written_by:
            raise SystemExit(
                f"FAIL: {destination} is on the retired list, but {', '.join(still_written_by)} "
                f"still publishes to it — deleting it would remove a live page"
            )
        retired = site / destination
        if retired.is_dir():
            shutil.rmtree(retired)
            deletions.append(f"{destination}/")


def apply_retention(site: Path, repo_with_the_branches: Path, workflow_dir: Path) -> list[str]:
    deletions: list[str] = []
    delete_legacy_report_attachments_no_published_report_references(site, deletions)
    delete_previews_whose_branch_no_longer_exists(site, repo_with_the_branches, deletions)
    delete_retired_fanout_destinations(site, workflow_dir, deletions)
    return deletions


def plant_a_site_that_exercises_every_rule(root: Path, live_branch: str) -> None:
    attachments = root / LEGACY_REPORT_ATTACHMENT_DIR
    attachments.mkdir(parents=True)
    kept = "a" * 40 + ".zip"
    dropped = "b" * 40 + ".zip"
    (attachments / kept).write_bytes(b"kept")
    (attachments / dropped).write_bytes(b"dropped")
    report_payload = io.BytesIO()
    with zipfile.ZipFile(report_payload, "w") as report_zip:
        report_zip.writestr("report.json", '{"attachments":[{"path":"data/' + kept + '"}]}')
    encoded = base64.b64encode(report_payload.getvalue()).decode("ascii")
    (root / LEGACY_REPORT_DIR / "index.html").write_text(
        f'<html><script id="playwrightReportBase64">data:application/zip;base64,{encoded}</script></html>',
        encoding="utf8",
    )
    (root / PROOF_PREVIEW_DIR / live_branch).mkdir(parents=True)
    (root / PROOF_PREVIEW_DIR / live_branch / "index.html").write_text("live", encoding="utf8")
    (root / PROOF_PREVIEW_DIR / "branch-that-was-deleted").mkdir(parents=True)
    (root / PROOF_PREVIEW_DIR / "branch-that-was-deleted" / "index.html").write_text(
        "dead", encoding="utf8"
    )
    for retired in RETIRED_FANOUT_DESTINATIONS_NO_WORKFLOW_WRITES_ANYMORE:
        (root / retired).mkdir(parents=True)
        (root / retired / "index.html").write_text("retired", encoding="utf8")


def make_repo_with_one_branch(root: Path, branch: str) -> Path:
    origin = root / "origin.git"
    subprocess.run(["git", "init", "-q", "--bare", "-b", branch, str(origin)], check=True)
    seed = root / "seed"
    subprocess.run(["git", "init", "-q", "-b", branch, str(seed)], check=True)
    (seed / "README").write_text("seed", encoding="utf8")
    for command in (
        ["git", "config", "user.email", "retention@selftest"],
        ["git", "config", "user.name", "retention selftest"],
        ["git", "add", "-A"],
        ["git", "commit", "-q", "-m", "seed"],
        ["git", "remote", "add", "origin", str(origin)],
        ["git", "push", "-q", "origin", branch],
    ):
        subprocess.run(command, cwd=seed, check=True)
    return seed


def run_selftest_so_a_broken_rule_cannot_report_green() -> None:
    live_branch = "integration-J-99"
    with tempfile.TemporaryDirectory() as scratch:
        root = Path(scratch)
        site = root / "site"
        site.mkdir()
        plant_a_site_that_exercises_every_rule(site, live_branch)
        git_metadata_bytes = 1048576
        plant_git_metadata_github_pages_never_publishes(site, git_metadata_bytes)
        repo = make_repo_with_one_branch(root, live_branch)
        workflow_dir = root / "workflows"
        workflow_dir.mkdir()

        payload_before = payload_bytes_of_published_checkout(site)
        deletions = apply_retention(site, repo, workflow_dir)
        payload_after = payload_bytes_of_published_checkout(site)

        assert payload_before < git_metadata_bytes, (
            f"selftest: the summary reports {payload_before} bytes for a site whose published "
            f"files are a few hundred bytes; it counted the git metadata of the checkout"
        )
        assert payload_after < payload_before, (
            f"selftest: the summary reports {payload_after} bytes after the rule deleted "
            f"{len(deletions)} path(s) from a {payload_before}-byte site"
        )
        assert (site / LEGACY_REPORT_ATTACHMENT_DIR / ("a" * 40 + ".zip")).exists(), (
            "selftest: the rule deleted an attachment the published report references"
        )
        assert not (site / LEGACY_REPORT_ATTACHMENT_DIR / ("b" * 40 + ".zip")).exists(), (
            "selftest: the rule kept an attachment no published report references"
        )
        assert (site / PROOF_PREVIEW_DIR / live_branch).is_dir(), (
            "selftest: the rule deleted the preview of a branch that still exists"
        )
        assert not (site / PROOF_PREVIEW_DIR / "branch-that-was-deleted").exists(), (
            "selftest: the rule kept the preview of a branch that was deleted"
        )
        for retired in RETIRED_FANOUT_DESTINATIONS_NO_WORKFLOW_WRITES_ANYMORE:
            assert not (site / retired).exists(), (
                f"selftest: the rule kept the retired fan-out destination {retired}"
            )
        assert len(deletions) == 4, f"selftest: unexpected deletion set {deletions}"

    with tempfile.TemporaryDirectory() as scratch:
        root = Path(scratch)
        site = root / "site"
        site.mkdir()
        plant_a_site_that_exercises_every_rule(site, live_branch)
        repo = make_repo_with_one_branch(root, live_branch)
        (site / LEGACY_REPORT_DIR / "index.html").unlink()
        workflow_dir = root / "workflows"
        workflow_dir.mkdir()

        apply_retention(site, repo, workflow_dir)

        assert (site / LEGACY_REPORT_ATTACHMENT_DIR / ("b" * 40 + ".zip")).exists(), (
            "selftest: an unreadable report pruned attachments it could not prove unreachable"
        )

    with tempfile.TemporaryDirectory() as scratch:
        root = Path(scratch)
        site = root / "site"
        site.mkdir()
        plant_a_site_that_exercises_every_rule(site, live_branch)
        repo = make_repo_with_one_branch(root, live_branch)
        workflow_dir = root / "workflows"
        workflow_dir.mkdir()
        (workflow_dir / "still-publishing.yml").write_text(
            f"          destination_dir: {RETIRED_FANOUT_DESTINATIONS_NO_WORKFLOW_WRITES_ANYMORE[0]}\n",
            encoding="utf8",
        )
        try:
            apply_retention(site, repo, workflow_dir)
        except SystemExit as refusal:
            assert "still publishes to it" in str(refusal), f"selftest: wrong refusal {refusal}"
        else:
            raise AssertionError(
                "selftest: the rule deleted a retired destination a workflow still publishes to"
            )

    print("PASS")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--site", default=".")
    parser.add_argument("--repo", default=".")
    parser.add_argument("--workflows", default=".github/workflows")
    parser.add_argument("--selftest", action="store_true")
    arguments = parser.parse_args()

    if arguments.selftest:
        run_selftest_so_a_broken_rule_cannot_report_green()
        return 0

    site = Path(arguments.site).resolve()
    before = payload_bytes_of_published_checkout(site)
    deletions = apply_retention(site, Path(arguments.repo).resolve(), Path(arguments.workflows))
    after = payload_bytes_of_published_checkout(site)

    for deletion in deletions:
        print(f"  deleted {deletion}")
    print(
        f"gh-pages payload {megabytes(before)} → {megabytes(after)} "
        f"({len(deletions)} path(s) deleted, {megabytes(before - after)} reclaimed)"
    )
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf8") as step_summary:
            step_summary.write(
                f"### gh-pages retention\n\n{megabytes(before)} → {megabytes(after)}, "
                f"{len(deletions)} path(s) deleted.\n\n"
            )
    return 0


if __name__ == "__main__":
    sys.exit(main())
