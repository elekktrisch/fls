---
name: modernize-decompose
description: Phase 4 of the modernization workflow. Decomposes the rewrite into epics, stories, and tasks based on the current-state feature inventory, vision, and ADRs. Writes docs/modernization/epics/E-NN-*.md and stories/S-NN-*.md with dependency metadata. Trigger when the user invokes /modernize-decompose, after phases 1, 2, and 3 are complete.
---

# Phase 4 — Epic & Story Decomposition

You are running phase 4 of a four-phase modernization workflow. Your job is to convert the feature inventory + ADRs into an actionable backlog: epics, stories, and tasks, with explicit dependencies and a recommended execution order.

## Preconditions

1. `01-current-state.md`, `02-vision-and-constraints.md`, and `adrs/*.md` all exist. Read them.
2. If `epics/` or `stories/` already contain files, ask the user whether to (a) refresh from inputs (regenerate from scratch), (b) merge (preserve existing files, add new ones to fill gaps), or (c) abort.

## How to decompose

Top-down, three levels:

### Epics
A handful (5–15) of large workstreams. Each epic corresponds to either:
- A feature-area cluster from the inventory (e.g., "Flights & state machine", "Accounting rules engine", "Identity & multi-tenancy"), or
- A cross-cutting concern surfaced by ADRs (e.g., "Migration tooling & parity validation", "Observability foundation", "CI/CD pipeline").

### Stories
Each epic decomposes into stories. A story is **independently shippable** — it has clear acceptance criteria, can be merged on its own, and either lands user-visible value or unblocks the next story.

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

## When you are done

1. Generate `_ORDER.md` and sanity-check the topological sort.
2. Print:
   - Epic count.
   - Story count broken down by estimate (S/M/L).
   - The first 3 stories of `_ORDER.md` — the user's immediate next moves.
   - Any L stories that couldn't be split (flagged for design work).
3. Tell the user: "Stories are ready. To start implementing, open story S-001 (or whichever you want to begin with). If you want GitHub Issues, write that sync script as its own story."
