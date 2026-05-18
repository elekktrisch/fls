---
name: modernize-review
description: Phase 7 — review one implemented story via specialist subagents. Conditional dispatch by diff scope. Synthesises into ## Review. Trigger: /modernize-review S-NNN.
---

# Phase 7 — Story Review (post-implement)

Take one just-implemented story (`S-NNN`) and produce an honest, actionable review. Maintainability + parity are the headline dimensions (whether the rewrite is delivering on its reason for being); security + usability follow.

Read [ADR 0022](../../../docs/modernization/adrs/0022-modernization-primary-directives.md). Doc-drift findings default to improvement/nudge per directive 1; schema-side business logic is a Directive-2 blocker.

Anchored on the PR when one exists; commit-range mode as fallback.

## Story ID resolution

The story ID can be passed explicitly (`S-NNN`) or inferred from the current branch when it matches `story/S-NNN-*` (check via `git rev-parse --abbrev-ref HEAD`; pattern `^story/S-(\d{3})(-.*)?$`):

- **Arg + branch match** → proceed with the arg.
- **Arg + branch is `story/S-MMM-*` where `MMM ≠ NNN`** → bail: *"current branch is `story/S-MMM-...` but you passed `S-NNN`; switch branch or correct the arg."*
- **Arg + branch isn't a story branch** → proceed with the arg.
- **No arg + branch matches `story/S-NNN-*`** → use the branch's `S-NNN` (the common case after `/modernize-implement` opened the branch).
- **No arg + branch doesn't match** → prompt the operator for the story ID via `AskUserQuestion` (single question).

## Preconditions

1. Story ID resolved per § Story ID resolution above. Story file at top-level `stories/` OR `stories/implemented/` — `/modernize-implement` Step 8 archives the story into `implemented/` as part of the mark-done commit (folded in to save one CI cycle vs. archiving at finalize). Review runs against either location; finalize only adds merged stamps.
2. `status: done` (`in_progress` → ask if early-feedback; `todo`/`blocked` → refuse; already `reviewed: true` → ask re-review or abort).
3. `refined: true` (else "review needs refinement-section contracts; run /modernize-refine first or skip review").
4. Locatable diff (PR exists or commits ref the story's issue, story-ID prefix, or fall in `started_at`-`done_at` window). Bail if nothing to review.
5. PR exists (`github_pr:`), `OPEN` + `READY_FOR_REVIEW` (`DRAFT` → ask if early-feedback; `MERGED` → read-only against merge commit; absent → commit-only fallback).

## Procedure

### Step 1 — Locate diff, compute scope flags, load context

Diff: `gh pr view <github_pr> --json baseRefName,headRefOid` → `<merge-base>..<headRefOid>`. Fallback: enumerate commits by issue ref / story-ID / time window; diff is `<first>^..<last>`.

Capture file list. Compute scope flags:

- `has_frontend` — any `next/web/` path.
- `has_backend` — any `next/server/` path.
- `has_legacy_ref` — any `flsserver/` or `flsweb/` path.
- `has_dep_change` — any `**/package.json`, `**/build.gradle*`, `**/pom.xml`, `**/*-lock.*`.
- `is_docs_only` — every path matches `docs/**`, `*.md`, `CONVENTIONS.md`, or `next/ops/*.sh|*.json`.

Reviewer dispatch:

| Reviewer | Spawn when | Section in `## Review` |
|---|---|---|
| `maintainability-reviewer` | **always** | `### Maintainability` |
| `security-reviewer` | not `is_docs_only` | `### Security` |
| `parity-reviewer` | `parity_test` non-empty OR `has_legacy_ref` | `### Parity` |
| `usability-reviewer` | `has_frontend` | `### Usability` |
| `tech-writer-reviewer` | NOT `has_frontend` (replaces usability for backend-only) | `### Code quality` |

For each not-spawned reviewer: **omit the section entirely.** Two exceptions:
- **Parity skipped** → keep `### Parity` heading + `**Oracle:** N/A — <reason>` line. The explicit "no oracle exists" claim is itself useful at finalize.
- **All reviewers skipped (pure-docs diff)** → write a single-line `## Review` block: `**Outcome:** pass (docs-only diff).`

Load: full story file, ADRs in `adr_refs`, legacy code at cited file:line (open, don't paraphrase), `00-seed.md`, `02-vision-and-constraints.md`.

### Step 1.5 — Context7 freshness (conditional)

Skip if `has_dep_change` is false. Skip if `context7_last_checked` < 7 days old and no dep-file path changed since.

Otherwise: per library / framework / SDK touched (dep files + new imports), `mcp__context7__resolve-library-id` → `mcp__context7__query-docs`. Verify version pins alive + APIs not deprecated + peer-dep alignment correct. Pass synthesised facts (1-3 lines per library) into each reviewer's prompt as "Library facts". Reviewers run in subagents without Context7 access — front-loading is the only way.

Stamp `context7_last_checked: <ISO date>` on frontmatter. A deprecated version pin or deprecated API used = **maintainability finding** (not security).

### Step 2 — Spawn reviewers in parallel

Send all applicable `Agent` calls in ONE message. Each subagent prompt must include:

- Absolute path to story file.
- Absolute paths to ADRs in `adr_refs`.
- Diff range (SHAs) + short list of changed paths. In PR mode also include PR number + URL.
- Absolute path to the relevant refinement section (Design notes for maintainability; Test plan + `parity_test` for parity; Security plan for security; Design notes + Test plan for usability; Design notes + cross-doc set for tech-writer).
- `parity_test` frontmatter value (parity-reviewer specifically).
- Project context: `@TenantId` multi-tenancy, sacred cows in `00-seed.md`, `next/server/` + `next/web/` layout, German default locale, [ADR 0022 directives](../../../docs/modernization/adrs/0022-modernization-primary-directives.md).
- Library facts from Step 1.5 (or empty/omitted).
- Output format (each agent's system prompt specifies; call it out).
- **Brevity rule:** "Findings only — one bullet each, `file:line` cite, blocker/improvement/nudge tag. No prefatory summary, no per-dimension overview, no padding. If you have nothing in a dimension, emit nothing." Restate this in every spawn prompt — it overrides the agent's default output template when they conflict.

### Step 3 — Synthesise (editorial)

Reviewers produce findings; you compose into `## Review`. **The job is editorial: cut padding, drop empty sections, never paraphrase a blocker into an improvement.** Don't re-decide severity, but do drop a reviewer's "Looks good overall" preamble — findings only.

**Severity rubric:**

- **blocker** — breaks a refinement contract / ADR / sacred cow / security or parity invariant; OR an AC has no passing test; OR a Flyway migration is destructive without rollback; OR silent legacy-behavior divergence on a parity-relevant flow; OR (per ADR 0022 directive 2) a new CHECK constraint / generated column / trigger encoding business logic without inline structural justification.
- **improvement** — code works + honors contract but the next maintainer pays a tax.
- **nudge** — minor / cosmetic / situational.

**Per ADR 0022 directive 1**: doc-drift findings (header lists 8 vs body has 11; stale test-method-name in story) default to improvement/nudge — blocker only when the drift actively misleads a future implementer to write incorrect code.

**Stale story content from over-eager refinement is itself a finding.** If `## Design notes` enumerates file trees / method signatures / test method names / threat-model rows whose mitigations all landed in code, raise it as an `improvement` ("prune story to load-bearing decisions") with a pointer to the implement-skill prune step. Don't merge the prune yourself — that's the implementer's job.

**Conflict resolution:**
- Two reviewers same finding → merge at higher severity, cross-reference. Highest signal.
- Reviewer empty for genuinely-N/A dimension → **omit the section** rather than writing `(N/A — …)` placeholder. Skipped-by-dispatch sections are different (handled at write-back, see Step 4).
- Reviewer output clearly broken (no structure, hallucinated paths) → re-run that one with clarifying prompt.

**Parity N/A:** preserve verbatim in section + `review_parity_oracle` frontmatter. An unverified parity claim is itself useful information.

### Step 4 — Write `## Review` section

Append (or replace, if `reviewed: true`) inside the delimiters:

```markdown
## Review

<!-- modernize-review: start -->

**Reviewed:** <ISO> · **PR:** #M (or `Diff: <sha>..<sha>`) · **Outcome:** <pass | blockers | improvements-only>

### Maintainability
- **[blocker]** <finding> — `<path>:<line>`. **Fix:** <action>.
- **[improvement]** ...

### Parity
**Oracle:** <name of fixture / harness / oracle, or `(N/A — <reason>)`>
- **[blocker]** ...

### Security
- ...

### Cross-reviewer agreements
- <when ≥2 reviewers reinforced the same finding — highest signal>

<!-- modernize-review: end -->
```

**Omit any section with zero findings.** Don't write empty `### Security` /
`### Usability` / `### Code quality` headings. Parity is the one exception
— preserve the `(N/A — …)` line because an explicit "no oracle exists"
statement is itself useful information for finalize.

Findings format: one bullet, `file:line` cite, severity tag, then *why* in
≤ 1 sentence, then `**Fix:**` in ≤ 1 sentence. Drop "diff size" /
"N commits, M files" — `gh pr view` carries that.

Re-run replaces the delimited block atomically. Refinement sections +
everything else preserved verbatim.

### Step 5 — Frontmatter

```yaml
reviewed: true
reviewed_at: <ISO date>
review_outcome: pass | blockers | improvements-only
review_blockers: <count>
review_improvements: <count>
review_nudges: <count>
review_parity_oracle: <one-line name or "N/A — <reason>">
review_reviewers: [maintainability, ...]
context7_last_checked: <ISO date>  # only when Step 1.5 ran
```

### Step 6 — Report

- Story ID + title.
- PR / diff URL + size.
- **Outcome** (bold).
- Reviewers run / skipped (compact: `ran: [maint, sec, tech-writer] · skipped: [parity (parity_test=none), usability (no frontend)]`).
- Findings counts per dimension × severity (compact: `maintainability: 1B/3I/2N · parity: (N/A — skipped) · ...`).
- **Blockers** (full list — one line each with path + flagging reviewer).
- Top 3 improvements worth surfacing in conversation.
- Cross-reviewer agreements.
- Next: `blockers` → `/modernize-rework S-NNN` → fix → re-review → `/modernize-finalize`. `improvements-only` → `/modernize-rework` (triage) → `/modernize-finalize`. `pass` → `/modernize-finalize`.

**Per [[feedback-no-per-blocker-issues]]: do NOT file GitHub issues per blocker.** The story `## Review` section is canonical.

## Quality bar

- One story per invocation.
- Conditional reviewer dispatch — skip rather than spawn-then-return-N/A.
- `usability-reviewer` and `tech-writer-reviewer` share the fourth slot (mutually exclusive by `has_frontend`).
- Context7 conditional (only when dep-files changed + cache stale).
- Reviewers run in parallel (single message, multiple `Agent` calls).
- Severity discipline. Blocker = contract / ADR / invariant / Directive-2 break, not "smells off."
- **Synthesis is editorial: findings only, no padding, omit empty sections.** Don't paraphrase blockers into improvements.
- Replace, don't append, on re-run.
- Maintainability + parity are the headlines.
- Per ADR 0022 directive 1: doc-drift defaults to improvement/nudge. **Bloated refinement sections are doc-drift** — flag for prune.

## Not in scope

Code edits (reviewers are read-only). PR merging. Refinement-section edits (re-run `/modernize-refine` separately). AC edits. `status` flips. Cross-story / epic / ADR review.

## When done

`## Review` section written; frontmatter stamped; operator has next action. Diff untouched. No GitHub issues filed (per memory rule).
