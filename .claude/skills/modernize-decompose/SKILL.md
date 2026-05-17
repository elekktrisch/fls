---
name: modernize-decompose
description: Phase 4 — decompose the rewrite into epics + stories with dependency metadata under docs/modernization/{epics,stories}/. Trigger: /modernize-decompose (after phases 1-3).
---

# Phase 4 — Epic & Story Decomposition

Convert the feature inventory + ADRs into an actionable backlog: epics, stories, `_ORDER.md`.

Read [ADR 0022](../../../docs/modernization/adrs/0022-modernization-primary-directives.md). Per directive 1: a story body is "just enough to ship behavior" — ACs grounded in legacy code beat long context paragraphs.

## Preconditions

1. `01-current-state.md`, `02-vision-and-constraints.md`, `adrs/*.md` exist.
2. If `epics/` or `stories/` already populated, ask: **refresh** (regenerate) / **merge** (preserve existing, fill gaps) / **abort**.

## Core principle — maximum autonomy

Per [[feedback-derive-before-asking]]: `AskUserQuestion` is a last resort. Story writing is autonomous; legacy code is the primary source; the current-state doc + ADRs frame; uncertainty becomes an `## Assumptions made` line, not a blocking question.

**Operator reviews after artifacts exist**, not during construction. A backlog with assumptions the user can override beats a half-written backlog blocked on a question.

### What autonomous means

- **Read legacy code** for each epic — controllers, services, jobs, templates, schema, tests. Skim isn't enough; ACs reference *specific* legacy behaviors (state transitions, time gates, validation rules, edges), not paraphrased summaries.
- **Decide + document.** When two reasonable decompositions exist (bundle/split, order, parity-test placement), pick one, record alternative in `## Notes` as "Assumption: chose X over Y because Z; revisit if Y matters more."
- **Default to inclusion.** Feature in inventory + seed doesn't defer/drop = write the story.
- **Default to ADR conformance.** ADR follow-ups = write the story.
- **Default to parity tests for sacred-cow paths.** Task 1 of the story; operator says so if they want to skip.

### `AskUserQuestion` justified ONLY when

Answer fundamentally changes artifact structure AND isn't derivable from seed + vision + ADRs + current-state + legacy code:

- Pre-existing `epics/` or `stories/` — refresh / merge / abort.
- Feature in inventory contradicts a vision constraint such that resolution changes story shape.
- Required input file missing / corrupt.

That's the list. Anything else: pick, record, write.

### Never ask

- "Should we have a story for X?" — write it.
- "What should AC for X be?" — read the code, write them.
- "How big is this story?" — measure (entities / endpoints / templates touched), pick S/M/L.
- "Which ADRs apply?" — derivable.
- "What order should epics go in?" — write `_ORDER.md` from dependency + risk + sacred-cow weight.
- "Bundle X+Y or split?" — pick + note.
- "Parity test for X?" — yes if it touches a sacred cow; no otherwise.

## Procedure

### Step 0 — Pre-read discovery §8 "Findings pre-answered"

That section is the discovery skill's contract with this phase. Most AC content + ADR cross-refs are there. Pull forward; don't re-derive.

### Step 1 — Write epics (5-15)

Each epic = a feature-area cluster from inventory OR a cross-cutting concern from ADRs.

Per epic, BEFORE decomposing into stories:
1. Read legacy code for that area — list paths in epic body as "Legacy code touched."
2. List ADRs that constrain (from `adrs/*.md` follow-ups).
3. Measure: controller count / service count / entity count / e2e test count / rough lines. Feeds story estimates.

### Step 2 — Decompose into stories

A story is **independently shippable** — clear ACs, mergeable on its own, lands user-visible value or unblocks the next story.

**ACs grounded in code, not paraphrase:**
- ❌ "Flight state transitions work like the old system."
- ✅ "POST /api/v1/flights with `processStateId = Locked` + `lockedOn` 1 day in past returns 400 (mirrors `FlightService.cs:1380-1440` time-gate)."

**Per [ADR 0022 directive 2]: ACs avoid prescribing schema-level business logic.** Don't write "delivery.process_state CHECK IN (10, 20, 30, 99)" as an AC. Write "Delivery rejects out-of-set state transitions" — leave the implementation (aggregate method) to refine/implement.

**Estimates calibrated against measurements:**
- S (≤1 day): single endpoint or small entity, no integration, < 200 lines legacy touched.
- M (2-5 days): cluster of endpoints, one entity + DTOs, modest integration, 200-1000 lines.
- L (>5 days): cross-entity, external integration, or rules-engine-shaped. Split if possible.

**Parity-sensitive stories** (sacred cows) name a concrete `parity_test` — e2e file + test name, or "write parity test as task 1." Bare "parity test exists" rejected.

Story frontmatter:

```yaml
---
id: S-NNN
title: <short title>
epic: E-NN
status: todo
depends_on: [S-NNN, ...]
acceptance:
  - <one-line testable condition>
  - ...
estimate: S | M | L
adr_refs: [0001, 0002, ...]
parity_test: <e2e file + test name, or "none">
---
```

Stories with multiple sub-deliverables get a `## Tasks` checklist (not separate files).

### Step 3 — Story ordering (`_ORDER.md`)

1. **Foundational** stories first: bootstrap repos, CI, baseline auth, baseline DB, baseline frontend shell. Everything depends on these.
2. **Vertical slices** that prove the architecture end-to-end on a low-risk domain.
3. **Feature parity**, ordered by (a) external-integration risk, (b) user-visible criticality. Rules engine + accounting pipeline last (subtlest behavior).
4. **Cutover prep**: data migration, parity-validation harness, runbooks.

Topological sort by `depends_on`. Tie-break: stories with more downstream dependents win (do unblockers first).

## File formats

### Epic: `docs/modernization/epics/E-NN-<slug>.md`

```markdown
---
id: E-NN
title: <title>
status: todo
adr_refs: [...]
---

## Goal
One paragraph.

## Scope
- In: ...
- Out: ...

## Stories
- [ ] S-NNN — title

## Done when
Concrete criteria.
```

### Story: `docs/modernization/stories/S-NNN-<slug>.md`

```markdown
---
(frontmatter as above)
---

## Context
Why this story exists. One paragraph.

## Acceptance criteria
- ...

## Tasks
- [ ] ...

## Notes
Implementation gotchas, sacred cows, parity tests, assumptions.
```

## Quality bar

- Every story has ACs you can write a test against. "Looks right" is not acceptance.
- Every story names ≥ 1 ADR ref OR explicitly notes "no ADR — pure parity work".
- Every parity-sensitive story names a `parity_test` OR makes "write parity test" task 1.
- No L story without a `## Tasks` split. If unsplittable, flag for design work.
- Stories reference future stories by ID, not raw name (renames stay easy).
- ACs cite legacy code where parity matters — file paths, function names, line ranges, specific behaviors.
- Estimates calibrated — M stories have counts in `## Notes` to back them.
- Per ADR 0022 directive 2: ACs describe behavior, not schema-level business logic implementation.
- **`AskUserQuestion` count expected = 0 (or 1 for pre-existing artifacts).** > 1 is a flag the autonomy principle wasn't respected.

## When done

1. Generate `_ORDER.md`; sanity-check topological sort.
2. Print:
   - Epic count.
   - Story count by estimate (S/M/L).
   - **`AskUserQuestion` count** — expected 0-1.
   - **`## Assumptions made`** list — autonomous decisions worth operator review (decomposition picks, scope inferences, parity-test placement, order ties). Operator pushes back on what matters.
   - First 3 stories of `_ORDER.md`.
   - Any L stories that couldn't be split.
3. Tell operator: "Stories ready. Review assumptions. Start with S-001 (or your pick) via `/modernize-refine S-001` → `/modernize-implement S-001`."
