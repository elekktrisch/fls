---
name: modernize-refine
description: Phase 5 — refine one story via specialist subagents. Conditional dispatch by story shape. Synthesises into the story file. Trigger: /modernize-refine S-NNN.
---

# Phase 5 — Story Refinement (just-in-time)

Take one story (`S-NNN`); turn its draft ACs into implementation-ready spec by spawning 3-5 specialists in parallel + synthesising back into the story file.

Read [ADR 0022](../../../docs/modernization/adrs/0022-modernization-primary-directives.md). Per directive 1: refinement is *optional* when the story has no design forks. Skip if `## Tasks` + ACs already say enough to implement.

JIT only — never refine more than one story per invocation. Stale refinement is worse than no refinement.

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

### Step 3 — Synthesise

Specialists produce outputs; you compose into the story. Don't re-decide.

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
- Synthesis is mechanical, not editorial.
- Replace, don't append, on re-run.
- Frontmatter reflects reality (`refined_specialists` = who ran).
- Open design questions surface real conflicts, not "things to think about."
- Per ADR 0022 directive 2: schema-level business logic in design notes = call out as deviation requiring rationale.

## Not in scope

AC edits (`/modernize-decompose`'s job — surface conflict in `## Open design questions` instead). Code generation (`/modernize-implement`). Epic refinement. `depends_on` validation (`/modernize-implement`'s precondition). Commits (operator's call).
