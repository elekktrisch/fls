---
id: S-152
title: Rename `next/` → `alpenflight/` working subtree
epic: E-01
status: in_progress
started_at: 2026-05-19
depends_on: []
acceptance:
  - All `next/server/`, `next/web/`, `next/database/`, `next/auth/`, `next/ops/` folders renamed to `alpenflight/...` in a single atomic commit.
  - All in-repo references in docs, ADRs, scripts, configs, and CI workflows updated (excluding archived stories under `docs/modernization/stories/implemented/`).
  - All five CI workflows (`ci`, `compose-lint`, `compose-smoke`, `extract`, `nightly`) actually **run and pass green** on the rename PR — not "skipped to green" (path-filter trap, see Design notes §5).
  - Anchored grep `git grep -nE '(^|[^A-Za-z0-9_/])next/(auth|database|ops|server|web)' -- ':!flsserver' ':!flsweb' ':!e2e/node_modules' ':!docs/modernization/stories/implemented'` returns zero hits.
estimate: S
adr_refs: []
parity_test: none
refined: true
refined_at: 2026-05-19
refined_specialists: [requirements, solution, qa]
github_issue: 81
---

## Context
Vision-doc §8 final naming open item. The technical rebrand (S-128) covered the user-facing name but the working subtree slug is still `next/`. This story closes the loop.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] `git mv next/{auth,database,ops,server,web} alpenflight/...` — five renames, one commit.
- [ ] Anchored search-replace `\bnext/` → `alpenflight/` (see Design notes §2 for safe regex + exclusion globs).
- [ ] Update path filters in `.github/workflows/{ci,compose-lint,compose-smoke,extract}.yml` from `next/**` → `alpenflight/**` in the same commit.
- [ ] Update `.idea/gradle.xml` and any tracked `.idea/modules/*.iml` referencing `$PROJECT_DIR$/next/...`.
- [ ] Run completeness probe (anchored grep from AC) — must be empty.
- [ ] Run `bash alpenflight/ops/dev-up-full.sh` locally + one Playwright spec — proves dev loop intact.
- [ ] Push PR; verify the four path-filtered workflows show **executed and green**, not "skipped". Use `workflow_dispatch` belt-and-braces if uncertain.

## Notes
Fires whenever — no calendar dependency. The earlier it runs, the fewer references accumulate to update.

<!-- modernize-refine: start -->

## Design notes

### 1. Rename mechanism
One commit. Five `git mv` calls in any order — git infers renames at diff time from similarity, not from invocation order. `git diff --summary HEAD` should show five `rename` entries before staging the search-replace.

### 2. Search-replace strategy
Anchored regex on `\bnext/` to spare prose ("next quarter", "next release", `next/last`, `nextTick`). Recommended one-liner:

```
rg -l --hidden -g '!.git' -g '!**/node_modules/**' -g '!flsserver/**' -g '!flsweb/**' \
   -g '!e2e/node_modules/**' -g '!docs/modernization/stories/implemented/**' '\bnext/' \
| xargs perl -i -pe 's{\bnext/}{alpenflight/}g'
```

Then eyeball `git diff` for any false-positive in backtick prose. **Do not** use plain `sed s/next/alpenflight/g` — ADR / CHANGELOG prose will detonate.

### 3. In-tree vs. out-of-tree split
**In-tree, this commit:** repo files only — workflows, `docker-compose.yml`, root `CLAUDE.md` / `README.md`, `.claude/**`, `docs/**` (excluding `implemented/`), all moved-into-`alpenflight/` files, tracked `.idea/` entries with `$PROJECT_DIR$/next/...`.

**Out-of-tree, operator follow-up after merge (NOT blockers for AC — CI runs in fresh containers):**
- `/etc/sandbox-persistent.sh:5` `ESBUILD_BINARY_PATH` (sandbox-local; ignored by CI).
- Filesystem rename `/home/agent/fls-build/next-web/` → conditional on §10 open question.
- `.idea/workspace.xml` — per-user churn; drop if tracked.

### 4. Symlink decision (`next/web/node_modules`)
Pick **operator-runbook** (not in this commit). The symlink target is on the agent's Linux-local FS, not committed. Post-merge runbook:
1. `mv /home/agent/fls-build/next-web /home/agent/fls-build/alpenflight-web`
2. Recreate symlink: `cd alpenflight/web && ln -sfn /home/agent/fls-build/alpenflight-web/node_modules node_modules`
3. Update `/etc/sandbox-persistent.sh` ESBUILD_BINARY_PATH.

Documented in `alpenflight/web/CLAUDE.md` §9 post-rename. (Conditional on §10 decision below.)

### 5. CI path-filter trap
GH Actions `paths:` filter evaluates against **changed files**, which on a rename PR includes BOTH source and destination paths — so the rename PR's own CI **does** trigger under the old `next/**` filter. *Post-merge*, future PRs touching `alpenflight/**` only would be skipped under a stale filter. Therefore update filters to `paths: 'alpenflight/**'` (no union needed — nothing under `next/**` exists after merge) in the same commit. **Verification gate:** inspect the PR's Checks tab; "skipped" is the silent-failure mode, not "passed".

### 6. `implemented/` story audit trail
**Don't rewrite paths inside `docs/modernization/stories/implemented/*.md`.** Those are historical records; the file was created at `next/...` and the citation is accurate-as-of-implementation. Excluded in the rg glob above.

### 7. What NOT to rename
- `flsserver/`, `flsweb/`, `e2e/node_modules/` — exclude via globs.
- Docker container/image names already `alpenflight-*` — no `next/` substring, unaffected.
- Keycloak realm slug, compose project names (`fls-e2e`, `alpenflight-dev`), git branch names — separate concerns.
- Closed/merged PR descriptions and SHA-pinned blob URLs — those resolve against historical paths, leave alone.

### 8. Atomicity
Single commit on the story branch is non-negotiable per AC (bisect-friendly, no broken intermediate states). Multiple exploration commits → `git reset --soft <base> && git commit -m "..."` before pushing. Squash-merge would collapse anyway, but the AC asks explicitly.

### 9. Cross-story implications
- After merge, every in-flight `/modernize-fleet` worktree or unmerged story branch touching `next/` faces a mechanical rebase conflict on every moved file. Schedule for a fleet-quiet window (see §10).
- Future `/modernize-implement` / `/modernize-refine` invocations naturally use `alpenflight/` paths once main has merged.

## Edge cases & hidden requirements

- **Persistent env hazard (out-of-band):** `/etc/sandbox-persistent.sh:5` hardcodes `next-web` in `ESBUILD_BINARY_PATH`. Only matters if §10 decides to rename the build-cache dir; otherwise leave alone.
- **Compose build contexts** (`docker-compose.yml:131`, `:181`) are runtime path refs — covered by the anchored sweep but verify they resolve in the `compose-smoke` workflow.
- **GHA cache key churn:** `ci.yml:108` hashes `next/web/pnpm-lock.yaml` — first post-rename run cold-misses cache (acceptable, note in PR).
- **Squash-merge rollback window:** `git revert <sha>` is clean within ~24 h. After downstream branches accumulate `alpenflight/` history, roll-forward is the only path.
- **Search-replace blind spots** to manually verify after the sweep: hand-eyeball any backtick/quote contexts (e.g. ADR examples) that didn't match `\bnext/` cleanly. Rare; not zero.

## Security plan
(N/A — pure repo plumbing; no auth/tenancy/PII surface changes.)

## Test plan

No new unit / integration / e2e specs — this story has no business logic. The verification *is* the test plan:

1. **Completeness probe (gate):** anchored grep from AC returns empty.
2. **CI green AND executed:** all five workflows ran (not skipped) and passed on the rename PR. Inspect the Checks tab — `workflow_dispatch` belt-and-braces if uncertain.
3. **Dev-loop smoke (manual, local):** `bash alpenflight/ops/dev-up-full.sh` brings up stack; one existing Playwright spec (e.g. Clubs CRUD) passes against the renamed paths.
4. **IDE refresh (manual):** open in IntelliJ, confirm Gradle re-imports `alpenflight/server/` cleanly — catches stale `.idea/` paths.

**Out of scope:** parity tests, new coverage. No business behavior changed.

## Performance plan
(N/A — no hot path, no query, no latency surface touched.)

## Open design questions

1. **Angular project + build-cache rename.** Should `next-web` (Angular project name in `angular.json`, output dir `dist/next-web/browser/`, build cache dir `/home/agent/fls-build/next-web/`, `ESBUILD_BINARY_PATH`) also become `alpenflight-web` in this commit? Ripples to: Caddy SPA-fallback config (S-041), `next/web/CLAUDE.md` §9 docs, persistent env, contributor onboarding. **Defensible either way** — pick before starting:
   - **A. Rename in this commit.** Clean break; one operator-runbook step covers everything.
   - **B. Defer to a follow-up.** Keeps S-152 mechanical; preserves dist/output paths short-term.

2. **In-flight branch policy.** PR #80 (S-047) is open; S-151 / S-153 / S-157 / S-158 are unrefined-todo and will touch `next/` once started.
   - **A. Hold S-152 until #80 merges, then run S-152 *next* before starting any other story.** Minimizes rebase pain.
   - **B. Rename now and force-rebase open branches.** Faster overall but every open branch pays the mechanical-conflict cost on every moved file.

<!-- modernize-refine: end -->

