---
name: do-retro
description: Improve the suite from what shipping just taught you — tune the do-* skills/agents, propose ADR amendments, re-shape the backlog, do memory hygiene, file infra/efficiency journeys. Purely manual; reconstructs lessons from git + PR threads. Trigger: /do-retro.
---

# do-retro — the suite's immune system

After a batch of journeys ship, digest what they taught and feed it back into
the tooling, the architecture, the backlog, and memory. This is the only
do-* skill that changes the suite itself.

Read [ADR 0022](../../../docs/modernization/adrs/0022-modernization-primary-directives.md).
Per directive 1: skills/agents exist to ship behavior — improve them when they
got in the way, not to polish prose.

**Search posture.** Default to MCP servers over raw grep: use the IntelliJ MCP
(`search_in_files_by_regex`, `search_in_files_by_text`, `find_files_by_glob`,
`search_symbol`) for code/PR archaeology and the **codebase-memory-mcp** for
recalling and updating durable lessons. Fall back to `Grep`/`Glob` only when no
MCP server is connected.

## Cadence

**Purely manual, no breadcrumbs.** `/do-ship` writes no retro log — it stays
lean. When invoked, `/do-retro` reconstructs the lessons itself from git history
(commit messages, the shape of the diffs, fix-CI churn), merged PR threads
(review comments, escalations, gap-hunter findings), and the journeys that
landed since the last retro. Run it deliberately — end of day, after a batch,
before a release — not on a loop.

## What it digests

Scan the journeys shipped since the last retro. For each, look for the
**friction signals**:

- Operator interventions / escalations (a journey that needed a human call).
- `gap-hunter` catches (a hollow-green that slipped to the gate).
- Flaky or repeatedly-red full-chain runs (proof-chain fragility).
- Mocked-seam signoffs that recurred (a real seam the chain can't yet exercise).
- Wallclock-budget breaches (a slow loop nobody fixed).
- Re-carves (`/do-plan` had to re-shape a journey mid-flight → the carve
  heuristic was wrong).
- Task-boundary misses (a `/do-task` worker escalated, drifted past its one
  task, or a task was too big for a clean context → `/do-ship`'s task-list
  decomposition needs tuning).

## What it's allowed to change

1. **Tune the suite itself.** Edit `do-plan` / `do-ship` / `do-task` / `do-retro`
   SKILL.md and the four agents (`legacy-oracle`, `slice-carver`, `gap-hunter`,
   `e2e-driver`) where a recurring friction shows the instructions were wrong or
   thin. Keep skill files ≤ 200 lines, agents ≤ 100. Smallest edit that removes
   the friction.
2. **Propose ADR amendments.** When a journey revealed an architecture decision
   was wrong/incomplete, draft the amendment to `docs/modernization/adrs/` for
   operator approval — **propose, don't auto-apply** load-bearing ADR changes.
3. **Re-shape the backlog.** File follow-up journeys / re-carves when shipping
   showed the plan was wrong (a journey too big, a missing screen, a wrong dep).
   File **infrastructure and efficiency journeys** (proof-chain speedups,
   snapshot reuse, flake fixes, CI sharding) the same way. Feeds `/do-plan`.
4. **Memory hygiene** (via `codebase-memory-mcp` + `.claude/memory/`):
   - **Capture** durable, non-obvious lessons (feedback/project facts).
   - **Update** stale memories whose facts the recent work changed.
   - **Compact** redundant/overlapping memories into one.
   Follow the memory-file convention (frontmatter + `MEMORY.md` pointer).
5. **Prune verified helper e2e.** Scan e2e specs tagged `@helper` with a
   `covered-by: <IntegrationTest>` pointer. For each, verify the named
   integration/unit test **exists and passes**; if so, delete the e2e helper and
   report it (the tag is the author's pre-authorization; the deletion is
   reversible via git). Keeps the expensive e2e suite to wiring + happy paths as
   cheaper tests take over the logic/error cases. Never touch un-tagged or
   wiring/happy-path specs.

## Coexist-then-retire (the modernize-* sunset)

The do-* suite coexists with the legacy `modernize-*` skills + 12 agents. Once
do-* is proven on 2-3 real journeys, `/do-retro` files the **cleanup journey**
that deletes the superseded `modernize-*` skills/agents and prunes the
`rolled_up_into:` horizontal stories. Don't delete on day one — file it when the
shipped evidence says do-* covers the ground. The 47 `implemented/` stories and
their docs stay as history.

## Procedure

1. Reconstruct the since-last-retro journey set (git log on `implemented/`,
   merged PRs). Note the date window in the report.
2. Extract friction signals per journey (above).
3. For each recurring signal, decide the smallest fix and which lever it
   belongs to (suite edit / ADR amendment / backlog journey / memory).
4. Apply suite edits + memory hygiene directly. **Draft** ADR amendments and
   **file** backlog journeys (don't implement them).
5. If the 2-3-journey bar is met, file the modernize-* cleanup journey.
6. Report (below). Don't squash-merge anything — this skill edits tooling on the
   current branch; the operator reviews the diff.

## Quality bar

- Every suite edit traces to a concrete friction signal — no speculative tuning.
- ADR amendments are proposed, never auto-applied.
- Backlog changes are filed as journeys, not implemented here.
- Memory edits follow the convention; redundant memories are merged, not piled.
- Skill ≤ 200 lines, agent ≤ 100 lines after edits.

## When done

Print: the journey window digested; friction signals found; suite edits made
(file + one-line why each); ADR amendments drafted (operator decides); backlog /
infra / efficiency journeys filed; memory captured/updated/compacted; whether
the modernize-* cleanup journey was filed and why.

## Not in scope

Implementing the journeys it files (that's `/do-ship`), carving the roadmap
(that's `/do-plan`), merging PRs, auto-applying ADR changes.
