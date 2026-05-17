---
name: modernize-review
description: Phase 7 — review one implemented story via specialist subagents. Conditional dispatch by diff scope. Synthesises into ## Review. Trigger: /modernize-review S-NNN.
---

# Phase 7 — Story Review (post-implement)

Take one just-implemented story (`S-NNN`) and produce an honest, actionable review. Maintainability + parity are the headline dimensions (whether the rewrite is delivering on its reason for being); security + usability follow.

Read [ADR 0022](../../../docs/modernization/adrs/0022-modernization-primary-directives.md). Doc-drift findings default to improvement/nudge per directive 1; schema-side business logic is a Directive-2 blocker.

Anchored on the PR when one exists; commit-range mode as fallback.

## Preconditions

1. Single `S-NNN` arg. Story file at top-level `stories/` (refuse if in `implemented/`).
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

For each not-spawned reviewer, pre-fill its section with `(N/A — <reason from diff scope>)`. Examples:
- parity skipped: `**Oracle:** N/A — parity_test: none and no flsserver/flsweb references in diff.`
- security skipped (docs-only): `(N/A — pure-docs diff; no code surface.)`

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

### Step 3 — Synthesise

Reviewers produce findings; you compose into `## Review`. Don't re-decide.

**Severity rubric:**

- **blocker** — breaks a refinement contract / ADR / sacred cow / security or parity invariant; OR an AC has no passing test; OR a Flyway migration is destructive without rollback; OR silent legacy-behavior divergence on a parity-relevant flow; OR (per ADR 0022 directive 2) a new CHECK constraint / generated column / trigger encoding business logic without inline structural justification.
- **improvement** — code works + honors contract but the next maintainer pays a tax.
- **nudge** — minor / cosmetic / situational.

**Per ADR 0022 directive 1**: doc-drift findings (header lists 8 vs body has 11; stale test-method-name in story) default to improvement/nudge — blocker only when the drift actively misleads a future implementer to write incorrect code.

**Conflict resolution:**
- Two reviewers same finding → merge at higher severity, cross-reference. Highest signal.
- Reviewer empty for genuinely-N/A dimension → preserve their "(N/A — …)" note.
- Reviewer output clearly broken (no structure, hallucinated paths) → re-run that one with clarifying prompt.

**Parity N/A:** preserve verbatim in section + `review_parity_oracle` frontmatter. An unverified parity claim is itself useful information.

### Step 4 — Write `## Review` section

Append (or replace, if `reviewed: true`) inside the delimiters:

```markdown
## Review

<!-- modernize-review: start -->

**Reviewed:** <ISO> · **PR:** #M (or `Diff: <sha>..<sha>`) · **Diff size:** N commits, M files · **Outcome:** <pass | blockers | improvements-only>

### Maintainability
- **[blocker]** <finding> — `<path>:<line>`. <why>. **Fix:** <action>.
- **[improvement]** ...
- **[nudge]** ...

### Parity
**Oracle:** <name of fixture / harness / oracle, or `(N/A — <reason>)`>
- **[blocker]** ...

### Security
- ...

### Usability  (when has_frontend; omitted for backend-only)
- ...

### Code quality  (when NOT has_frontend; populated by tech-writer-reviewer)
- ...

### Cross-reviewer agreements
- <when ≥2 reviewers reinforced the same finding — highest signal>

<!-- modernize-review: end -->
```

Re-run replaces the delimited block atomically. Refinement sections + everything else preserved verbatim.

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
- Synthesis is mechanical. Don't paraphrase blockers into improvements.
- Replace, don't append, on re-run.
- Maintainability + parity are the headlines.
- Per ADR 0022 directive 1: doc-drift defaults to improvement/nudge.

## Not in scope

Code edits (reviewers are read-only). PR merging. Refinement-section edits (re-run `/modernize-refine` separately). AC edits. `status` flips. Cross-story / epic / ADR review.

## When done

`## Review` section written; frontmatter stamped; operator has next action. Diff untouched. No GitHub issues filed (per memory rule).
