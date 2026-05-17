---
name: modernize-implement
description: Phase 6 — implement one refined story end-to-end. TDD, work-package commits, story branch + draft PR, CI watch. Trigger: /modernize-implement S-NNN.
---

# Phase 6 — Story Implementation

Take one refined story (`S-NNN`) and ship it: code per the design notes, tests per the QA plan, tests green, story `status: done`, PR ready-for-review.

Read [ADR 0022](../../../docs/modernization/adrs/0022-modernization-primary-directives.md) before starting. The two directives govern every decision below.

## Preconditions

Bail if any fail:

1. Single `S-NNN` arg. Story file at `docs/modernization/stories/S-NNN-*.md` (top-level; refuse if in `implemented/`).
2. Frontmatter has `refined: true` (else "run /modernize-refine first").
3. `status: todo` (in_progress → ask to resume; done/blocked → refuse).
4. Every `depends_on` story is `done` AND its `github_pr` is `MERGED` (or no PR exists).
5. Working tree clean.
6. On `main` (or configured default).

## Procedure

### Step 1 — Load context

Read in parallel: the full story file, each ADR in `adr_refs`, the legacy code paths cited in design notes (open them, don't paraphrase), the `parity_test` if any, `00-seed.md`.

**Speculative-refinement freshness (when `refined_speculative: true`):** re-refine if `refined_speculative_at` > 14 days old, or any `adr_refs` file or `depends_on` story changed since. Invoke `/modernize-refine S-NNN` JIT before continuing.

**Context7 freshness (when story touches a library / framework):** `mcp__context7__resolve-library-id` → `mcp__context7__query-docs` for each library mentioned in `adr_refs` or design notes. Library facts override training-data assumptions. Skip for general programming concepts.

### Step 2 — Status flip + GitHub issue + branch

Update frontmatter:

```yaml
status: in_progress
started_at: <ISO date>
```

**GitHub issue:** if `gh auth status` OK + remote exists + no `github_issue:` yet, `gh issue create` with title `S-NNN: <story title>`, body = `## Context` verbatim + AC checklist + link back to MD. Capture issue number; stamp `github_issue: N` on frontmatter. If already stamped, verify open via `gh issue view`.

**Branch:** `git checkout -b story/S-NNN-<slug>` off main. The initial commit (status + issue stamp) lands here: `#N: start`.

**Fallback (no `gh` / no remote / `gh issue create` fails):** skip issue lifecycle; use `S-NNN: <summary>` commit prefix instead of `#N:`.

### Step 3 — Parity strategy + red tests FIRST

Decide the parity layer:

- **Parity-sensitive story** (frontmatter `parity_test` non-empty, touches a seed sacred cow, or in a parity-flagged epic): preferred = e2e ported from legacy; acceptable = API integration; last resort = unit. Tests assert *observable behavior*, never legacy URL shape / HTTP verb / response envelope (unless the refinement explicitly preserves the shape — e.g. Proffix API, OGN ingestion contract).
- **Greenfield**: assert design-notes ACs directly. No parity oracle.
- **Partial-parity** (refactor + new capability): parity tests for preserved behavior + new tests for new capability.

Write tests at the chosen layer. Watch them fail for the *right reason* ("expected 200, got 404" — not `NullPointerException`). Commit red as `#N: red <layer> tests for <story>`. **Don't push** — CI would just confirm red.

### Step 4 — Implement per work-package

Order:
1. **DB migration** if domain model changed (Flyway V<n+1>__*.sql). Per [ADR 0022 directive 2](../../../docs/modernization/adrs/0022-modernization-primary-directives.md): schema = structural only (PKs, FKs, structural NOT NULL, identity-bearing partial UNIQUE, indexes). Business rules (CHECK-in-set, ranges, calculations, generated columns) go on aggregates at S-022/S-064 — flag in the migration with a comment if you're tempted.
2. **Backend slice**: entity → repository → service → controller + unit tests. `@PreAuthorize` per security plan; indexes per perf plan; `@TenantId` resolves automatically.
3. **Frontend slice**: Signal Store → component → route + component tests, consuming the regenerated TS client.
4. **Iterate to green.** Each green-turning iteration is its own commit.

**Disjoint sub-tasks** (backend / frontend / e2e) MAY run as parallel general-purpose Agents in one message. Same working tree; same TaskCreate ledger. Sequential reconciliation when they return. Default off for S-stories.

### Step 5 — Commit + push policy (autonomous)

- **Commit per work-package, not per file.** Target 3-5 commits for M stories, 5-8 for L.
- **Subject:** `#N: <one-line summary>` (or `S-NNN: …` fallback). No Conventional Commits prefix.
- **First push** happens after the backend slice is locally green → opens **draft PR** (`gh pr create --draft --base main --head story/S-NNN-<slug>`, body `Closes #N` + AC checklist). Stamp `github_pr: M` on frontmatter.
- **Subsequent pushes** at locally-green work-package boundaries. Watch CI in background:
  ```
  gh run watch <run-id> --exit-status   # run_in_background: true
  ```
- **CI failure:** stop foreground work. Comment on issue `CI failed on push <sha>: <run url> — <one-line cause>`. `gh run view --log-failed`. Fix in a new commit `#N: fix CI — <cause>`. Re-watch. Resume only when green.
- **Don't push past red.** Don't `--no-verify` / `--no-gpg-sign`. Don't force-push.

### Step 6 — Escalation triggers

Stop and ask the operator (single precise question) when:

- A parity test fails and the only way to pass it is to change behavior.
- A previously-green test in another story fails because of this story.
- A `depends_on` artifact is missing despite the dep being `done`.
- Legacy code being ported has an apparent bug — never silently fix.
- An AC is unmeetable as written.

Before escalating, consider a **one-shot read-only specialist consult** (Step 4.5):

| Specialist | When |
|---|---|
| `solution-architect` | Module / package layout fork, integration with another story's contract |
| `implementation-architect` | Design-notes gap; patch without re-deriving |
| `security-engineer` | Is this query / endpoint safe to expose? |
| `qa-engineer` | Right test shape / layer? Parity oracle ambiguous? |
| `performance-engineer` | Query plan shows seq-scan; is the index sufficient? |
| `legacy-investigator` | Legacy at file:line: intentional / bug / dead code? |
| `requirements-engineer` | Is AC N actually meetable given what the code does? |

One consult per fork, no chaining. Record in the done report.

### Step 7 — Pre-push gate (Step 6.7 self-review)

Before the final status-flip commit, one `maintainability-reviewer` consult against `git diff main...HEAD`. **Scope override: blockers only** ("surface only findings that break a refinement contract, ADR, sacred cow, security invariant, or leave an AC without a passing test"). Return `(none)` if clean.

- `(none)`: proceed to Step 8.
- Blockers fixable in one commit: fix, commit `#N: self-review fixes — <summary>`, push, proceed.
- Structural blocker (design contradiction, missing AC, ADR conflict): **stop, escalate per Step 6**. Don't fix-and-push past a redesign.

Skip the gate only for bookkeeping-only diffs; note the skip in the done report.

### Step 8 — Story-body sweep + status:done

**Body sweep** (non-contract content; refinement sections stay verbatim):

1. Delete stale `## Implementation status (paused …)` / `## Pickup notes` sections.
2. Check off `## Tasks` boxes or replace with "superseded by acceptance criteria".
3. Predicted-vs-actual migration version: update if wrong.
4. Strip `#N:` commit-subject identifiers + 7+ hex-char SHA tokens from body text (cite by file:line / PR# / story-ID instead).
5. Test-method-name drift: grep `_test_` / `_check_` / `_pinning_` / `_seeded_` patterns in backticks; verify each cited test actually exists; update or annotate.
6. Design-notes ↔ implementation drift on column inventory / row counts: amend the inventory + add one-line deviation note.

Commit sweep as `#N: post-implementation body sweep`. Skip for bookkeeping-only diffs.

**Status flip + archive move (one commit, one CI cycle):** update frontmatter `status: done` + `done_at: <ISO date>`. **Then `git mv docs/modernization/stories/S-NNN-<slug>.md docs/modernization/stories/implemented/S-NNN-<slug>.md` in the same commit.** Folding the archive into the mark-done commit means `/modernize-finalize`'s Step 2.5 doesn't need to fire a second CI cycle just to relocate the file. Mandatory ordering (git-mv trap guard):

1. Edit at the ORIGINAL path FIRST (`status: done` + `done_at` stamps).
2. `git mv` SECOND.
3. `git add <new-path>` THIRD to stage the post-mv content.
4. `git diff --cached --name-status` to verify: must show `R<NN>` rename. `git diff --cached --stat` shows BOTH rename AND additions. If "0 insertions, 0 deletions" with rename-detection, the trap fired — re-stage and re-check.

Then bundle any rework follow-ups this story closes (e.g. a fold-in stub like `S-123` for S-003): stamp them `status: done` + `done_at` + `resolved_by: S-NNN`, `git mv` to `implemented/`, include in the same commit.

Commit subject: `#N: mark done`. Body: `Closes #N` (plus `Closes #M` for any fold-ins with their own GitHub issue). Push. Watch CI; resume Step 5 CI-failure-handling if red.

**Ready-for-review:** `gh pr ready <PR>`. Apply `status/done` label if it exists (`gh issue edit N --add-label status/done --remove-label status/in-progress`). Post one final summary comment on the issue (parity layer, commit count, CI outcomes, PR URL).

### Step 9 — Done report

Print to operator:

- Story ID + title.
- Branch / GH issue (#N + URL) / PR (#M + URL, `READY_FOR_REVIEW`).
- Parity strategy used + rationale (one sentence, especially if dropped below e2e).
- Commit count + each subject line.
- CI run outcomes.
- Files changed (count + area summary).
- Tests added (count by layer).
- AC verification status (test name or manual-check per criterion).
- Self-review consult outcome (`(none)` / `<N> blockers fixed in <commit>` / `escalated`).
- Specialist consults made (which / what / followed?).
- Parallel sub-agents used (if any).
- CI / push fallback used (if any).
- Doc updates (`CONVENTIONS.md` lines added) or `(no doc changes)`.
- ADR amendments **proposed** (operator decides; don't auto-edit ADRs).
- Suggested next: `/modernize-review S-NNN` → `/modernize-rework` (if findings) → `/modernize-finalize`.

## Quality bar

- One story per invocation.
- `refined: false` is a hard bail.
- Parity strategy + red tests FIRST. Watch tests fail for the right reason.
- Parity assertions = observable behavior, never legacy API shape (exception: shapes the refinement explicitly preserves).
- Honor refinement contracts (Design / Security / Test / Performance / Open questions).
- Per [ADR 0022](../../../docs/modernization/adrs/0022-modernization-primary-directives.md): schema is structural; business rules on aggregates. A new CHECK constraint / generated column / trigger in a migration is a self-review blocker unless inline-justified as structural.
- Commit per work-package; push at locally-green boundaries; don't push past red.
- One GitHub issue per story. Milestone comments only, no per-commit chatter.
- Code is self-explanatory. Default to no comments. Add only when *why* is non-obvious. Never reference current task / fix / issue in code comments.
- Never embed git SHAs in committed docs. Cite by subject / file:line / PR# / story-ID.
- The skill does not merge PRs. Does not auto-edit ADRs. Does not delete GitHub issues.

## Not in scope

ADR edits (propose in done report), refinement re-runs (operator's call), code-review (`/modernize-review` is the next phase), merging (`/modernize-finalize` is the operator's tool), production deploys (S-121).

## When done

Story is `status: done`, tests green, PR ready-for-review, operator has next action.
