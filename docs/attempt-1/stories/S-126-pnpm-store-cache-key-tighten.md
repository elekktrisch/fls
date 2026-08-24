---
id: S-126
title: Tighten pnpm-store cache restore-keys in CI
epic: E-01
status: todo
estimate: S
parity_test: none
depends_on: []
adr_refs: []
refined: false
origin: rework
origin_story: S-002
origin_finding: pnpm-store cache `restore-keys` in `.github/workflows/ci.yml:419-421` falls back to `${{ runner.os }}-pnpm-store-`, matching any prior store regardless of `pnpm-lock.yaml` hash. Combined with `--frozen-lockfile` the install still verifies, but pnpm's content-addressed integrity check is the only line of defense against a poisoned cache entry.
---

## Context

Follow-up from review of S-002 (originating story). The originating story's review found:

> pnpm-store cache `restore-keys` allows cross-lockfile cache reuse — `.github/workflows/ci.yml:419-421`. The fallback `${{ runner.os }}-pnpm-store-` matches any prior store regardless of `pnpm-lock.yaml` hash; combined with `--frozen-lockfile` install still verifies, but pnpm's content-addressed store check is the only line of defense.
> **Suggested fix:** drop the `restore-keys` for the pnpm store or tighten to a major-version-pinned scope.
> **Path:** `.github/workflows/ci.yml:419-421`.

See [`S-002-scaffold-web-skeleton.md`](S-002-scaffold-web-skeleton.md#review) for full review context.

## Acceptance criteria

- [ ] CI's `next-build` pnpm-store cache step uses either no `restore-keys` (strict — only exact lockfile-hash match) or a major-version-pinned fallback (`${{ runner.os }}-pnpm-store-v11-`) so a pnpm major bump invalidates stale stores.
- [ ] Verify CI still benefits from cache hits on lockfile-unchanged pushes (warm-cache install time stays comparable).
- [ ] Document the rationale inline (one comment line) so the trade-off is legible to the next maintainer.

## Notes

Touches `.github/workflows/ci.yml` so the operator must apply via a `workflow`-scoped token or apply locally + push. No code change.
