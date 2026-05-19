---
id: S-152
title: Rename `next/` → `alpenflight/` working subtree
epic: E-01
status: done
started_at: 2026-05-19
done_at: 2026-05-19
depends_on: []
acceptance:
  - All `next/server/`, `next/web/`, `next/database/`, `next/auth/`, `next/ops/` folders renamed to `alpenflight/...` in a single atomic commit.
  - All in-repo references in docs, ADRs, scripts, configs, and CI workflows updated (excluding archived stories under `docs/modernization/stories/implemented/`).
  - All four PR-triggered CI workflows (`ci`, `compose-lint`, `compose-smoke`, `extract`) actually run and pass green on the rename PR — not "skipped to green" (path-filter trap).
  - Anchored grep `git grep -nE '(^|[^A-Za-z0-9_/])next/(auth|database|ops|server|web)' -- ':!flsserver' ':!flsweb' ':!e2e/node_modules' ':!docs/modernization/stories/implemented'` returns zero hits.
estimate: S
adr_refs: []
parity_test: none
refined: true
refined_at: 2026-05-19
refined_specialists: [requirements, solution, qa]
github_issue: 81
github_pr: 82
merged: true
merged_at: 2026-05-19
---

## Context
Vision-doc §8 final naming open item — closes the loop on the S-128 rebrand (which renamed the product but left the working subtree slug as `next/`).

## Decisions worth preserving

- **Build-cache slug.** Operator chose to drop the `-web` suffix: `/home/agent/fls-build/next-web/` → `/home/agent/fls-build/alpenflight/`. Angular project name was already `"web"` (not `"next-web"` as refine assumed), so no project-level rename needed; only the build-cache + symlink + `ESBUILD_BINARY_PATH` changed. Each contributor needs the one-time runbook: `mv` the build-cache dir, recreate `alpenflight/web/node_modules` symlink, update `ESBUILD_BINARY_PATH`.
- **Path-filter trap.** CI `paths:` filters moved to `alpenflight/**` in the **same** commit as the rename; verified all four PR-triggered workflows executed (not "skipped to green") on the rename PR. `nightly.yml` is schedule-only, not PR-triggered.
- **Excluded from sweep on purpose.** `flsserver/`, `flsweb/`, `e2e/node_modules/`, `docs/modernization/stories/implemented/**` (archived historical record).

## Deferred boyscout follow-ups

The anchored `\bnext/` regex correctly skipped slash-less identifiers; two surfaces survived and are queued separately:

- **CI job ids** `next-build`, `next-auth-realm-shape`, `outputs.next` in `.github/workflows/ci.yml`. Couples to branch-protection required-checks rebind — needs coordinated rename.
- **Docker compose profile** literal `"next"` across `docker-compose.yml`, `.github/workflows/compose-{lint,smoke}.yml`, `alpenflight/ops/*`. Renaming requires every contributor to retag local containers; needs a contributor-comms moment.

Both tracked in the boyscout-followups memory queue.
