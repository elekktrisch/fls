#!/usr/bin/env python3

from pathlib import Path

GIT_METADATA_DIRECTORY_GITHUB_PAGES_NEVER_PUBLISHES = ".git"


def megabytes(byte_count: int) -> str:
    return f"{byte_count / 1048576:.1f} MB"


def payload_bytes_of_published_checkout(checkout: Path) -> int:
    return sum(
        published.stat().st_size
        for published in checkout.rglob("*")
        if published.is_file()
        and GIT_METADATA_DIRECTORY_GITHUB_PAGES_NEVER_PUBLISHES
        not in published.relative_to(checkout).parts
    )


def plant_git_metadata_github_pages_never_publishes(checkout: Path, byte_count: int) -> None:
    packed_objects = (
        checkout / GIT_METADATA_DIRECTORY_GITHUB_PAGES_NEVER_PUBLISHES / "objects" / "pack"
    )
    packed_objects.mkdir(parents=True)
    (packed_objects / "pack-selftest.pack").write_bytes(b"g" * byte_count)
    (checkout / GIT_METADATA_DIRECTORY_GITHUB_PAGES_NEVER_PUBLISHES / "HEAD").write_text(
        "ref: refs/heads/gh-pages\n", encoding="utf8"
    )
