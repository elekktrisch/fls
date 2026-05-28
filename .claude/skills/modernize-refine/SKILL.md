---
name: modernize-refine
description: Phase 5 — refine one story via specialist subagents, then open a story branch + GH issue + draft PR so the refinement diff is reviewable on GitHub before implement runs. Trigger: /modernize-refine S-NNN.
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

**Refinement does not speculate.** Anything the legacy code + ADRs don't pin, the operator gets grilled on in Step 3.5 via the grill-me skill. `## Open design questions` is the residual section for items the operator *explicitly* defers post-grill — empty by default, not a dumping ground.

**Legacy table migration tracking is global, not per-story.** Refine updates [`docs/modernization/legacy-migration-plan.md`](../../../docs/modernization/legacy-migration-plan.md) (single source of truth, exhaustive over the legacy DB) with the rows this story owns. The story body itself stays silent — readers go to the global plan. See Step 4.5.

Things that **do not** belong in the story:

- File trees, package layouts, method signatures, DTO field lists — `ls`, `grep`, and the code itself document these.
- Test method names — the test files document these.
- Threat-model rows whose mitigations land in the code anyway.
- Latency budgets that aren't separately measured.
- Alternatives-considered enumerations — those belong in the PR description.

Soft target: design + edge cases + test plan + security + performance combined ≈ 150 lines. If you blow past it, ask whether a competent implementer would actually re-derive what you wrote — and cut.

## Base branch resolution

The story's base branch — the branch story branches are cut from + PRs target + finalize merges into — is resolved as:

- `integration_base: <branch>` in the story frontmatter, if present.
- else the repo's default branch (typically `main`).

This is the `<base>` placeholder used throughout the rest of this skill. Operator workflow: create `integration/<name>` off main, set `integration_base: integration/<name>` on each story in the cluster, run refine / implement / finalize as normal — stories branch off integration, PRs target integration. Operator merges integration → main once the cluster is tested together.

## Preconditions

1. Single `S-NNN` arg.
2. Story file at top-level `stories/` (refuse if in `implemented/`).
3. Not `status: done` (else ask re-refine).
4. If `refined: true` — ask: re-refine (overwrite) or abort. Re-refine on an existing `story/S-NNN-*` branch adds another commit; re-refine from `<base>` switches into the existing branch first.
5. Working tree clean — or the only dirty file is the target story file (refine is about to write to it anyway).
6. Current branch is either `<base>` (resolved above) OR already `story/S-NNN-<slug>` matching the resolved story ID.

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

**Solution-architect prompt MUST additionally include:**

- A mandate to produce a separate output block titled `## Legacy table migration deltas (for global plan)`. One row per legacy DB table this story consumes / replaces / drops, in the same shape used by [`docs/modernization/legacy-migration-plan.md`](../../../docs/modernization/legacy-migration-plan.md). These rows are NOT pasted into the story body — refine's Step 4.5 reconciles them into the global plan. Cite the legacy table by the name it has in `flsserver/database/FLS/Updates/DBUpdate_v*.sql` (final-state name after all DBUpdates). Allowed semantics: `port-as-rows` · `port-as-schema-only` · `drop` · `fold-into-<table>` · `split-into-<tables>` · `replaced-by-<external-system>`. Empty block is allowed ONLY when the story genuinely has zero schema impact (pure frontend, devops, doc-only) — say so explicitly with a one-line reason.

### Step 3 — First-pass synthesise (editorial)

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

### Step 3.5 — Grill the operator on unresolved forks

Refine is **not speculative**. Anything the legacy doesn't pin AND the specialists can't unambiguously derive is escalated to the operator before the story body locks in. Implementation: invoke the **grill-me skill** with the unresolved fork as the topic — it drives a focused multi-turn interrogation and returns a decision.

**Identify forks worth grilling:**

- Two specialists disagree on the same decision.
- An architect recommendation deviates from observable legacy behavior (port-of-feature stories) without an explicit cited reason.
- A schema-level deviation per ADR 0022 directive 2 with no rationale in the design notes.
- A "Recommendation: X" line in any specialist's output where the legacy code doesn't observably support X and no ADR pins it.
- A migration semantic the architect picked (port-as-rows vs drop vs split — see Step 4's Legacy table migration section) where the legacy data shape doesn't dictate the answer.

**Skip the grill when:**

- The fork is fully derivable from grep'ping legacy code (refine should resolve it itself, not bug the operator).
- The fork is already explicitly answered in an ADR or upstream story refinement.
- The story is greenfield (no legacy to be unclear about) AND the architect's recommendation is internally consistent.

**Invoke pattern** — for each unresolved fork:

```
Skill("grill-me", "S-NNN refine — <fork topic in one line>. Context:
<legacy citation or specialist disagreement summary>. Need: pick one of
[option A | option B | option C] or surface a new option. Bake the
resolution back into the story's design notes / migration plan.")
```

Bake each grill outcome into the relevant section of the synthesis (Design / Migration / Edge / Security / Test). Only what survives grilling and *still* needs operator-async-decision lands in `## Open design questions`. Default is that section is EMPTY post-grill.

If you skip the grill, say so in the report — "no unresolved forks; legacy + ADRs pinned every decision."

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
<only if forks SURVIVED the Step 3.5 grill — else omit entirely>

<!-- modernize-refine: end -->
```

Re-runs replace atomically; everything else preserved. Legacy table migration mappings go to the global plan (Step 4.5), not into the story body.

### Step 4.5 — Update the global legacy-migration plan

The single source of truth for "every legacy DB table → where it lands in the new stack" is [`docs/modernization/legacy-migration-plan.md`](../../../docs/modernization/legacy-migration-plan.md). Every story that touches DB schema MUST update the rows it owns. The story body itself stays silent — readers go to the global plan.

**For each row the solution-architect emitted in its `## Legacy table migration deltas (for global plan)` block:**

1. Locate the row in the plan (by legacy table name). Every legacy table is pre-seeded — if the row doesn't exist, that's a bug (file a follow-up to fix the plan, then proceed).
2. Update its columns: `Destination`, `Semantics`, `Owned by` (set to `S-NNN`), `Notes`.
3. If two stories both legitimately touch the same table (e.g. S-NNN ports the data, S-MMM splits a column out later), `Owned by` is the **most recent** owner; previous owner is captured in `Notes` as `also touched by S-XXX (<what it did>)`.

**Conflict handling:** if the architect proposes a semantics that contradicts an existing row's `Semantics` (e.g. an earlier story said `port-as-rows`, this one says `drop`), surface to the operator via grill-me in Step 3.5 — don't silently overwrite. The plan is contract; conflicts mean a design pivot.

**Empty deltas:** if the architect's block was empty (story has zero schema impact), Step 4.5 is a no-op. Note this in the report.

**Plan-as-contract:** the plan is committed in the same Step 6 commit as the story refinement, so the diff lands in the same PR. Reviewers can see both the story decisions AND their schema fallout in one place.

### Step 5 — Frontmatter

```yaml
refined: true
refined_at: <ISO date>
refined_specialists: [requirements, solution, qa, security, performance]  # only the ones that ran
context7_last_checked: <ISO date>  # only when Step 1.5 ran
github_issue: N                    # stamped in Step 6 when created
github_pr: M                       # stamped in Step 6 when created
```

`refined_specialists` reflects what *actually ran*. Skipped specialists' sections show `(N/A)`; don't list them.

If frontmatter pre-set `refine_specialists:` (override), preserve it verbatim.

### Step 6 — Branch + GH issue + commit + draft PR

The refinement diff should be reviewable on GitHub before implement runs, so refine owns the GH bootstrap (issue + branch + draft PR). Implement Step 2 then resumes on the existing branch instead of creating one.

**Branch.** If not already on `story/S-NNN-<slug>`: `git checkout -b story/S-NNN-<slug>` off the current branch (precondition #6 guarantees that's `<base>` or the matching story branch).

**GH issue.** If `gh auth status` OK + remote exists + no `github_issue:` already stamped: `gh issue create` with title `S-NNN: <story title>`, body = `## Context` verbatim + AC checklist from frontmatter + back-link to the MD path. Capture issue number; stamp `github_issue: N`. If already stamped, verify still open via `gh issue view`.

**Commit.** Single commit covering frontmatter + story body delta + the `legacy-migration-plan.md` diff from Step 4.5 (+ issue stamp if just minted). Subject: `#N: refine` (or `S-NNN: refine` fallback when no issue exists). Re-refine on an existing branch: `#N: re-refine — <one-line headline of what changed>`.

**Push + draft PR.** `git push -u origin story/S-NNN-<slug>`. Then `gh pr create --draft --base <base> --head story/S-NNN-<slug>` if no PR exists yet. PR body:

```
Closes #N

Refinement diff for S-NNN. Decisions are in the story body under
`<!-- modernize-refine: start --> / end -->`.

Specialist headlines:
- <one-line per specialist that ran>

Open design questions: <count, or "none">

Implementation starts when `/modernize-implement S-NNN` runs on this branch.
```

Stamp `github_pr: M` on frontmatter and commit the stamp as a separate `#N: stamp PR` follow-up (so the first push isn't held until after PR creation — race-free).

**Fallback — no `gh` / no remote / `gh` call fails.** Branch + commit still happen (local-only is useful). Skip push, issue, PR. Report the fallback explicitly so the operator knows the PR didn't open. Do NOT bail — refinement value already landed in the story body.

### Step 7 — Report

- Story ID + title.
- One-line summary per specialist (the headline of each section).
- Whether `## Open design questions` was populated + count.
- Size delta (lines added).
- Branch: `story/S-NNN-<slug>` (current).
- Issue: `#N <url>` (or `(fallback — no GH issue)`).
- PR: `#M <url>` DRAFT (or `(fallback — no PR; local-only)`).
- Next: `/modernize-implement S-NNN` (run on this branch).

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
- **No speculation.** Step 3.5 grills the operator on every fork the legacy + ADRs don't pin. `## Open design questions` is EMPTY by default — only survives when the operator explicitly defers.
- **Legacy table migration tracking is global, not per-story.** Refine updates `docs/modernization/legacy-migration-plan.md` (Step 4.5) with the rows this story owns. Story body stays silent. The plan is exhaustive over the legacy DB; every table has a row from day one.
- Per ADR 0022 directive 2: schema-level business logic in design notes = call out as deviation requiring rationale.
- Refinement opens the story branch + GH issue + draft PR (Step 6). Implement resumes on the existing branch.

## Not in scope

AC edits (`/modernize-decompose`'s job — surface conflict in `## Open design questions` instead). Code generation (`/modernize-implement`). Status flip to `in_progress` (`/modernize-implement` owns). Epic refinement. `depends_on` validation (`/modernize-implement`'s precondition).
