---
name: modernize-refine
description: Phase 5 — refine one story via specialist subagents. Conditional dispatch by story shape. Synthesises into the story file. Trigger: /modernize-refine S-NNN.
---

# Phase 5 — Story Refinement (just-in-time)

Take one story (`S-NNN`); surface the load-bearing decisions an implementer can't derive from code alone, and write them into the story file as tightly as possible.

Read [ADR 0022](../../../docs/modernization/adrs/0022-modernization-primary-directives.md). Per directive 1: refinement is *optional* when the story has no design forks. Skip if `## Tasks` + ACs already say enough to implement.

JIT only — never refine more than one story per invocation. Stale refinement is worse than no refinement.

## What "refined" means here

Refinement is a **decision document**, not a design document. It exists because the implementer needs to know things the code can't tell them on its own:

- Cross-story contracts (what S-NNN consumes from S-MMM; what it produces for S-OOO).
- Rip-out plans, deprecation flags, and other non-obvious lifecycle notes.
- Parity exclusions + the reason.
- Non-obvious decisions where a competent implementer would otherwise pick differently.
- Open questions that didn't get answered.

Things that **do not** belong in the story:

- File trees, package layouts, method signatures, DTO field lists — `ls`, `grep`, and the code itself document these.
- Test method names — the test files document these.
- Threat-model rows whose mitigations land in the code anyway.
- Latency budgets that aren't separately measured.
- Alternatives-considered enumerations — those belong in the PR description.

Soft target: design + edge cases + test plan + security + performance combined ≈ 150 lines. If you blow past it, ask whether a competent implementer would actually re-derive what you wrote — and cut.

## Preconditions

1. Single `S-NNN` arg.
2. Story file at top-level `stories/` (refuse if in `implemented/`).
3. Not `status: done` (else ask re-refine).
4. If `refined: true` — ask: re-refine (overwrite) or abort.

## Procedure

### Step 1 — Load + scope

Read in parallel: target story, ADRs in `adr_refs`, `00-seed.md`, `01-current-state.md`, `02-vision-and-constraints.md`, `_ORDER.md`, every `depends_on` story (two-step glob: top-level then `implemented/`).

Compute story-shape flags from title + ACs + body + cited legacy code + `adr_refs`:

- `has_security_signal` — story mentions `auth` / `authz` / `Keycloak` / `OIDC` / `@TenantId` / `@PreAuthorize` / `PII` / `OWASP` / `tenant` / `audit` / `RBAC` / `permission` / `role` / `password` / `principal`. Or cited legacy under `UserService` / `Auth*` / `Identity*` / `Login*`.
- `has_performance_signal` — mentions `index` / `query` / `latency` / `cache` / `@BatchSize` / `fetch` / `N+1` / `p95` / `p99` / `JOIN` / `hot path` / `throughput` / `pagination` / `bulk` / `streaming`. Or estimate is `L`. Or epic is performance-flagged.
- `has_library_signal` — `adr_refs` lists runtime-stack ADRs OR body references a specific library by name OR cites version pins.

Specialist dispatch:

| Specialist | Spawn when | Section |
|---|---|---|
| `requirements-engineer` | **always** | `## Edge cases & hidden requirements` |
| `solution-architect` | **always** | `## Design notes` |
| `qa-engineer` | **always** | `## Test plan` |
| `security-engineer` | `has_security_signal` | `## Security plan` |
| `performance-engineer` | `has_performance_signal` | `## Performance plan` |

Skipped specialists pre-fill section with `(N/A — <reason from story-shape>)`.

**Frontmatter override:** `refine_specialists:` in frontmatter forces a specialist set (e.g. to add security when auto-detect misses). Override wins.

### Step 1.5 — Context7 freshness (conditional)

Skip if `has_library_signal` false. Skip if `context7_last_checked` < 7 days old and library surface hasn't expanded since.

Otherwise: per library / framework / SDK touched (derive from `adr_refs` + ACs + legacy code), `mcp__context7__resolve-library-id` → `mcp__context7__query-docs`. Verify current version, peer-dep matrix, API recommendations / deprecations.

Pass 1-3-line "Library facts" block into each specialist's prompt (subagents have no Context7 access). Stamp `context7_last_checked: <ISO>` on frontmatter.

### Step 2 — Spawn applicable specialists in parallel

ONE message, multiple `Agent` calls. Each prompt must include:

- Absolute path to story file.
- Absolute paths to ADRs in `adr_refs`.
- `depends_on` IDs (so agent can read upstream refinements).
- Project context (FLS modernization, sacred cows, `@TenantId` multi-tenancy, [ADR 0022 directives](../../../docs/modernization/adrs/0022-modernization-primary-directives.md)).
- Library facts from Step 1.5 (or empty).
- Output format (each agent specifies; call it out).
- **Brevity rule:** "Decisions over enumeration. If a competent implementer would derive it from the code, tests, or ADRs, omit it. Target ≤ 30 lines per section." Restate this in every spawn prompt — it overrides the agent's default output template when they conflict.

### Step 3 — Synthesise (editorial)

Specialists produce outputs; you cut what the code will document anyway, then compose into the story. **The job is editorial: trim to decisions.** A specialist who returns a 60-line section gets cut to the 10-15 lines that carry weight; the rest is restated in code when the implementer touches it.

**Heuristic for what to keep:**

- Cross-story contracts (consumes / produces by ID).
- Non-obvious decisions + the why.
- Rip-out / deprecation / sunset markers.
- Parity exclusions (and why excluded).
- Open questions / forks (`## Open design questions`).

**Heuristic for what to cut:**

- Anything that reads like a file tree, package layout, or method-signature list.
- "Alternatives considered" — PR description.
- Test method names — the test files name themselves.
- Threat-model rows with already-pinned mitigations (the mitigation lands in code; the row is noise).
- "What stays / what's mocked" inventories longer than 5 lines — collapse to one sentence and let the rip-out checklist in code carry the rest.

**Conflict resolution:**
- Two specialists disagree → capture both in `## Open design questions` for operator input.
- Specialist output empty for genuinely-N/A category → preserve their `(N/A)` note.
- Output clearly broken → re-run that one with clarifying prompt.

**Per ADR 0022 directive 2:** when the architect proposes schema-level business logic (CHECK constraints encoding state machines / ranges / calculations, generated columns for domain math, triggers), the synthesised design notes must call it out as a *deviation requiring rationale* — not silently accept. Default position: business logic on aggregates.

### Step 4 — Write back

Append (or replace) inside `<!-- modernize-refine: start --> / end -->` delimiters, in order:

```markdown
<!-- modernize-refine: start -->

## Design notes
<solution-architect>

## Edge cases & hidden requirements
<requirements-engineer>

## Security plan
<security-engineer or N/A>

## Test plan
<qa-engineer>

## Performance plan
<performance-engineer or N/A>

## Open design questions
<only if conflicts surfaced — else omit entirely>

<!-- modernize-refine: end -->
```

Re-runs replace atomically; everything else preserved.

### Step 5 — Frontmatter

```yaml
refined: true
refined_at: <ISO date>
refined_specialists: [requirements, solution, qa, security, performance]  # only the ones that ran
context7_last_checked: <ISO date>  # only when Step 1.5 ran
```

`refined_specialists` reflects what *actually ran*. Skipped specialists' sections show `(N/A)`; don't list them.

If frontmatter pre-set `refine_specialists:` (override), preserve it verbatim.

### Step 6 — Report

- Story ID + title.
- One-line summary per specialist (the headline of each section).
- Whether `## Open design questions` was populated + count.
- Size delta (lines added).
- Next: `/modernize-implement S-NNN`.

## Quality bar

- One story per invocation.
- Context7 conditional + freshness-cached.
- Conditional specialist dispatch (skip rather than spawn-then-return-N/A).
- Frontmatter `refine_specialists:` overrides auto-detect.
- Specialists run in parallel (single message, multiple `Agent` calls).
- **Synthesis is editorial: trim to decisions.** If the code will document it, cut it from the story.
- Soft body target: design + edge + test + security + perf ≈ 150 lines combined. Blow past it only when the story is genuinely that thorny — and say why in the report.
- Replace, don't append, on re-run.
- Frontmatter reflects reality (`refined_specialists` = who ran).
- Open design questions surface real conflicts, not "things to think about."
- Per ADR 0022 directive 2: schema-level business logic in design notes = call out as deviation requiring rationale.

## Not in scope

AC edits (`/modernize-decompose`'s job — surface conflict in `## Open design questions` instead). Code generation (`/modernize-implement`). Epic refinement. `depends_on` validation (`/modernize-implement`'s precondition). Commits (operator's call).
