---
name: modernize-refine-ahead
description: Speculative-buffer-fill variant of /modernize-refine — refines the next N unrefined-but-unblocked stories from _ORDER.md ahead of when implement actually needs them. Trigger: /modernize-refine-ahead [N] (default 5).
---

# Modernize — Refine Ahead (speculative buffer fill)

You are running a **buffer-fill** variant of phase 5. Where `/modernize-refine` is strictly just-in-time (one story, refined the moment the operator is about to implement it), this skill deliberately refines a small batch of upcoming stories *before* the operator needs them, so the implement phase never blocks on cold specs.

The motivating asymmetry: refinement is read-only, takes ~3 minutes per story (5 parallel specialists), and runs entirely in markdown — no working tree changes, no commits, no merge conflicts. Implement is the hot path; refinement shouldn't be on its critical path when it doesn't have to be.

## When to use

- Before kicking off a run of `/modernize-implement` invocations — populate the buffer first.
- As part of the daily / weekly cadence: keep a rolling buffer of `N` refined-but-not-implemented stories.
- Before delegating an implementation batch to a fleet orchestrator (`/modernize-fleet`) — the fleet picks refined stories; speculative refinement keeps the picker fed.

## When NOT to use

- When the upcoming stories are in flux (ADR amendments in flight, decompose still settling). Speculative refinement of moving targets is wasted work.
- When the operator already has a concrete next story and wants the freshest possible refinement — that's the original `/modernize-refine S-NNN` use case, not this one.
- As a replacement for `/modernize-refine` in normal flow. The skills coexist: this one fills the buffer; the JIT one tops up the next story right before implement.

## Preconditions

1. The argument is an optional integer `N` (default 5). `N ≥ 1`. If the operator passed something else, ask. Hard cap `N ≤ 20` — beyond that, refinement starts being stale before it's used.
2. `docs/modernization/stories/_ORDER.md` exists and is parseable.
3. At least one eligible story exists per the selection rule below. If none, report "buffer already full / no unblocked eligible stories" and exit cleanly.

These are the only legitimate `AskUserQuestion` calls. Story selection and refinement itself are deterministic.

## Story selection rule

Walk `_ORDER.md` top-to-bottom. A story is **eligible** for speculative refinement when **all** of:

1. `status: todo` in its frontmatter (not `in_progress`, `done`, or `blocked`).
2. `refined: false` (or `refined:` field absent — same thing).
3. Every story listed in `depends_on:` has `status: done` AND `merged: true` (the dependency's code has actually landed). A dependency whose PR is still open is too soft a foundation — refinement against a moving target produces stale advice.
4. The story is not in an explicitly-skipped epic (see the `Skipped-epics list` below — empty by default).

Stop selecting at the first ineligible-and-not-yet-refined story whose ineligibility is a hard block (missing dependency), **except** that you may skip past stories that are temporarily ineligible because they're already `refined: true` (those are fine — they were refined earlier; just don't double-refine).

Hard stop conditions encountered before reaching `N`:
- A `status: blocked` story with no clear unblock path — skip and continue.
- A story whose `depends_on` is malformed or missing — skip and surface in the report.

Pick up to `N` eligible stories in `_ORDER.md` order. If fewer than `N` are eligible, refine what you have and report the gap.

## How to refine each story

For each selected story in order, run the **exact same flow as `/modernize-refine S-NNN`**:

1. **Step 1 — Load context** (target story file, ADRs, seed / current-state / vision, `_ORDER.md`).
2. **Step 1.5 — Context7 freshness pass** for the libraries the story touches.
3. **Step 2 — Spawn the five specialists in parallel** — `requirements-engineer`, `solution-architect`, `security-engineer`, `qa-engineer`, `performance-engineer`. Single message, five Agent calls.
4. **Step 3 — Synthesize** the five outputs.
5. **Step 4 — Write the refinement sections back** into the story file between the `<!-- modernize-refine: start -->` / `end -->` delimiters.
6. **Step 5 — Update frontmatter** with `refined: true`, `refined_at`, `refined_specialists`, plus the **speculative-refinement marker** described below.

Read the full per-step rules in `/modernize-refine`'s SKILL.md. This skill is a **multi-story wrapper** over that one — do not re-derive the inner steps here.

### Speculative-refinement marker

In addition to the regular frontmatter stamps, add:

```yaml
refined_speculative: true
refined_speculative_at: <ISO date>
```

This marker tells `/modernize-implement` that the refinement was done ahead of implement time. The implement skill can use it to decide whether to **re-refine** before starting (recommended threshold: re-refine if `refined_speculative_at` is older than 14 days, *or* if any ADR in the story's `adr_refs` has a modification date newer than `refined_speculative_at`, *or* if any `depends_on` story merged after `refined_speculative_at`).

If `/modernize-refine S-NNN` is later run on the same story (operator topping up just before implement), the regular `refined_at` field is updated and the `refined_speculative: true` marker is removed (replaced with `refined_speculative: false` plus a new `refined_at`). That signals "the spec is now fresh, not stale-by-design."

### Concurrency rule

**Stories run serially; specialists within each story run in parallel.** Five specialists × N stories spawned all at once would create 5N concurrent subagents, which is wasteful (synthesis is serial in the parent) and risks rate limits. The right shape is:

```
for story in selected_stories:
    spawn 5 specialists in parallel
    synthesize when all 5 return
    write back to story file
```

If you genuinely need higher throughput than this gives, use `/modernize-fleet` (which dispatches stories to worktrees and runs the full refine→implement→review cycle in parallel).

## Step-by-step orchestration

### Step 1 — Resolve `N` and select stories

1. Parse the argument. Default `N = 5` if absent.
2. Read `_ORDER.md`, walk top-to-bottom, find the first `N` eligible stories per the selection rule.
3. Report the selection to the operator **before** spawning specialists:

   ```
   Selected for speculative refinement (in _ORDER.md order):
   1. S-NNN — <title>
   2. S-NNN — <title>
   ...
   <K> stories selected (<N> requested, <N-K> ineligible: <one-line reason per skip>).
   Proceed? [Y/n]
   ```

   This is the **only** mid-run prompt. Default Y; operator can override.

### Step 2 — Refine each selected story serially

For each story, run the regular `/modernize-refine` flow (Steps 1-5 of that skill) end-to-end before moving to the next. Use TaskCreate to track the per-story progress; mark each story's task in-progress when starting and completed when frontmatter is stamped.

If any individual story refinement fails (e.g. a specialist returns broken output and re-running it still fails, or the story file is malformed), **continue to the next story** — don't let one bad story abort the whole batch. Surface the failure in the report.

### Step 3 — Final report

Print to the user:

- **Stories refined:** count + per-story headline ("S-NNN — `Open design questions` count: 0 / refinement size delta: +N lines").
- **Stories skipped:** count + per-story reason (ineligible, malformed, specialist-failure).
- **Buffer state:** approximate count of refined-but-not-implemented stories now in the buffer (scan `_ORDER.md` for `status: todo` + `refined: true`).
- **Suggested next action:** `/modernize-implement <S-NNN>` against the freshly-refined story at the top of the buffer (the first eligible `status: todo` + `refined: true` in `_ORDER.md`).

## Quality bar

- **Specialist-fan-out concurrency is per-story, not cross-story.** Five parallel per story; stories serial. Cross-story parallel is the fleet skill's job, not this one's.
- **Skip already-refined stories silently.** Don't double-refine to "improve" a prior refinement — that's what `/modernize-refine S-NNN` (without `-ahead`) is for.
- **Mark every output as speculative.** `refined_speculative: true` is the breadcrumb that lets `/modernize-implement` decide whether to re-refine.
- **Hard cap at `N = 20`.** Beyond that, refinement decays faster than implement can consume the buffer; you'd be doing work that has to be redone.
- **Eligible-only selection.** A story whose `depends_on` is unmerged is *not* eligible — refining against a moving foundation produces advice that won't survive contact with reality.
- **Continue on per-story failure.** One bad story doesn't abort the batch. Report and move on; the operator addresses skipped stories individually.
- **Skill does not commit.** Markdown edits land in the working tree; the operator commits when they're happy. This matches `/modernize-refine`'s non-committing posture.

## What this skill does *not* do

- It does not run `/modernize-implement`. The buffer is for implement to consume later; this skill only fills it.
- It does not re-refine `refined: true` stories. If the operator wants a re-refine, they invoke `/modernize-refine S-NNN` directly.
- It does not edit `_ORDER.md`. Order is set at decompose / rework time.
- It does not modify acceptance criteria, ADRs, or epics. Refinement is read-only on those artifacts.
- It does not push, commit, or open PRs. Working-tree only.
- It does not refine across worktrees. That's the fleet skill.
- It does not promise freshness. Speculatively-refined stories may go stale before implement starts; the `refined_speculative_at` stamp + the implement skill's re-refine heuristic is the mitigation.

## When done

`N` (or fewer) stories now have `refined: true` + `refined_speculative: true` frontmatter and full refinement sections. The buffer is fuller. The operator can run `/modernize-implement S-NNN` against the next refined story without waiting on a per-story refine roundtrip.

If the operator wants to keep the buffer topped up on a recurring cadence, they can wrap this skill in `/loop` (e.g. `/loop 30m /modernize-refine-ahead 3`) — but the skill itself is one-shot per invocation.
