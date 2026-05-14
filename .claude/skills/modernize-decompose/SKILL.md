---
name: modernize-decompose
description: Phase 4 of the modernization workflow. Decomposes the rewrite into epics, stories, and tasks based on the current-state feature inventory, vision, and ADRs. Writes docs/modernization/epics/E-NN-*.md and stories/S-NN-*.md with dependency metadata. Trigger when the user invokes /modernize-decompose, after phases 1, 2, and 3 are complete.
---

# Phase 4 — Epic & Story Decomposition

You are running phase 4 of a four-phase modernization workflow. Your job is to convert the feature inventory + ADRs into an actionable backlog: epics, stories, and tasks, with explicit dependencies and a recommended execution order.

## Preconditions

1. `01-current-state.md`, `02-vision-and-constraints.md`, and `adrs/*.md` all exist. Read them.
2. If `epics/` or `stories/` already contain files, ask the user whether to (a) refresh from inputs (regenerate from scratch), (b) merge (preserve existing files, add new ones to fill gaps), or (c) abort.

## Core principle — maximum autonomy

This skill produces a backlog. Write it. **`AskUserQuestion` is a last resort, not a default.** Story writing should be fully autonomous: legacy code is the primary source of story content, the current-state doc and ADRs provide the framing, and any uncertainty becomes an explicit assumption recorded in the story — not a blocking question to the user.

**The user reviews and adjusts after the artifacts exist**, not during their construction. A backlog with assumptions the user can override beats a half-written backlog blocked on a question.

### What "autonomous" means in practice

- **Read the legacy code** for each epic's feature area — controllers, services, jobs, templates, schema, tests. Skim is not enough; you need depth to write acceptance criteria that reference *specific* legacy behaviors (state transitions, time gates, validation rules, edge cases), not paraphrased summaries.
- **Decide and document.** When two reasonable decompositions exist (bundle vs. split, story order, parity-test placement), pick one, note the alternative in the story's `## Notes` section as "Assumption: chose X over Y because Z; revisit if Y matters more", and move on.
- **Default to inclusion.** If a feature is in the inventory and the seed doesn't explicitly defer or drop it, write a story for it. Do not ask "should we port X?" — write the story; let the user delete it if they want to drop it.
- **Default to ADR conformance.** If an ADR's follow-ups call out a story, write it. Do not ask "should we do this follow-up?" — the ADR already said yes.
- **Default to parity tests for sacred-cow code paths.** Write the parity test as task 1; if the user wants to skip it for a given story they will say so.

### When `AskUserQuestion` is justified (rare)

Reserve for cases where the answer **fundamentally changes the artifact structure** and is **not derivable from any combination of seed + vision + ADRs + current-state + legacy code**:

- A pre-existing `epics/` or `stories/` folder must be resolved (refresh / merge / abort) — this is genuinely blocking because it changes whether you write at all.
- A feature in the inventory is in tension with a vision constraint and the resolution would change the story shape significantly.
- A required input file is missing or corrupt.

That's the list. Anything else — pick an answer, record the assumption, write the artifact, surface assumptions in the final summary.

### What never to ask

- "Should we have a story for X?" — write it.
- "What should the acceptance criteria for X be?" — read the code, write them.
- "How big is this story?" — measure (entities, endpoints, templates touched), pick S/M/L.
- "Which ADRs apply?" — derivable from the story's domain.
- "What order should the epics go in?" — write `_ORDER.md` from dependency + risk + sacred-cow weight; the user re-orders if they disagree.
- "Should we bundle X and Y into one story or split them?" — pick one and note the alternative.
- "Should we write a parity test for X?" — yes if it touches a sacred cow; no otherwise. Don't ask.

### Reporting back

When done, the summary must include:
- A `## Assumptions made` section listing the decisions the skill resolved autonomously and could be revisited. The user reads this and pushes back on the ones that matter.
- The `AskUserQuestion` count — should be zero or one (the "existing artifacts?" precondition check) in a typical run.

## How to decompose

Top-down, three levels.

### Step 0 — Pre-read the discovery doc's §8 "Findings pre-answered"
That section is the discovery skill's contract with this phase. Most of the feed for story acceptance criteria and ADR cross-references is there. Pull it forward; do not re-derive.

### Epics
A handful (5–15) of large workstreams. Each epic corresponds to either:
- A feature-area cluster from the inventory (e.g., "Flights & state machine", "Accounting rules engine", "Identity & multi-tenancy"), or
- A cross-cutting concern surfaced by ADRs (e.g., "Migration tooling & parity validation", "Observability foundation", "CI/CD pipeline").

For each epic, **before decomposing into stories**:
1. Read the legacy code for that feature area — controllers, services, scheduled jobs, templates, schema, tests. List the file paths in the epic's body as "Legacy code touched."
2. List the ADRs that constrain this epic (from `adrs/*.md` follow-ups).
3. Measure: count the controllers, services, entities, e2e tests, lines (rough order of magnitude). These numbers feed story estimates.

### Stories
Each epic decomposes into stories. A story is **independently shippable** — it has clear acceptance criteria, can be merged on its own, and either lands user-visible value or unblocks the next story.

**Each story's acceptance criteria must be grounded in code, not paraphrase.** Examples:
- ❌ "Flight state transitions work like in the old system."
- ✅ "POST /api/v1/flights with `processStateId = Locked` and `lockedOn` set to 1 day in the past returns 400 (mirrors `FlightService.cs:1380-1440` time-gate check)."

**Estimates are calibrated against code measurements:**
- S (≤1 day): single endpoint or small entity, no integration, < 200 lines of legacy code touched.
- M (2–5 days): cluster of endpoints, one entity + DTOs, modest integration, 200–1000 lines touched.
- L (>5 days): cross-entity feature, integration with external system, or rules-engine-shaped logic. Split if possible.

**Parity-sensitive stories** (anything touching sacred cows per seed) must name a concrete `parity_test` — the e2e file + the specific test name, or "write parity test as task 1." Bare "parity test exists" is not acceptable.

For each story, frontmatter must include:

```yaml
---
id: S-NNN
title: <short title>
epic: E-NN
status: todo  # todo | in_progress | done | blocked
depends_on: [S-NNN, S-NNN]
acceptance:
  - <one-line condition>
  - <one-line condition>
estimate: S | M | L         # S = ≤1 day, M = 2-5 days, L = >5 days; split L if you can
adr_refs: [0001, 0002]
parity_test: <name of the e2e or unit test that proves equivalence with the old system, or "none">
---
```

### Tasks
Stories large enough to have multiple sub-deliverables get a `## Tasks` section in the story body — a checklist, not separate files.

## Story ordering

The output must include an explicit execution order, surfaced as a `docs/modernization/stories/_ORDER.md` file:

1. Start with **foundational** stories: bootstrap repos, CI, baseline auth, baseline DB connectivity, baseline frontend shell. Anything that everything else depends on.
2. Then **vertical slices** that prove the architecture end-to-end on a low-risk domain.
3. Then **feature parity**, working through the inventory in order of (a) external-integration risk, (b) user-visible criticality. Save the rules engine and accounting pipeline for last unless the user says otherwise — they have the most subtle behavior to preserve.
4. Finally **cutover prep**: data migration, parity-validation harness, runbooks.

Sort by `depends_on` topologically. Where ties exist, prefer stories with more downstream dependents (do the unblockers first).

## Epic file format

`docs/modernization/epics/E-NN-<slug>.md`:

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
- [ ] S-NNN — title

## Done when
Concrete criteria that distinguish "this epic is finished" from "we're still on it".
```

## Story file format

`docs/modernization/stories/S-NNN-<slug>.md`:

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
- [ ] ...

## Notes
Implementation gotchas, references to seed sacred cows that apply here, parity tests to mirror, etc.
```

## Quality bar

- Every story has acceptance criteria you could write a test against. "Looks right" is not acceptance.
- Every story names at least one ADR reference or explicitly notes "no ADR — pure parity work".
- Every parity-sensitive story names a `parity_test`. If no test exists, an early task in the story is "write the parity test" — do not ship parity work blind.
- No story is L without a Tasks section that splits it. If you can't split it, that's a signal it needs design work, not execution — flag it for the user.
- Stories don't reference future stories by raw name in prose, only by ID. Renames stay easy.
- **Acceptance criteria cite legacy code where parity matters** — file paths, function names, line ranges, specific behaviors. Paraphrased "works like the old system" is rejected.
- **Estimates are calibrated** — a story marked M has measurable evidence (counts of endpoints / entities / tests touched) in its Notes section.
- **No `AskUserQuestion` calls for content that can be derived from the inventory + ADRs + legacy code.** If the question is "what should the acceptance criteria be?", the answer is in the code — read it. Reserve user questions for scope-cut, prioritization, and judgment calls.

## When you are done

1. Generate `_ORDER.md` and sanity-check the topological sort.
2. Print:
   - Epic count.
   - Story count broken down by estimate (S/M/L).
   - **`AskUserQuestion` count** — expected: 0 (or 1 if a pre-existing `epics/`/`stories/` folder forced a refresh/merge/abort question). >1 is a flag that the autonomy principle was not respected.
   - **`## Assumptions made`** — the autonomous decisions worth surfacing for user review (decomposition picks, scope inferences, parity-test placement, order ties broken by judgment). The user reads this list and pushes back on what matters.
   - The first 3 stories of `_ORDER.md` — the user's immediate next moves.
   - Any L stories that couldn't be split (flagged for design work).
3. Tell the user: "Stories are ready. Review the assumptions list above; everything else is set. To start implementing, open story S-001 (or whichever you want to begin with). If you want GitHub Issues, write that sync script as its own story."
