---
name: modernize-implement
description: Phase 6 — implement one refined story end-to-end. TDD, work-package commits, story branch + draft PR, CI watch. Trigger: /modernize-implement S-NNN.
---

# Phase 6 — Story Implementation

Take one refined story (`S-NNN`) and ship it: code per the design notes, tests per the QA plan, tests green, story `status: done`, PR ready-for-review.

Read [ADR 0022](../../../docs/modernization/adrs/0022-modernization-primary-directives.md) before starting. The two directives govern every decision below.

## Story ID resolution

The story ID can be passed explicitly (`S-NNN`) or inferred from the current branch when it matches `story/S-NNN-*` (check via `git rev-parse --abbrev-ref HEAD`; pattern `^story/S-(\d{3})(-.*)?$`):

- **Arg + branch match** → proceed with the arg.
- **Arg + branch is `story/S-MMM-*` where `MMM ≠ NNN`** → bail: *"current branch is `story/S-MMM-...` but you passed `S-NNN`; switch branch or correct the arg."*
- **Arg + branch isn't a story branch** → proceed with the arg (this is the normal case for implement — branch is `main` until Step 2 creates it).
- **No arg + branch matches `story/S-NNN-*`** → use the branch's `S-NNN` (lets you resume an in-flight story without retyping the ID).
- **No arg + branch doesn't match** → prompt the operator for the story ID via `AskUserQuestion` (single question).

## Preconditions

Bail if any fail:

1. Story ID resolved per § Story ID resolution above. Story file at `docs/modernization/stories/S-NNN-*.md` (top-level; refuse if in `implemented/`).
2. Frontmatter has `refined: true` (else "run /modernize-refine first").
3. `status: todo` (in_progress → ask to resume; done/blocked → refuse).
4. Every `depends_on` story is `done` AND its `github_pr` is `MERGED` (or no PR exists).
5. Working tree clean.
6. On `main` (or configured default) — unless resuming an `in_progress` story on its own branch.

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
- **`ScheduleWakeup` hygiene.** If you schedule a long-fallback wakeup (`delaySeconds ≥ 1200`, `prompt: "/modernize-implement S-NNN"`) while a CI watch runs in the background, the harness's task-notification path will normally re-invoke you faster — the wakeup is just insurance against a hang. Don't schedule a fresh fallback after every CI watch completes; one outstanding fallback is enough. After the **final** push (Step 8 mark-done) returns green, you have no more work to babysit — do NOT schedule a fresh fallback there. A stale wakeup firing 30 min later will re-enter `/modernize-implement` on a `status: done` story and bounce off the precondition; cheap, but noisy.

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

### Step 7 — Pre-push reviewer panel + auto-fix loop

Before the final status-flip commit, run the full reviewer panel against `git diff main...HEAD`. Findings come back as `[blocker]` / `[improvement]` / `[nudge]`; **fix all three severities inline** (auto-fix policy — the implement skill owns its review). No `## Review` story-body section is written; the fix lives in code commits + the PR diff.

**Scope flags** (compute once before dispatch):
- `has_frontend` — any `alpenflight/web/` path **that is NOT auto-generated** (skip OpenAPI snapshot + codegen artifacts under `alpenflight/web/src/app/api/generated/`).
- `has_backend` — any `alpenflight/server/` path.
- `has_legacy_ref` — any `flsserver/` or `flsweb/` path.
- `is_docs_only` — every path matches `docs/**`, `*.md`, `CONVENTIONS.md`, or `alpenflight/ops/*.sh|*.json`.

**Reviewer dispatch** (spawn all applicable in ONE message — parallel):

| Reviewer | Spawn when |
|---|---|
| `maintainability-reviewer` | always |
| `security-reviewer` | not `is_docs_only` |
| `parity-reviewer` | `parity_test` non-empty OR `has_legacy_ref` |
| `usability-reviewer` | `has_frontend` (real UI changes, not codegen) |
| `tech-writer-reviewer` | NOT `has_frontend` (replaces usability for backend / docs-only diffs) |

Each spawn carries: absolute path to the story file + ADRs in `adr_refs`, diff range SHAs + changed-path list, the refinement section relevant to that reviewer, project context (`@TenantId`, sacred cows, ADR 0022 directives), and library facts from any Context7 lookups. Output format: **findings only, one bullet each, `file:line` cite, severity tag, no padding, omit empty dimensions**.

**Auto-fix loop:**
1. Collect findings from all reviewers.
2. Fix every finding inline. One commit per logical batch (e.g. "ADR + CONVENTIONS doc fixes" / "ArchUnit rule additions" / "package-info corrections"). Subject `#N: self-review fixes — <summary>`.
3. Push. Watch CI in the background per Step 5.
4. **Re-run the same reviewer panel against the freshened head.** Hard cap: 2 review rounds total. If round 2 still surfaces blockers, **escalate per Step 6** ("auto-fix didn't converge — needs a design pivot or operator call"). Operator can then invoke `/modernize-rework S-NNN` explicitly.

**Escalation triggers** (in addition to Step 6's general triggers): a reviewer-emitted blocker that requires changing the story's ACs, an ADR, or a sacred-cow contract — not a fix-the-code-and-move-on finding. Surface to operator with one question; default action is "invoke `/modernize-rework`".

Skip the panel only for bookkeeping-only diffs (the implement skill's own metadata edits); note the skip in the done report.

### Step 8 — Prune the story + status:done

The story body was a plan. The code is now the source of truth for everything the plan covered. **Cut the story down to what the code can't carry.** This is not bookkeeping — it's the discipline that keeps planning docs from rotting and misleading future readers.

**Prune pass** (walk every section between `<!-- modernize-refine: start --> / end -->` and outside it; delete liberally):

Keep, but only when load-bearing:

- AC frontmatter (always).
- 1-paragraph `## Context` if the *why* is non-obvious from the PR title.
- Cross-story contracts (consumes / produces by ID) where the contract isn't visible at a call site.
- Rip-out plans, deprecation flags, sunset markers.
- Parity exclusions + the reason.
- `## Open design questions` answers that the operator needs to see surfaced.

Delete:

- File trees, package layouts, method signatures, DTO field lists — `ls`, `grep`, the code itself.
- Test method names, test-method tables — the test files name themselves.
- Threat-model rows whose mitigations landed in code (the code carries it; the row is noise).
- **`## Proposed ADR amendment` (and any other "TODO operator decides" section).** Either resolve in the same PR (apply the ADR amendment yourself per the rework propagation-check rule and delete the section) OR move to a deferred follow-up story (`origin: rework-meta`, `kind: adr-amendment`). NEVER carry as "load-bearing decision" — the next review WILL re-flag it as a blocker for self-contradiction with the resolved `[accepted]` annotation in the review block.
- Latency budgets that aren't separately measured.
- "Alternatives considered" — the PR description holds the rejection rationale.
- "Implementation deviations from refined design" sections — drift-tracking is not value; the code is the answer.
- `## Tasks` lists — superseded by the AC checklist.
- Stale `## Implementation status (paused …)` / `## Pickup notes`.

Also strip `#N:` commit-subject tokens + 7+ hex-char SHA tokens from body text (cite by file:line / PR# / story-ID).

**Soft target after prune:** the implemented story file fits comfortably in one screen of context. If it doesn't, ask which paragraphs a future reader would actually act on; cut the rest.

Commit prune as `#N: prune story to load-bearing decisions`. Skip for bookkeeping-only diffs.

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
- Suggested next: `/modernize-finalize S-NNN` (docs-prune pass + squash-merge). Use `/modernize-rework S-NNN` only if you want to revisit scope / design.

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
- **Prune the story body before marking done.** Planning docs that the code now documents should be deleted, not preserved as drift-tracking. See Step 8.
- The skill does not merge PRs. Does not auto-edit ADRs. Does not delete GitHub issues.

## Not in scope

ADR edits (propose in done report — operator surfaces at finalize), refinement re-runs (operator's call), scope / design pivots (`/modernize-rework` is operator-invoked), merging (`/modernize-finalize` is the operator's tool), production deploys (S-121).

## When done

Story is `status: done`, tests green, PR ready-for-review, operator has next action.
