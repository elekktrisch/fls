---
name: modernize-fleet
description: Parallel-fleet orchestrator — dispatches N independent unblocked stories to isolated git worktrees and runs refine→implement→review concurrently per story. Batches checkpoints. Trigger: /modernize-fleet [N] (default 3).
---

# Modernize — Fleet (parallel story execution)

You are running a **fleet orchestrator** over the modernization workflow. Where `/modernize-refine` → `/modernize-implement` → `/modernize-review` runs one story at a time, this skill picks N independent unblocked stories, dispatches each to its own git worktree via a `general-purpose` subagent with `isolation: "worktree"`, and runs the refine→implement→review flow **concurrently per story**.

The wins:

- **Throughput** — N stories in roughly the wall-clock of one. For the leaf-heavy phases (CRUD runs, observability stories, the long tail of small stories), this is a 3-10x acceleration.
- **Batched operator attention** — instead of N sequential rework sessions of 20 minutes each, one batched session at the end where every story's findings surface together.
- **Crash-isolated work units** — a failure in story X doesn't poison stories Y/Z. Each worktree is independent.

The trade-off:

- **Fleet is leaf-only.** Foundational stories that touch shared schema, ADRs, or load-bearing infrastructure should *not* be fleeted — merge conflicts and inconsistent assumptions across worktrees would burn more time than the parallelism saves. Use the JIT skills for those.
- **Port allocation discipline required.** Each worktree may need its own dev-server / docker-compose port range. The skill assigns offsets, but the subagent must honor them.

## When to use

- Long tail of leaf stories (CRUDs, schema additions, observability, small follow-ups, scheduled jobs).
- Stories from different epics that have no shared file paths.
- After a `/modernize-refine-ahead N` run that filled the buffer — fleet picks refined stories first to skip the refinement roundtrip.

## When NOT to use

- Foundational stories (schema spine, auth bedrock, layout primitives). One story at a time keeps assumptions consistent.
- Stories that share a file path. Two worktrees both editing `next/server/src/main/java/.../FlightController.java` will merge-conflict.
- Stories in a parity-flagged epic where the parity oracle is itself being built (the harness story should land before the stories depending on it).
- The first few stories of an epic. Establishing a pattern in parallel produces three diverging patterns. Land the first story sequentially; fleet the rest.

## Preconditions

1. The argument is an optional integer `N` (default 3). `N ≥ 2`. Hard cap `N ≤ 5` for the initial release — beyond that, coordination overhead and operator-attention budget exceed the parallelism benefit.
2. Working tree is clean and current branch is `main` (or default).
3. `git worktree` works (some sandbox environments restrict it — try `git worktree list` and bail with a clear message if it errors).
4. `gh auth status` is OK and a GitHub remote exists. Fleet without GitHub is theoretically possible but the coordination cost goes up; require GitHub for now.
5. The story corpus has at least 2 fleet-eligible stories per the selection rule below. If fewer, refuse and recommend the JIT flow.

These are the only blocking conditions.

## Story selection rule

A story is **fleet-eligible** when **all** of:

1. `status: todo` (not in-flight, not done, not blocked).
2. All `depends_on` entries have `merged: true`.
3. The story has not been called out as foundational. Heuristic — fleet-INELIGIBLE if any of:
   - The story is in epic E-01 (foundations) or E-02 (database/migration spine) or E-03 (identity/auth/tenancy).
   - The story's `parity_test` references a harness that doesn't yet exist (e.g. `delivery-test-harness` referenced before S-079 is merged).
   - The story's `## Design notes` (if refined) explicitly says "must be implemented before parallel work begins."
4. The story does not share a file path with any other selected story. Pairwise check: scan each candidate's `## Tasks` and `## Design notes` for `next/server/src/.../<file>` and `next/web/src/.../<file>` references. Two stories with overlapping target paths are not co-fleetable; pick one for this run, defer the other.
5. The story is in a different *epic* from every other selected story (cross-epic preferred — within-epic increases conflict risk).

Walk `_ORDER.md` top-to-bottom. Apply the rules; collect up to `N` co-fleetable stories. If fewer than `N` are co-fleetable, fleet what you have and report the gap.

## How to fleet

### Step 1 — Select and confirm

1. Resolve `N` from the argument (default 3).
2. Apply the selection rule. Surface the candidate set:

   ```
   Fleet candidates (in _ORDER.md order, pairwise co-fleetable):
   1. S-NNN (epic E-XX) — <title> — paths: next/server/.../A.java
   2. S-NNN (epic E-XX) — <title> — paths: next/web/.../B.ts
   3. S-NNN (epic E-XX) — <title> — paths: next/server/.../C.java
   <K> selected (<N> requested, <N-K> ineligible: <one-line reason per skip>).
   Proceed? [Y/n]
   ```

3. Single confirmation prompt. Default Y. If operator says n, exit cleanly.

### Step 2 — Allocate worktrees + port offsets

For each selected story `S-NNN-i` (i = 0..K-1):

1. **Branch:** `story/S-NNN-<slug>` (same convention as the implement skill — fleet's branch is the implement branch).
2. **Port offset:** assign port-base `25500 + (i × 100)`. The subagent will receive this as `FLEET_PORT_OFFSET=<offset>` in its environment / prompt and must honor it when starting docker-compose, dev servers, etc. The implementation may not honor this yet — document the assumption in the subagent's prompt and surface in the final report any subagent that couldn't apply the offset.
3. The worktree itself is allocated by the Agent tool's `isolation: "worktree"` parameter — no manual `git worktree add` needed. The Agent's result contains the worktree path + branch.

### Step 3 — Spawn N parallel story-runner subagents

In a **single message with K tool uses**, spawn K Agent calls. Each Agent:

- `subagent_type: "general-purpose"`
- `isolation: "worktree"`
- `description`: `"Fleet runner: S-NNN"`
- `prompt`: see the **Subagent prompt template** below

The subagents run concurrently. Each writes commits to its own worktree, pushes its branch, opens a draft PR, runs review against the PR, posts findings, and returns a result blob.

#### Subagent prompt template

```
You are a fleet runner for story <S-NNN>, part of a parallel batch of <K> stories
running concurrently. Your worktree is isolated; you have your own checkout off
`main`. You will not see the other stories.

## Task

Execute the modernization workflow's refine → implement → review flow for this
ONE story, end-to-end, in your worktree. The flow is documented in three skills
that you should READ FIRST before doing any work:

1. /c/Users/roman/IdeaProjects/fls/.claude/skills/modernize-refine/SKILL.md
2. /c/Users/roman/IdeaProjects/fls/.claude/skills/modernize-implement/SKILL.md
3. /c/Users/roman/IdeaProjects/fls/.claude/skills/modernize-review/SKILL.md

You DO NOT have access to the Skill tool (subagents can't invoke skills). Inline
the steps yourself: read each SKILL.md, then perform its work as documented.
Skip steps in modernize-refine if the story already has `refined: true` (just
proceed to implement). Skip modernize-refine entirely if the story has
`refined: true` and is up-to-date; otherwise run refine first.

## Story

Story file: /c/Users/roman/IdeaProjects/fls/docs/modernization/stories/S-NNN-<slug>.md
ADRs referenced: <list of full paths from frontmatter `adr_refs`>
Project conventions: /c/Users/roman/IdeaProjects/fls/CLAUDE.md +
  /c/Users/roman/IdeaProjects/fls/docs/modernization/00-seed.md +
  /c/Users/roman/IdeaProjects/fls/docs/modernization/02-vision-and-constraints.md

## Fleet-specific constraints

- **Port offset:** When starting any service (docker-compose, ng serve, gradle
  bootRun, Playwright runner), honor `FLEET_PORT_OFFSET=<offset>`. Default
  ports + offset = your worktree's actual ports. If a port is hardcoded in a
  config file and you can't override it without editing a file the operator
  will need to keep, surface that in your result blob and proceed without
  starting that service (skip integration tests that require it; flag clearly).
- **Single branch:** You are on branch `story/S-NNN-<slug>` (or will create it
  at the implement-skill's Step 2). Don't switch branches.
- **No interactive prompts.** The operator is not watching you — the fleet
  orchestrator will batch questions afterwards. If the implement skill says
  "stop and ask the operator" (Step 5 escalation), instead:
    - Capture the question, the context, and your best recommendation.
    - Mark this story as `status: in_progress` (not done) with a
      `fleet_escalation: <reason>` frontmatter field.
    - Commit + push current progress.
    - Return your result blob with `escalated: true` and the full question.
  The fleet orchestrator will surface the escalation to the operator in the
  batched checkpoint.
- **Self-review gate (Step 6.7 in modernize-implement):** RUN IT. The fleet
  flow relies on the self-review gate to cut down on review→rework loops.
- **Don't finalize.** Stop after `/modernize-review` writes findings. The
  fleet orchestrator runs `/modernize-sweep-finalize` (or interactive
  `/modernize-finalize` per story) afterwards based on operator decisions.

## Result blob

Return a single markdown block with:

```
## Fleet runner result: S-NNN

- **Status:** done | escalated | failed
- **Branch:** story/S-NNN-<slug>
- **Worktree path:** <auto-reported by isolation>
- **GitHub issue:** #N + URL (or null if fallback)
- **PR:** #M + URL (or null if fallback)
- **Refine outcome:** <re-refined / used existing speculative refinement / skipped (already JIT-refined)>
- **Implement outcome:** N commits, list of work-package titles
- **CI outcome:** green / red (which step?) / not run
- **Self-review outcome:** no blockers / N blockers fixed / escalated
- **Review outcome:** pass | improvements-only | blockers (+ counts per dimension)
- **Escalations (if any):** verbatim question + your recommendation
- **Port offset honored:** yes | partial: <which services skipped> | no: <reason>
- **Followup actions for operator:** one line per pending decision (rework prompts, ADR amendment proposals)
```

End your work after returning this blob. The fleet orchestrator owns the next steps.
```

### Step 4 — Wait + collect

Wait for all K Agent calls to complete. They run in parallel; collect their result blobs.

If any returned `failed`, surface the failure verbatim with the worktree path so the operator can inspect.

### Step 5 — Batched checkpoint — operator session

Surface to the operator the **batched** outcome table:

```
Fleet run complete (K stories, <wall-clock> elapsed):

| Story  | Status     | Review outcome      | Escalations |
|--------|------------|---------------------|-------------|
| S-NNN  | done       | improvements-only   | 0           |
| S-NNN  | done       | blockers (2)        | 0           |
| S-NNN  | escalated  | -                   | 1 (parity)  |
| ...    |            |                     |             |
```

Then, for each story that needs operator action, present a **single decision prompt** rather than walking back into a per-finding loop. Options surfaced to operator (one prompt per story):

- **Rework interactively** — `/modernize-rework S-NNN` (operator drives per-finding triage now).
- **Rework with --bold** — `/modernize-rework S-NNN --bold` (auto-triage the cheap end; surface only blockers + ambiguous improvements).
- **Defer** — leave the PR open and come back later. Fleet skill exits; next sweep / next operator pass picks it up.
- **Address escalation** — for `escalated` stories, present the question + recommendation + the worktree path. Operator decides; if continuing, operator resumes via `/modernize-implement S-NNN` (which honors `fleet_escalation` frontmatter).

This is the **only** mid-fleet operator session. After this, the operator either:
- Walked through rework on the stories that need it,
- Pushed the address-now fixes per the standard `/modernize-rework` flow,
- Re-invoked `/modernize-review` if needed,
- And is ready for finalize.

### Step 6 — Sweep-finalize

After the operator's rework session, invoke `/modernize-sweep-finalize` as the final step (this skill's responsibility — call it directly, don't ask the operator).

The sweep will:
- Finalize every story whose blockers are clear and CI is green.
- Defer stories with ADR amendments or `CHANGES_REQUESTED` to interactive `/modernize-finalize`.
- Report the batch outcome.

### Step 7 — Worktree cleanup

For each worktree that has been finalized (story `merged: true`):
- The Agent tool's `isolation: "worktree"` cleanup already removes the local worktree directory after the subagent returned (or left it with changes that have now been merged; either way `git worktree prune` removes stale entries).
- Run `git worktree prune` to clean any stale references.
- The remote branch was deleted by sweep-finalize.

For worktrees that are *not* yet finalized (escalated stories, deferred rework): leave them in place. The operator's follow-up `/modernize-implement S-NNN` will pick up the same branch on its own checkout.

### Step 8 — Final report

Print to the user:

- **Fleet started / completed:** ISO timestamps + wall-clock.
- **Stories attempted:** count + per-story status.
- **Stories merged (via sweep-finalize):** count + per-story merge-commit SHAs.
- **Stories pending operator action:** count + per-story pending action (rework, escalation, ADR amendment).
- **Aggregate diff:** total commits, total files changed, total lines +/-.
- **Port-offset issues (if any):** which subagents couldn't honor the offset and why. The operator may want to plumb the offset through the configs that didn't honor it.
- **Cross-worktree merge conflicts (if any):** stories whose final merge to main conflicted with another fleet-run story's merge. The operator addresses these manually.
- **Suggested next action:** another `/modernize-fleet N` if eligible stories remain; otherwise `/modernize-refine-ahead` to refill the buffer, then fleet again.

## Quality bar

- **Pairwise co-fleetable selection.** Two fleet-eligible stories that touch the same file path are not co-fleetable in the same run. The skill enforces this at selection time, not at merge time.
- **One subagent per story.** Don't spawn five-specialist fan-outs from the orchestrator — that's the inner skill's concern. Fleet runs one Agent per story; the Agent in turn spawns whatever fan-out the inner skills require, in its own worktree.
- **Single message, K tool uses.** All K Agent calls are dispatched in one message — that's what makes them run concurrently. Sequential dispatch would defeat the parallelism.
- **No interactive prompts inside subagents.** Subagents escalate by returning, never by asking. The operator session is batched at Step 5.
- **Finalize is serialized.** Even after K parallel runs, finalize lands on `main` one story at a time. Sweep-finalize handles the serialization.
- **Port offsets must be honored when applicable.** A subagent that can't honor the offset must surface why so the next fleet run doesn't hit the same wall.
- **Foundational stories are excluded.** Epic E-01/E-02/E-03 stories are not fleeted (selection rule 3). The skill enforces this; even if the operator manually picks foundational stories, the selection rule refuses.
- **Operator confirms selection once, decides per-story disposition once.** Two prompts total: Step 1 confirmation, Step 5 batched checkpoint. No mid-run questions.

## What this skill does *not* do

- It does not select stories that share file paths. Conflict-prevention at the input side, not at the merge side.
- It does not fleet foundational stories. Those are JIT-only.
- It does not merge PRs directly. Finalize goes through `/modernize-sweep-finalize` (or interactive `/modernize-finalize` for the deferred ones).
- It does not iterate. One fleet run per invocation; the operator re-invokes for the next batch.
- It does not maintain a queue across invocations. State lives in the story frontmatter; the next fleet run re-derives eligibility from the same source.
- It does not modify `_ORDER.md` directly. Selection respects the order; rework follow-ups (via `/modernize-rework`) are appended to `_ORDER.md` by that skill, not by fleet.
- It does not run `/modernize-rework`. The operator owns the rework session; fleet just batches the prompt-up.
- It does not auto-apply ADR amendments. Sweep-finalize defers those; they remain operator-owned via interactive finalize.

## When done

Up to `N` stories are merged to `main` (via sweep-finalize). Any escalations / un-addressed rework are surfaced to the operator with the worktree path + frontmatter breadcrumbs needed to resume work via the JIT skills. Worktrees for finalized stories are pruned; worktrees for unfinished stories are preserved.

The operator's next action is either another fleet run (if the buffer has eligible stories), a `/modernize-refine-ahead` to refill, or a JIT pass on any escalated story.
