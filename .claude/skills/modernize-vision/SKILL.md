---
name: modernize-vision
description: Phase 2 of the modernization workflow. Elicits target outcomes, non-functional requirements, and non-negotiable constraints from the user, then writes docs/modernization/02-vision-and-constraints.md. Trigger when the user invokes /modernize-vision, after phase 1 (modernize-discover) has produced 01-current-state.md.
---

# Phase 2 — Vision & Constraints

You are running phase 2 of a four-phase modernization workflow. Your job is to elicit and record what "success" looks like, so phase 3 (ADRs) can evaluate options against criteria and phase 4 (decomposition) can prioritize stories by value.

## Preconditions

1. `docs/modernization/00-seed.md` exists. Read it. Strategic anchors and sacred cows from the seed are **already decided** — do not ask the user to confirm them.
2. `docs/modernization/01-current-state.md` exists. Read it, especially the "Open questions for phase 2" section — those are your starting elicitation targets.
3. If `02-vision-and-constraints.md` already exists, ask the user whether to (a) replace it, (b) extend it (you keep accepted content, add new sections), or (c) abort.

## How to elicit

Use `AskUserQuestion` for structured questions. Group them into batches by theme — never more than four questions per batch. Order batches from most load-bearing to least.

Suggested batch order:

1. **Outcomes** — what changes for the user/business after the rewrite is done. Make them measurable where possible (e.g., "page load < 1s p95", "new feature dev time halved", "no Saturday outages").
2. **Non-functional requirements** — performance, availability, security posture, accessibility, observability, compliance.
3. **Operational constraints** — hosting environment, deployment cadence, budget, team size and skill mix, supportable runtimes (e.g., "must run on Windows", "must be Postgres-compatible", "must integrate with our existing K8s cluster").
4. **Cutover & rollback** — acceptable downtime window for the hard cutover, what "rollback" means if cutover fails, how you'll validate parity before cutting.
5. **Scope qualifications** — features explicitly in scope for the rewrite, features explicitly out, anything to deprecate during the rewrite.

For each question:
- Offer concrete options grounded in the discovery doc. If the system is currently a Windows monolith, "must run on Windows" is a real option, not a hypothetical.
- Mark a recommendation when the answer is well-supported by the discovery findings. State the reasoning in the description.
- Never ask the user to confirm a strategic anchor from the seed — those are fixed.

When the user gives an answer that contradicts a seed anchor, push back once. If they confirm, update the seed (not the vision doc) with their override.

## What to write

`docs/modernization/02-vision-and-constraints.md`:

### 1. Target outcomes
The handful of measurable changes that define success. One line each, with a measurement method where possible.

### 2. Non-functional requirements
A table: NFR | target | measurement | rationale. Cover at minimum: performance, availability, security, observability. Add accessibility, i18n, compliance only if elicited.

### 3. Hard constraints
The things the new system **must** satisfy. One line each, with the source of the constraint (user input, regulatory, business, technical).

### 4. Soft preferences
Nice-to-haves that should bias ADRs but aren't go/no-go.

### 5. Cutover plan shape
Acceptable downtime window. Parity-validation strategy. Rollback definition. Don't design the cutover here — just record the constraints it must respect.

### 6. Decision criteria for phase 3
Synthesized from the above: an ordered list of criteria ADRs will be evaluated against (e.g., "minimizes operational cost", "maximizes team familiarity", "supports row-level multi-tenancy structurally"). This is the input contract for phase 3.

## Quality bar

- Every NFR has a number or a falsifiable condition. "Fast" is not an NFR; "p95 page load < 1s on a 4G connection" is.
- Every hard constraint cites its source. "The system must support German and French" → "(business: customer base is Switzerland)".
- The decision criteria list is ranked, not flat. Phase 3 will use the ranking to break ties.
- No implementation choices in this document. "Postgres" is a phase-3 ADR, not a phase-2 constraint — unless the user explicitly said it is, in which case record it as a constraint and skip the corresponding ADR in phase 3.

## When you are done

1. Read the file back. Sanity-check that each section is populated and the criteria list is ordered.
2. Print a 5-line summary: outcome count, NFR count, hard-constraint count, top-3 decision criteria, suggested next command.
3. Do **not** start phase 3.
