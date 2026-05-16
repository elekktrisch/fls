---
name: modernize-refine
description: Phase 5 — refine one story via specialist subagents. Conditional dispatch by story shape — requirements/solution/qa always run; security + performance spawn only when the story has the signal. Synthesizes into the story file. Trigger: /modernize-refine S-NNN.
---

# Phase 5 — Story Refinement (just-in-time)

You are running phase 5 of the modernization workflow. Your job is to take **one** story (S-NNN) at a time — the one the user is about to start — and turn its draft acceptance criteria + tasks into an implementation-ready spec by spawning five specialist subagents in parallel and synthesizing their output back into the story file.

Refinement is **just-in-time, not batch**: never refine more than one story per invocation. The user invokes the skill again for the next story when they're ready. Stale refinement is worse than no refinement — most of the project's 122 stories will be touched only once.

## Preconditions

1. The argument is a single story ID: `S-NNN`. If the user passed something else, ask for the ID.
2. The story file exists at `docs/modernization/stories/S-NNN-*.md` (top-level). If not found there, also check `docs/modernization/stories/implemented/S-NNN-*.md`:
   - If found in `implemented/`: refuse with "Story S-NNN is already finalized (in stories/implemented/). Refining a shipped story would produce a spec disconnected from its already-merged code. If you genuinely need to re-open it, copy the file back to stories/ first."
   - If not found in either location: bail.
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
- The **upstream stories** named in `depends_on:`. These typically live in `docs/modernization/stories/implemented/` (most foundational stories have already shipped by the time a later story is refined). Resolve each via a two-step glob: `docs/modernization/stories/S-NNN-*.md` first, then `docs/modernization/stories/implemented/S-NNN-*.md`. Read whichever you find — implemented stories are reference-only but remain authoritative for their refinement / design decisions.

You should *not* read every story or every ADR — only the ones this story depends on or references. Keep context focused.

**Story-shape detection (primary efficiency lever).** While loading, compute these flags from the story's title + acceptance criteria + body + cited legacy code + `adr_refs`. They determine which specialists Step 2 spawns — specialists whose dimension doesn't apply to the story are skipped entirely rather than spawned-then-returning-N/A. Cuts ~20-40% of refine tokens on typical stories.

- `has_security_signal` — story mentions any of: `auth`, `authz`, `Keycloak`, `OIDC`, `@TenantId`, `@PreAuthorize`, `PII`, `OWASP`, `tenant`, `audit`, `RBAC`, `permission`, `role`, `password`, `keycloak_sub`, `principal`. Or cited legacy code is under `flsserver/.../UserService` / `Auth*` / `Identity*` / `Login*`.
- `has_performance_signal` — story mentions any of: `index`, `query`, `latency`, `cache`, `caching`, `@BatchSize`, `fetch`, `N+1`, `p95`, `p99`, `JOIN`, `hot path`, `throughput`, `pagination`, `bulk`, `streaming`. Or estimate is `L`. Or epic is performance-flagged (e.g. `E-09` perf-baseline).
- `has_library_signal` — story's `adr_refs` lists ADRs about runtime stacks (0001 Spring Boot, 0002 Postgres, 0003 Flyway, 0004 Angular, 0005 API shape, 0006 NgRx, 0007 Keycloak, etc.), OR the story body references a specific library / framework / SDK by name, OR the body cites version pins.

The specialist dispatch table (applied in Step 2):

| Specialist | Spawn when | Section name |
|---|---|---|
| `requirements-engineer` | **always** | `## Edge cases & hidden requirements` |
| `solution-architect` | **always** | `## Design notes` |
| `qa-engineer` | **always** | `## Test plan` |
| `security-engineer` | `has_security_signal` | `## Security plan` |
| `performance-engineer` | `has_performance_signal` | `## Performance plan` |

For each not-spawned specialist, pre-fill its section in Step 4's template with `(N/A — <reason from story-shape>)` instead of spawning the agent. Examples:
- Security skipped: `## Security plan` → `(N/A — no security signal in story scope; no authz, tenant, PII, or audit surface. Re-spec if S-NNN later acquires one.)`
- Performance skipped: `## Performance plan` → `(N/A — no performance signal in story scope; no queries, indexes, caching, or hot-path concerns. Re-spec if S-NNN later acquires one.)`

**Frontmatter override.** A story may pre-set `refine_specialists:` in its frontmatter to force a specialist set (e.g. `refine_specialists: [requirements, architect, qa, security]` to force security even when the auto-detect misses). When the frontmatter is present, it wins — auto-detect is the default fallback, the operator is the override.

### Step 1.5 — Context7 freshness pass (conditional)

**Skip this step entirely if `has_library_signal` is false** (the flag from Step 1). Stories with no library / framework / SDK reference don't benefit from a Context7 pass — examples: pure-doc stories, decompose / inventory work, story-rename refactors.

**Optional caching.** A story may carry `context7_last_checked: <ISO date>` in frontmatter (stamped by a prior pass — refine, implement, or review). If within the last 7 days AND the story's referenced library surface hasn't expanded since then, skip and trust the prior pass. Otherwise run + restamp.

**When the pass DOES run:** for every library / framework / SDK / API the story is likely to touch (Angular, Spring Boot, Tailwind, NgRx Signals, @angular-eslint, Flyway, Testcontainers, Playwright, Keycloak, etc. — derive from `adr_refs` + acceptance criteria + the legacy code being replaced), fetch the **current** API surface and version status via Context7 **before** spawning specialists.

Workflow per library: `mcp__context7__resolve-library-id` → pick best match → `mcp__context7__query-docs` for the specific question (latest stable version, peer-dep matrix, whether an API is still recommended or has been superseded).

Pass the synthesized facts (1-3 lines per library — current major, key API names, deprecations) into each specialist's prompt as a "Library facts" block. Specialists run in subagents that **do not have Context7 access** — front-loading the lookup is the only way to keep their recommendations current.

After running, stamp `context7_last_checked: <today's ISO date>` on the story frontmatter so future invocations (implement, review) can skip when nothing changed.

Skip libraries the story doesn't touch. Don't fetch generic programming docs.

### Step 2 — Spawn the applicable specialists in parallel

The specialist set is determined by Step 1's dispatch table — typically 3-5 of these run, depending on story shape:

- `requirements-engineer` — **always**. Surfaces edge cases, hidden requirements, NFR call-outs.
- `solution-architect` — **always**. Module layout, domain model, API surface, alternatives considered.
- `qa-engineer` — **always**. Test pyramid, specific test cases, parity-test design, fixtures, coverage gaps.
- `security-engineer` — when `has_security_signal`. Threat model, authorization, validation, PII, audit events, tenancy.
- `performance-engineer` — when `has_performance_signal`. Hot paths, required indexes, N+1 risks, caching, latency budget.

**Skipped specialists pre-fill their section** with `(N/A — <reason>)`. The synthesis step (Step 3) preserves the N/A verbatim — the operator gets visibility into what was deliberately not refined.

Each subagent's prompt **must include**:
- The absolute path to the story file.
- The absolute paths to the ADRs referenced by `adr_refs`.
- The story's `depends_on` IDs (so the agent can read those stories' refinements if they exist).
- A brief reminder of the project context (the 122-story FLS modernization, sacred cows, multi-tenancy by `@TenantId`).
- **The Library facts block from Step 1.5** — the specialist must pin versions and APIs against these facts, not against training-data assumptions. If Step 1.5 was skipped (no library signal), pass an empty / omitted block.
- The agent's output format (already in their system prompt, but call it out so they emit it cleanly).

Send the applicable Agent calls in **one message** so they run concurrently. Each returns a single markdown blob in their agent-defined format.

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
refined_specialists: [requirements, solution, qa, security, performance]   # only the ones that actually ran
context7_last_checked: <today's ISO date>   # only when Step 1.5 ran; omit when skipped
```

`refined_specialists` reflects what **actually ran** per Step 1's dispatch — do not list a specialist whose section is pre-filled with `(N/A — ...)`. The story-shape dispatch is the authoritative source of truth for which dimensions got real refinement.

If the operator pre-set `refine_specialists:` in the story frontmatter (override), preserve their list verbatim in `refined_specialists` — the override won.

### Step 6 — Report back

Print to the user:

- The story ID and title.
- A 1-line summary per specialist of what they added (the headline of each section).
- Whether `## Open design questions` was populated and the count.
- Total refinement size delta (lines added).
- Suggested next action: `/modernize-implement S-NNN`.

## Quality bar

- **One story per invocation.** Batching is forbidden — refinement is JIT by design.
- **Context7 freshness pass before specialists.** Every library / framework / SDK / API the story touches gets its current docs fetched via Context7 (Step 1.5) and the facts handed to each specialist. Subagents have no Context7 access — front-loading is the only way to keep version pins and API recommendations current.
- **Conditional specialist dispatch.** Step 1's story-shape flags determine which specialists spawn (see the dispatch table). Specialists whose dimension doesn't apply to the story are skipped entirely rather than spawned-then-returning-N/A — biggest single token saving. `requirements-engineer`, `solution-architect`, `qa-engineer` always run; `security-engineer` + `performance-engineer` are conditional.
- **Frontmatter `refine_specialists:` overrides auto-detect.** When set, the operator's list wins (used to force a specialist the auto-detect missed, or to suppress one for a known-N/A case).
- **Context7 freshness pass is conditional.** Step 1.5 runs only when `has_library_signal` is true (story references libraries / frameworks / SDKs / version pins) AND `context7_last_checked` is stale (> 7 days) or absent. When skipped, specialist prompts carry an empty "Library facts" block.
- **Specialists run in parallel.** Sequential spawning wastes wall-clock; send all applicable Agent calls in one message.
- **Synthesis is mechanical, not editorial.** The specialists own the analysis; you own the layout. Don't paraphrase their findings into something weaker.
- **Replace, don't append, on re-run.** Refining twice should not double the file.
- **Frontmatter must reflect reality** — `refined_specialists` lists who actually ran. Skipped specialists' sections show `(N/A — <reason>)`; do NOT list them in `refined_specialists`.
- **Open design questions surface conflicts** — they are not "things I think the operator should know about." If a conflict exists, list it. If not, omit the section.

## What this skill does *not* do

- It does not modify acceptance criteria — those came from `/modernize-decompose`. If the refinement reveals an acceptance criterion is wrong, surface it in `## Open design questions`, don't silently fix it.
- It does not generate code. That's `/modernize-implement`.
- It does not refine epics. Epics are read by the specialists (for context) but not modified.
- It does not check `depends_on` are `done`. That's `/modernize-implement`'s precondition.
- It does not commit. Markdown edits land in the working tree; the operator commits when they're happy.

## When done

The story file has the applicable refinement sections (3-5 depending on story shape, plus `## Open design questions` if conflicts surfaced) and refined-status frontmatter. Skipped specialists' sections carry `(N/A — <reason>)`. The user has the next-action prompt. No other artifacts are touched.

If the user wants to refine the next story in `_ORDER.md`, they invoke `/modernize-refine <next-S-id>`. The skill has no batch mode.
