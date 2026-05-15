---
name: modernize-adrs
description: Phase 3 — draft an ADR per major rewrite decision (options against vision criteria), ask user to pick. Writes docs/modernization/adrs/. Trigger: /modernize-adrs (after phases 1-2).
---

# Phase 3 — Architecture Decision Records

You are running phase 3 of a four-phase modernization workflow. Your job is to produce a small set of ADRs that lock in the target architecture before story decomposition begins.

## Preconditions

1. `docs/modernization/00-seed.md`, `01-current-state.md`, and `02-vision-and-constraints.md` all exist. Read all three.
2. If `docs/modernization/adrs/` already contains files, read them. Existing ADRs are inputs, not to be regenerated. Ask the user which decisions still need ADRs.

## Which decisions warrant an ADR

Default candidate set (skip any whose answer is already pinned by a hard constraint in the vision doc):

1. Backend language + framework.
2. Frontend framework + build tool.
3. State management / data fetching on the frontend.
4. Database engine.
5. Schema migration tooling.
6. Auth scheme (token type, lifetime, refresh strategy, SSO/OIDC integration).
7. Multi-tenancy enforcement mechanism (since the seed mandates structural enforcement).
8. Background-job mechanism.
9. Hosting + deployment target.
10. Observability (logs, metrics, traces).
11. API shape (REST / GraphQL / RPC).
12. Internationalization mechanism.
13. Reporting / file-export library (if the current one has license issues).
14. Email-sending infrastructure.
15. Inter-service communication (only if the architecture is going multi-service).

Confirm the list with the user before drafting. They may add or strike items. Number them in the order they should be decided — earlier ADRs constrain later ones.

## How to draft each ADR

For each ADR, do **not** ask the user immediately. Draft it first, then present options and ask.

Process per ADR:

1. **Identify 2–4 viable options.** Use the discovery doc's stack details + the vision's decision criteria to narrow. Reject options that violate hard constraints up front (don't list them as options just to compare).
2. **For each option, list:** capabilities, fit to decision criteria (cite ranking from vision), migration cost from current stack, ecosystem risks, escape hatches (can you swap it later without rewriting again?).
3. **State your recommendation** with a one-paragraph rationale that names which decision criteria drove it.
4. **Ask the user via `AskUserQuestion`** with options grounded in your draft. Their answer is binding.
5. **Write the ADR file** using the format below, with the chosen option marked Accepted and the others marked Rejected with a one-line "why not".

## ADR file format

Filename: `docs/modernization/adrs/NNNN-<kebab-slug>.md` where NNNN is zero-padded to 4 digits.

```markdown
# NNNN — <Title>

- **Status:** Accepted | Superseded by NNNN
- **Date:** YYYY-MM-DD
- **Decision criteria** (from vision): <ordered list, copied or cited>

## Context

What problem are we solving and why now. Two paragraphs max.

## Options considered

### Option A — <name>
- Capabilities:
- Fit to criteria:
- Migration cost:
- Ecosystem risk:
- Escape hatch:

### Option B — <name>
(same shape)

## Decision

Chosen: **<option>**. One paragraph rationale referencing the top criteria that drove it.

## Consequences

- Positive:
- Negative:
- Follow-ups (other ADRs or stories implied by this choice):
```

## Quality bar

- An ADR with one option is not an ADR — it is a constraint and belongs in the vision doc. If you find yourself drafting one, move it.
- The "Consequences → Follow-ups" section is what phase 4 will mine for epics. Make it concrete.
- Do **not** generate code, scaffolding, or examples inside ADRs. They are decisions, not how-tos.
- One ADR per decision. Bundling ("we picked stack X, including framework, ORM, and auth") guarantees future ADRs will supersede half of it.

## When you are done

1. List all ADRs in order with their decisions.
2. Identify cross-ADR contradictions if any. Resolve before continuing.
3. Print a 5-line summary: ADR count, decisions accepted, decisions still open if any, suggested next command.
4. Do **not** start phase 4.
