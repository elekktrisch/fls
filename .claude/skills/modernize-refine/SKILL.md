---
name: modernize-refine
description: Phase 5 — refine one story by spawning 5 specialist subagents in parallel (requirements/solution/security/qa/performance-engineer); synthesize into the story file. Trigger: /modernize-refine S-NNN.
---

# Phase 5 — Story Refinement (just-in-time)

You are running phase 5 of the modernization workflow. Your job is to take **one** story (S-NNN) at a time — the one the user is about to start — and turn its draft acceptance criteria + tasks into an implementation-ready spec by spawning five specialist subagents in parallel and synthesizing their output back into the story file.

Refinement is **just-in-time, not batch**: never refine more than one story per invocation. The user invokes the skill again for the next story when they're ready. Stale refinement is worse than no refinement — most of the project's 122 stories will be touched only once.

## Preconditions

1. The argument is a single story ID: `S-NNN`. If the user passed something else, ask for the ID.
2. The story file exists at `docs/modernization/stories/S-NNN-*.md`. If not, bail.
3. The story is not already `status: done`. If it is, ask the user whether to re-refine (warn that this overwrites prior refinement).
4. If `refined: true` is already set in the frontmatter, warn the user and ask: re-refine (overwrite the existing refinement sections) or abort.

These are the **only** legitimate `AskUserQuestion` calls. Everything else is derivable.

## How to refine

### Step 1 — Load context

Read in parallel:
- The target story file.
- Every ADR listed in the story's `adr_refs`.
- `00-seed.md`, `01-current-state.md`, `02-vision-and-constraints.md` — for the project-wide invariants.
- `_ORDER.md` — to confirm `depends_on` are real and to find the relevant up-stream stories.

You should *not* read every story or every ADR — only the ones this story depends on or references. Keep context focused.

### Step 2 — Spawn the five specialists in parallel

Launch all five subagents in a single message with five Agent tool calls:

- `requirements-engineer` — surfaces edge cases, hidden requirements, NFR call-outs.
- `solution-architect` — module layout, domain model, API surface, alternatives considered.
- `security-engineer` — threat model, authorization, validation, PII, audit events, tenancy.
- `qa-engineer` — test pyramid, specific test cases, parity-test design, fixtures, coverage gaps.
- `performance-engineer` — hot paths, required indexes, N+1 risks, caching, latency budget.

Each subagent's prompt **must include**:
- The absolute path to the story file.
- The absolute paths to the ADRs referenced by `adr_refs`.
- The story's `depends_on` IDs (so the agent can read those stories' refinements if they exist).
- A brief reminder of the project context (the 122-story FLS modernization, sacred cows, multi-tenancy by `@TenantId`).
- The agent's output format (already in their system prompt, but call it out so they emit it cleanly).

Send the five Agent calls in **one message** so they run concurrently. Each returns a single markdown blob in their agent-defined format.

### Step 3 — Synthesize, don't re-decide

The five outputs are inputs, not drafts. You compose them into the story file. You do not re-argue what they said.

**Conflict resolution:**
- If two specialists' recommendations conflict (e.g. architect says "cache aggressively" and security-engineer says "don't cache the tenant config"), capture both views in a new `## Open design questions` section and flag for operator input.
- If a specialist's output is empty for a category that genuinely doesn't apply (e.g. performance-engineer on a pure-schema story), preserve their "(N/A)" note rather than dropping the section.
- If a specialist produced clearly broken output (no structured sections, hallucinated paths), re-run that one specialist with a clarifying prompt. Don't synthesize garbage.

### Step 4 — Write the refinement back into the story file

Append (or replace, if already present) these sections **after the existing body** of the story file, in this order:

```markdown
## Design notes
<from solution-architect — full output minus the headings, restructured into a flowing section>

## Edge cases & hidden requirements
<from requirements-engineer>

## Security plan
<from security-engineer>

## Test plan
<from qa-engineer>

## Performance plan
<from performance-engineer>

## Open design questions
<populated only if conflicts surfaced — else omit the section entirely>
```

**Idempotency rule:** Re-running the skill on the same story **replaces** the above sections atomically. Anything else in the story body is preserved verbatim. Use a stable delimiter comment (`<!-- modernize-refine: start -->` / `<!-- modernize-refine: end -->`) so the replace is safe across re-runs.

### Step 5 — Update frontmatter

Add or update in the story's YAML frontmatter:

```yaml
refined: true
refined_at: <today's date, ISO>
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
```

If any specialist was skipped (e.g. performance-engineer on a pure-doc story), reflect that in `refined_specialists`. Do not pretend an agent ran when it didn't.

### Step 6 — Report back

Print to the user:

- The story ID and title.
- A 1-line summary per specialist of what they added (the headline of each section).
- Whether `## Open design questions` was populated and the count.
- Total refinement size delta (lines added).
- Suggested next action: `/modernize-implement S-NNN`.

## Quality bar

- **One story per invocation.** Batching is forbidden — refinement is JIT by design.
- **Five specialists, one parallel batch.** Sequential spawning wastes wall-clock.
- **Synthesis is mechanical, not editorial.** The specialists own the analysis; you own the layout. Don't paraphrase their findings into something weaker.
- **Replace, don't append, on re-run.** Refining twice should not double the file.
- **Frontmatter must reflect reality** — `refined_specialists` lists who actually ran.
- **Open design questions surface conflicts** — they are not "things I think the operator should know about." If a conflict exists, list it. If not, omit the section.

## What this skill does *not* do

- It does not modify acceptance criteria — those came from `/modernize-decompose`. If the refinement reveals an acceptance criterion is wrong, surface it in `## Open design questions`, don't silently fix it.
- It does not generate code. That's `/modernize-implement`.
- It does not refine epics. Epics are read by the specialists (for context) but not modified.
- It does not check `depends_on` are `done`. That's `/modernize-implement`'s precondition.
- It does not commit. Markdown edits land in the working tree; the operator commits when they're happy.

## When done

The story file has five (or six, if conflicts) new sections and refined-status frontmatter. The user has the next-action prompt. No other artifacts are touched.

If the user wants to refine the next story in `_ORDER.md`, they invoke `/modernize-refine <next-S-id>`. The skill has no batch mode.
