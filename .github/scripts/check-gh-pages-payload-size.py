#!/usr/bin/env python3

import argparse
import json
import os
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

from gh_pages_payload import (
    megabytes,
    payload_bytes_of_published_checkout,
    plant_git_metadata_github_pages_never_publishes,
)

GITHUB_PAGES_HARD_CAP_BYTES = 1024 * 1024 * 1024
PAYLOAD_BYTES_THAT_REDS_LONG_BEFORE_THE_HARD_CAP = 400 * 1024 * 1024


def read_github_api(url: str, token: str) -> dict:
    request = urllib.request.Request(url)
    request.add_header("Accept", "application/vnd.github+json")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read())


def payload_bytes_of_published_branch(repository: str, branch: str, token: str) -> int:
    head = read_github_api(
        f"https://api.github.com/repos/{repository}/git/ref/heads/{branch}", token
    )
    tree = read_github_api(
        f"https://api.github.com/repos/{repository}/git/trees/{head['object']['sha']}?recursive=1",
        token,
    )
    if tree.get("truncated"):
        raise SystemExit(
            f"FAIL: the git tree of {repository}@{branch} is truncated, so the measured payload "
            f"would understate the real one"
        )
    return sum(entry.get("size", 0) for entry in tree["tree"] if entry["type"] == "blob")


def assert_payload_stays_under_the_threshold(measured: int, threshold: int, source: str) -> None:
    print(f"gh-pages payload ({source}): {megabytes(measured)}")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf8") as step_summary:
            step_summary.write(
                f"### gh-pages payload\n\n{megabytes(measured)} of "
                f"{megabytes(threshold)} allowed "
                f"(GitHub Pages errors the build at {megabytes(GITHUB_PAGES_HARD_CAP_BYTES)}).\n\n"
            )
    if measured > threshold:
        raise SystemExit(
            f"FAIL: the published site is {megabytes(measured)}, over the "
            f"{megabytes(threshold)} threshold. GitHub Pages errors the BUILD at "
            f"{megabytes(GITHUB_PAGES_HARD_CAP_BYTES)}; the push still succeeds, the CDN keeps "
            f"serving the previous copy, and every deployed-page guard then reports a stale page. "
            f"Run .github/workflows/gh-pages-retention.yml before you publish more."
        )
    print(
        f"PASS: {megabytes(measured)} is under the {megabytes(threshold)} threshold "
        f"(hard cap {megabytes(GITHUB_PAGES_HARD_CAP_BYTES)})"
    )


def commit_every_file_the_way_a_publish_does(checkout: Path) -> None:
    for command in (
        ["git", "init", "-q", "-b", "gh-pages", "."],
        ["git", "config", "user.email", "payload@selftest"],
        ["git", "config", "user.name", "payload selftest"],
        ["git", "add", "-A"],
        ["git", "commit", "-q", "-m", "published"],
    ):
        subprocess.run(command, cwd=checkout, check=True)


def payload_bytes_the_published_branch_holds(checkout: Path) -> int:
    listed = subprocess.run(
        ["git", "ls-tree", "-r", "-l", "HEAD"],
        cwd=checkout,
        capture_output=True,
        text=True,
        check=True,
    )
    return sum(int(line.split()[3]) for line in listed.stdout.splitlines())


def run_selftest_so_a_broken_measurement_cannot_report_green() -> None:
    threshold = 4096
    with tempfile.TemporaryDirectory() as scratch:
        over = Path(scratch) / "over"
        (over / "nested").mkdir(parents=True)
        (over / "nested" / "big.bin").write_bytes(b"x" * (threshold + 1))
        try:
            assert_payload_stays_under_the_threshold(
                payload_bytes_of_published_checkout(over), threshold, "selftest"
            )
        except SystemExit as red:
            assert "over the" in str(red), f"selftest: wrong failure message {red}"
        else:
            raise AssertionError("selftest: a payload over the threshold reported green")

        under = Path(scratch) / "under"
        under.mkdir()
        (under / "small.bin").write_bytes(b"x" * (threshold - 1))
        plant_git_metadata_github_pages_never_publishes(under, threshold * 4)
        measured = payload_bytes_of_published_checkout(under)
        assert measured == threshold - 1, (
            f"selftest: the measurement reports {measured} bytes where GitHub Pages publishes "
            f"{threshold - 1}; it counted the git metadata, which Pages never serves"
        )
        assert_payload_stays_under_the_threshold(measured, threshold, "selftest")

        published = Path(scratch) / "checkout-of-the-published-branch"
        (published / "alpenflight" / "proof").mkdir(parents=True)
        (published / "alpenflight" / "proof" / "index.html").write_text("page", encoding="utf8")
        (published / "alpenflight" / "proof" / "run.webm").write_bytes(b"v" * threshold)
        (published / ".nojekyll").write_text("", encoding="utf8")
        commit_every_file_the_way_a_publish_does(published)
        measured_from_the_checkout = payload_bytes_of_published_checkout(published)
        held_by_the_branch = payload_bytes_the_published_branch_holds(published)
        assert measured_from_the_checkout == held_by_the_branch, (
            f"selftest: --dir measures {measured_from_the_checkout} bytes where the published "
            f"branch holds {held_by_the_branch}; --dir and the git-trees API must report the "
            f"same payload for the same tree"
        )
    print("PASS")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dir")
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY", ""))
    parser.add_argument("--branch", default="gh-pages")
    parser.add_argument(
        "--max-bytes",
        type=int,
        default=int(
            os.environ.get(
                "GH_PAGES_PAYLOAD_MAX_BYTES", PAYLOAD_BYTES_THAT_REDS_LONG_BEFORE_THE_HARD_CAP
            )
        ),
    )
    parser.add_argument("--selftest", action="store_true")
    arguments = parser.parse_args()

    if arguments.selftest:
        run_selftest_so_a_broken_measurement_cannot_report_green()
        return 0

    if arguments.dir:
        measured = payload_bytes_of_published_checkout(Path(arguments.dir))
        source = arguments.dir
    else:
        try:
            measured = payload_bytes_of_published_branch(
                arguments.repository, arguments.branch, os.environ.get("GITHUB_TOKEN", "")
            )
        except urllib.error.HTTPError as unreadable_branch:
            if unreadable_branch.code == 404:
                print(
                    f"::warning::{arguments.repository}@{arguments.branch} does not exist yet — "
                    f"nothing published, nothing to measure"
                )
                return 0
            raise
        source = f"{arguments.repository}@{arguments.branch}"

    assert_payload_stays_under_the_threshold(measured, arguments.max_bytes, source)
    return 0


if __name__ == "__main__":
    sys.exit(main())
