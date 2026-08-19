#!/usr/bin/env python3

import argparse
import base64
import io
import os
import posixpath
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from html.parser import HTMLParser
from pathlib import Path, PurePosixPath
from urllib.parse import unquote, urlsplit

from gh_pages_payload import (
    GIT_METADATA_DIRECTORY_GITHUB_PAGES_NEVER_PUBLISHES,
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
PUBLISHED_PAGE_SUFFIX = ".html"
REPOSITORY_THIS_PROJECT_PAGES_SITE_SERVES = os.environ.get("GITHUB_REPOSITORY", "elekktrisch/fls")
PROJECT_PAGES_ROOT_PREFIX_ABSOLUTE_LINKS_CARRY = (
    "/" + REPOSITORY_THIS_PROJECT_PAGES_SITE_SERVES.rsplit("/", 1)[-1]
)
ATTRIBUTES_THAT_ADDRESS_ANOTHER_PUBLISHED_FILE = frozenset(
    {"href", "src", "poster", "data", "action"}
)


class LinkTargetsOfRealTagsNotOfMinifiedScriptText(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.link_targets: list[str] = []

    def handle_starttag(self, tag: str, attributes: list[tuple[str, str | None]]) -> None:
        for name, value in attributes:
            if name in ATTRIBUTES_THAT_ADDRESS_ANOTHER_PUBLISHED_FILE and value:
                self.link_targets.append(value)


def site_relative_target_of_link(
    link_target: str, directory_of_the_page_that_links: PurePosixPath
) -> PurePosixPath | None:
    addressed = urlsplit(link_target)
    if addressed.scheme or addressed.netloc:
        return None
    path = unquote(addressed.path)
    if not path:
        return None
    if path.startswith("/"):
        if path != PROJECT_PAGES_ROOT_PREFIX_ABSOLUTE_LINKS_CARRY and not path.startswith(
            PROJECT_PAGES_ROOT_PREFIX_ABSOLUTE_LINKS_CARRY + "/"
        ):
            return None
        addressed_within_the_site = path[len(PROJECT_PAGES_ROOT_PREFIX_ABSOLUTE_LINKS_CARRY) :]
        normalized = posixpath.normpath(addressed_within_the_site.lstrip("/"))
    else:
        normalized = posixpath.normpath(
            posixpath.join(str(directory_of_the_page_that_links), path)
        )
    if normalized in (".", "", "/"):
        return None
    return PurePosixPath(normalized)


def site_relative_link_targets_of_page(page: Path, site: Path) -> set[PurePosixPath]:
    collector = LinkTargetsOfRealTagsNotOfMinifiedScriptText()
    collector.feed(page.read_text(encoding="utf8", errors="replace"))
    collector.close()
    directory = PurePosixPath(page.relative_to(site).as_posix()).parent
    targets = {site_relative_target_of_link(link, directory) for link in collector.link_targets}
    return {target for target in targets if target is not None}


def published_pages(site: Path) -> list[Path]:
    return sorted(
        page
        for page in site.rglob(f"*{PUBLISHED_PAGE_SUFFIX}")
        if page.is_file()
        and GIT_METADATA_DIRECTORY_GITHUB_PAGES_NEVER_PUBLISHES
        not in page.relative_to(site).parts
    )


def path_is_at_or_under(path: PurePosixPath, container: PurePosixPath) -> bool:
    return path == container or container in path.parents


def site_relative(path: Path, site: Path) -> PurePosixPath:
    return PurePosixPath(path.relative_to(site).as_posix())


def deletion_label(path: Path, site: Path) -> str:
    relative = site_relative(path, site)
    return f"{relative}/" if path.is_dir() else str(relative)


def split_candidates_by_what_the_surviving_pages_still_reach(
    site: Path, proposed_deletions: list[Path]
) -> tuple[list[Path], list[tuple[Path, Path, PurePosixPath]]]:
    every_page = published_pages(site)
    targets_of_page: dict[Path, set[PurePosixPath]] = {}
    reached: dict[Path, tuple[Path, PurePosixPath]] = {}
    while True:
        still_proposed = [proposed for proposed in proposed_deletions if proposed not in reached]
        proposed_by_site_relative_path = {
            site_relative(proposed, site): proposed for proposed in still_proposed
        }
        surviving_pages = [
            page
            for page in every_page
            if not any(
                path_is_at_or_under(site_relative(page, site), relative)
                for relative in proposed_by_site_relative_path
            )
        ]
        reached_this_pass: dict[Path, tuple[Path, PurePosixPath]] = {}
        for page in surviving_pages:
            if page not in targets_of_page:
                targets_of_page[page] = site_relative_link_targets_of_page(page, site)
            for target in sorted(targets_of_page[page]):
                for ancestor in (target, *target.parents):
                    proposed = proposed_by_site_relative_path.get(ancestor)
                    if proposed is not None and proposed not in reached_this_pass:
                        reached_this_pass[proposed] = (page, target)
        if not reached_this_pass:
            break
        reached.update(reached_this_pass)
    unreachable = [proposed for proposed in proposed_deletions if proposed not in reached]
    return unreachable, [(proposed, *reached[proposed]) for proposed in reached]


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


def legacy_report_attachments_no_published_report_references(site: Path) -> list[Path]:
    attachment_dir = site / LEGACY_REPORT_ATTACHMENT_DIR
    if not attachment_dir.is_dir():
        return []
    try:
        referenced = attachment_names_the_published_report_still_references(site)
    except (FileNotFoundError, zipfile.BadZipFile, ValueError) as unreadable_report:
        print(
            f"::warning::keeping every {LEGACY_REPORT_ATTACHMENT_DIR} file — {unreadable_report}"
        )
        return []
    return [
        attachment
        for attachment in sorted(attachment_dir.iterdir())
        if attachment.is_file() and attachment.name not in referenced
    ]


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


def previews_whose_branch_no_longer_exists(
    site: Path, repo_with_the_branches: Path
) -> list[Path]:
    preview_dir = site / PROOF_PREVIEW_DIR
    if not preview_dir.is_dir():
        return []
    try:
        live = sanitized_names_of_every_branch_on_origin(repo_with_the_branches)
    except subprocess.CalledProcessError as unreachable_origin:
        print(f"::warning::keeping every preview — origin is unreachable: {unreachable_origin}")
        return []
    if not live:
        print("::warning::keeping every preview — origin listed no branch at all")
        return []
    return [
        preview
        for preview in sorted(preview_dir.iterdir())
        if preview.is_dir() and preview.name not in live
    ]


def workflows_that_still_publish(destination: str, workflow_dir: Path) -> list[str]:
    if not workflow_dir.is_dir():
        return []
    return [
        str(workflow)
        for workflow in sorted(workflow_dir.glob("*.yml"))
        if f"destination_dir: {destination}" in workflow.read_text(encoding="utf8")
    ]


def retired_fanout_destinations(site: Path, workflow_dir: Path) -> list[Path]:
    retired_directories: list[Path] = []
    for destination in RETIRED_FANOUT_DESTINATIONS_NO_WORKFLOW_WRITES_ANYMORE:
        still_written_by = workflows_that_still_publish(destination, workflow_dir)
        if still_written_by:
            raise SystemExit(
                f"FAIL: {destination} is on the retired list, but {', '.join(still_written_by)} "
                f"still publishes to it — deleting it would remove a live page"
            )
        retired = site / destination
        if retired.is_dir():
            retired_directories.append(retired)
    return retired_directories


def apply_retention(site: Path, repo_with_the_branches: Path, workflow_dir: Path) -> list[str]:
    proposed_deletions = [
        *legacy_report_attachments_no_published_report_references(site),
        *previews_whose_branch_no_longer_exists(site, repo_with_the_branches),
        *retired_fanout_destinations(site, workflow_dir),
    ]
    unreachable, still_reached = split_candidates_by_what_the_surviving_pages_still_reach(
        site, proposed_deletions
    )
    for kept, page, target in sorted(still_reached, key=lambda reached: str(reached[0])):
        print(
            f"::warning::keeping {deletion_label(kept, site)} — "
            f"{site_relative(page, site)} links {target}"
        )
    deletions: list[str] = []
    for path in unreachable:
        label = deletion_label(path, site)
        if path.is_dir():
            shutil.rmtree(path)
        else:
            path.unlink()
        deletions.append(label)
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


def plant_a_published_page(
    site: Path, page: str, link_targets: tuple[str, ...] = (), script_text: str = ""
) -> None:
    links = "".join(f'<a href="{target}">proof</a>' for target in link_targets)
    script = f"<script>{script_text}</script>" if script_text else ""
    planted = site / page
    planted.parent.mkdir(parents=True, exist_ok=True)
    planted.write_text(f"<html><body>{links}{script}</body></html>", encoding="utf8")


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


LIVE_BRANCH_OF_THE_SELFTEST_SITE = "integration-J-99"


def plant_the_selftest_site(scratch: str) -> tuple[Path, Path, Path]:
    root = Path(scratch)
    site = root / "site"
    site.mkdir()
    plant_a_site_that_exercises_every_rule(site, LIVE_BRANCH_OF_THE_SELFTEST_SITE)
    repo = make_repo_with_one_branch(root, LIVE_BRANCH_OF_THE_SELFTEST_SITE)
    workflow_dir = root / "workflows"
    workflow_dir.mkdir()
    return site, repo, workflow_dir


def selftest_the_rule_deletes_only_what_no_published_page_reaches() -> None:
    with tempfile.TemporaryDirectory() as scratch:
        site, repo, workflow_dir = plant_the_selftest_site(scratch)
        git_metadata_bytes = 1048576
        plant_git_metadata_github_pages_never_publishes(site, git_metadata_bytes)

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
        assert (site / PROOF_PREVIEW_DIR / LIVE_BRANCH_OF_THE_SELFTEST_SITE).is_dir(), (
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


def selftest_an_unreadable_report_proves_no_attachment_unreachable() -> None:
    with tempfile.TemporaryDirectory() as scratch:
        site, repo, workflow_dir = plant_the_selftest_site(scratch)
        (site / LEGACY_REPORT_DIR / "index.html").unlink()

        apply_retention(site, repo, workflow_dir)

        assert (site / LEGACY_REPORT_ATTACHMENT_DIR / ("b" * 40 + ".zip")).exists(), (
            "selftest: an unreadable report pruned attachments it could not prove unreachable"
        )


def selftest_the_rule_refuses_a_destination_a_workflow_still_publishes() -> None:
    with tempfile.TemporaryDirectory() as scratch:
        site, repo, workflow_dir = plant_the_selftest_site(scratch)
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


def selftest_a_relative_link_keeps_the_directory_it_points_into() -> None:
    linked, unlinked = RETIRED_FANOUT_DESTINATIONS_NO_WORKFLOW_WRITES_ANYMORE
    with tempfile.TemporaryDirectory() as scratch:
        site, repo, workflow_dir = plant_the_selftest_site(scratch)
        plant_a_published_page(site, f"{linked}/J-2/index.html")
        plant_a_published_page(
            site, "alpenflight/previews/index.html", ("../proof/legacy-parity/J-2/",)
        )

        deletions = apply_retention(site, repo, workflow_dir)

        assert (site / linked).is_dir(), (
            f"selftest: the rule deleted {linked}, which alpenflight/previews/index.html links "
            f"into — the surviving page would carry a dead link"
        )
        assert f"{linked}/" not in deletions, (
            f"selftest: the rule reported {linked} as deleted while a published page links into it"
        )
        assert not (site / unlinked).exists(), (
            f"selftest: the rule kept {unlinked}, which no surviving published page links into"
        )


def selftest_a_root_absolute_link_keeps_the_directory_it_points_into() -> None:
    unlinked, linked = RETIRED_FANOUT_DESTINATIONS_NO_WORKFLOW_WRITES_ANYMORE
    with tempfile.TemporaryDirectory() as scratch:
        site, repo, workflow_dir = plant_the_selftest_site(scratch)
        plant_a_published_page(
            site,
            "alpenflight/proof/J-0/index.html",
            (f"{PROJECT_PAGES_ROOT_PREFIX_ABSOLUTE_LINKS_CARRY}/{linked}/",),
        )

        apply_retention(site, repo, workflow_dir)

        assert (site / linked).is_dir(), (
            f"selftest: the rule deleted {linked}, which a surviving page links through the "
            f"site-root form {PROJECT_PAGES_ROOT_PREFIX_ABSOLUTE_LINKS_CARRY}/{linked}/"
        )
        assert not (site / unlinked).exists(), (
            f"selftest: the rule kept {unlinked}, which no surviving published page links into"
        )


def selftest_a_link_from_a_page_that_the_sweep_deletes_keeps_nothing() -> None:
    linked_from_a_doomed_page = RETIRED_FANOUT_DESTINATIONS_NO_WORKFLOW_WRITES_ANYMORE[1]
    with tempfile.TemporaryDirectory() as scratch:
        site, repo, workflow_dir = plant_the_selftest_site(scratch)
        plant_a_published_page(
            site,
            f"{PROOF_PREVIEW_DIR}/branch-that-was-deleted/index.html",
            (f"{PROJECT_PAGES_ROOT_PREFIX_ABSOLUTE_LINKS_CARRY}/{linked_from_a_doomed_page}/",),
        )

        apply_retention(site, repo, workflow_dir)

        assert not (site / linked_from_a_doomed_page).exists(), (
            f"selftest: the rule kept {linked_from_a_doomed_page} for a link on a page the same "
            f"sweep deletes"
        )


def selftest_a_link_from_a_surviving_page_keeps_a_dead_branch_preview() -> None:
    dead_preview = f"{PROOF_PREVIEW_DIR}/branch-that-was-deleted"
    with tempfile.TemporaryDirectory() as scratch:
        site, repo, workflow_dir = plant_the_selftest_site(scratch)
        plant_a_published_page(
            site,
            f"{PROOF_PREVIEW_DIR}/{LIVE_BRANCH_OF_THE_SELFTEST_SITE}/index.html",
            ("../branch-that-was-deleted/",),
        )

        apply_retention(site, repo, workflow_dir)

        assert (site / dead_preview).is_dir(), (
            f"selftest: the rule deleted {dead_preview}, which the preview of a live branch "
            f"links into"
        )


def selftest_minified_script_text_never_counts_as_a_link() -> None:
    named_only_inside_a_script = RETIRED_FANOUT_DESTINATIONS_NO_WORKFLOW_WRITES_ANYMORE[1]
    with tempfile.TemporaryDirectory() as scratch:
        site, repo, workflow_dir = plant_the_selftest_site(scratch)
        plant_a_published_page(
            site,
            "alpenflight/proof/J-0/index.html",
            script_text=(
                "var r='<a href=\"/fls/"
                + named_only_inside_a_script
                + "/'+Ne(t)+'/index.html\">'+a+'</a>';"
            ),
        )

        deletions = apply_retention(site, repo, workflow_dir)

        assert not (site / named_only_inside_a_script).exists(), (
            f"selftest: the rule kept {named_only_inside_a_script} for a path that appears only "
            f"in minified script text, which no browser follows as a link"
        )
        assert f"{named_only_inside_a_script}/" in deletions, (
            f"selftest: the rule did not report {named_only_inside_a_script} as deleted"
        )


def selftest_a_target_that_addresses_no_published_file_is_not_a_reference() -> None:
    page_directory = PurePosixPath("alpenflight/previews")
    for addresses_no_published_file in (
        "about:blank",
        "https://github.com/elekktrisch/fls",
        "//example.invalid/x.html",
        "#top",
        "mailto:pilot@example.invalid",
        "javascript:void(0)",
        "data:image/png;base64,AAAA",
        "?filter=all",
        "/another-project-pages-site/index.html",
    ):
        assert site_relative_target_of_link(addresses_no_published_file, page_directory) is None, (
            f"selftest: {addresses_no_published_file} counted as a reference to a published file"
        )
    assert site_relative_target_of_link("../proof/legacy-parity/J-2/", page_directory) == (
        PurePosixPath("alpenflight/proof/legacy-parity/J-2")
    ), "selftest: a relative link into a sibling directory did not resolve to its published path"
    assert site_relative_target_of_link(
        f"{PROJECT_PAGES_ROOT_PREFIX_ABSOLUTE_LINKS_CARRY}/alpenflight/previews/", page_directory
    ) == PurePosixPath("alpenflight/previews"), (
        "selftest: a site-root link did not resolve to its published path"
    )


def run_selftest_so_a_broken_rule_cannot_report_green() -> None:
    selftest_the_rule_deletes_only_what_no_published_page_reaches()
    selftest_an_unreadable_report_proves_no_attachment_unreachable()
    selftest_the_rule_refuses_a_destination_a_workflow_still_publishes()
    selftest_a_relative_link_keeps_the_directory_it_points_into()
    selftest_a_root_absolute_link_keeps_the_directory_it_points_into()
    selftest_a_link_from_a_page_that_the_sweep_deletes_keeps_nothing()
    selftest_a_link_from_a_surviving_page_keeps_a_dead_branch_preview()
    selftest_minified_script_text_never_counts_as_a_link()
    selftest_a_target_that_addresses_no_published_file_is_not_a_reference()
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
