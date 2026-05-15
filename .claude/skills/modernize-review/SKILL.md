---
name: modernize-review
description: Phase 7 — review one implemented story across 3 streams: maintainability, security, usability via specialist subagents. Synthesizes into a ## Review section; files issues for blockers. Trigger: /modernize-review S-NNN.
---

# Phase 7 — Story Review (post-implement)

You are running phase 7 of the modernization workflow. Your job is to take **one** just-implemented story (S-NNN) and produce an honest, actionable review of the code it landed — primarily focused on **maintainability**, secondarily on **security** and **usability**.

The implement phase optimized for getting tests green and the story `done`. Review is the deliberate second pass that asks the questions implementation pressure suppresses: is this code something the team will want to extend a year from now? did the security plan actually land in the code, or just in the design notes? is the user-facing surface internally consistent with what already exists?

**Anchored on the PR when one exists.** The implement skill leaves a ready-for-review PR per story. This skill operates against that PR's diff: runs three specialist subagents in parallel, synthesizes findings into the story file. The PR is also where the operator merges from after blockers clear. For legacy / fallback stories without a PR, the skill falls back to commit-range mode.

Review is **just-in-time, not batch** — never review more than one story per invocation. Stale review is worse than no review. Most stories will be reviewed once and the findings either merged or rejected by the operator.

## Preconditions

1. The argument is a single story ID `S-NNN`. If missing, ask.
2. The story file exists at `docs/modernization/stories/S-NNN-*.md`.
3. The story has `status: done` in its frontmatter. If `in_progress`, ask whether to review the partial work (early-feedback mode) or wait. If `todo` or `blocked`, refuse. If already `reviewed: true`, warn and ask: re-review (overwrite the existing `## Review` section) or abort.
4. The story has `refined: true`. Review compares the diff against the refinement contracts (design notes / security plan / test plan / performance plan); without those there's no baseline to assess against. Bail with: "Story not refined — review needs the refinement sections as the contract baseline. Run `/modernize-refine S-NNN` first if you want to retro-spec, or skip review."
5. A diff is locatable for the story (see Step 1 below). If no PR exists for the story and no commits reference the story's GitHub issue and no commits fall in the `started_at` → `done_at` window, bail — there's nothing to review.
6. A PR exists for the story (`github_pr:` in frontmatter, status `OPEN` and `READY_FOR_REVIEW`). If the PR is `DRAFT`, ask whether to review the draft (early-feedback mode) or wait. If `MERGED`, proceed read-only against the merge commit. If absent entirely (legacy / fallback story), proceed in **commit-only mode** — the diff comes from the commit range instead of the PR.

These are the only legitimate `AskUserQuestion` calls. Everything else is derived from the story file + PR + diff + refinement sections.

## How to review

### Step 1 — Locate the PR + diff, load context

Find the artifact under review:

1. **Preferred — by PR.** If frontmatter has `github_pr: M`, run `gh pr view M --json number,state,headRefName,baseRefName,headRefOid,baseRefOid`. The diff is `<baseRefOid>..<headRefOid>` (compare-against-merge-base).
2. **Fallback — by commits.** If no `github_pr:` (legacy / fallback mode), enumerate commits as before: by issue ref (`git log --all --grep="#N\b"`), then story-ID prefix (`^S-NNN:`), then time window. The diff is `<first-commit>^..<last-commit>`.

Capture the diff as a workable artifact: `git diff <range> --stat` for the file map; `git diff <range> -- 'next/server/**'` and `git diff <range> -- 'next/web/**'` for the slices a specialist will want.

Then read in parallel:
- The story file in full (frontmatter, body, all five refinement sections).
- Every ADR listed in `adr_refs`.
- The legacy code paths cited in the story's acceptance criteria + design notes (file:line refs — open them, don't paraphrase).
- `00-seed.md` sacred cows + `02-vision-and-constraints.md` for the project invariants.

The refinement sections are the **contract**. The review's first question is always: did the implementation honor the contract?

### Step 1.5 — Context7 freshness pass

Identify every library / framework / SDK / API touched by the diff (Angular, Spring Boot, Tailwind, NgRx Signals, @angular-eslint, Flyway, Testcontainers, Playwright, Keycloak, JPA/Hibernate, springdoc-openapi, etc. — `git diff <range> -- '**/package.json' '**/pom.xml' '**/build.gradle*'` for dependency changes; scan the diff for new imports). For each, fetch current docs via Context7 to verify:

- **Version pins are still alive** (the story may have been refined months ago — check the package version against current major).
- **APIs used in the diff are still recommended** (not deprecated, not superseded by a newer pattern).
- **Peer-dep alignment is correct** (e.g. @angular-eslint major matches Angular major; NgRx Signals major matches Angular major).

Workflow per library: `mcp__context7__resolve-library-id` → pick best match → `mcp__context7__query-docs` for the specific question.

Pass the synthesized facts (1-3 lines per library — current major, key API names, deprecations) into each reviewer's prompt as a "Library facts" block. Reviewers run in subagents that **do not have Context7 access** — front-loading is the only way to keep reviewer findings anchored to current docs rather than training-data assumptions.

A version pin that has since been deprecated or a deprecated API used in the diff is a **maintainability finding** (not a security one) — improvement at minimum, blocker if the deprecation has a hard cut-off date that lands before the project ships.

Skip libraries the diff doesn't touch. Don't fetch generic programming docs.

### Step 2 — Spawn the three reviewers in parallel

Launch all three subagents in a single message with three Agent tool calls:

- `maintainability-reviewer` — primary focus. Code clarity, layering adherence, naming, duplication, test depth, dead code, dependency hygiene, ADR conformance, migration safety, comment / doc quality.
- `security-reviewer` — did the security plan land? `@PreAuthorize` annotations, tenant gates, input validation, audit events, PII handling, secrets, OWASP applicability gaps.
- `usability-reviewer` — UI consistency, i18n key coverage (no hardcoded strings), loading / empty / error states, accessibility basics (labels, ARIA, keyboard), responsive behavior, error-message clarity, parity-of-feel with surrounding components.

Each subagent's prompt **must include**:
- The absolute path to the story file.
- The absolute paths to ADRs in `adr_refs`.
- The diff range (commit SHAs) + a short list of changed file paths. In PR mode also include the PR number + URL so reviewers can cite line-anchored findings the operator can click through to.
- The absolute path to the relevant refinement section the reviewer should treat as the contract (Design notes for maintainability, Security plan for security, Design notes + Test plan for usability since there's no dedicated UX section).
- A reminder of project context — multi-tenancy by `@TenantId`, sacred cows in `00-seed.md`, the `next/server/` + `next/web/` layout, German default locale.
- The agent's output format (specified in each agent's system prompt; call it out so the synthesis step is mechanical).

Send the three Agent calls in **one message** so they run concurrently. Each returns a markdown blob with findings categorized blocker / improvement / nudge.

### Step 3 — Synthesize, don't re-decide

The three outputs are inputs, not drafts. You compose them into the story file's `## Review` section. You do not re-argue what they found.

**Severity rubric (apply when synthesizing — don't let reviewers downgrade their own findings):**

- **blocker** — the implementation breaks a refinement contract, an ADR, a sacred cow, or a security invariant. Examples: missing `@PreAuthorize` on a mutating endpoint; cross-tenant query without `@TenantId`; an acceptance criterion has no passing test; a Flyway migration is destructive without rollback. Blockers must be fixed before the story is considered shipped.
- **improvement** — code works and honors the contract but the next maintainer will pay a tax. Examples: function-length, naming clarity, duplicated helper, missing i18n key, test that exercises the wrong layer, dependency added without justification, a comment that explains the what instead of the why.
- **nudge** — minor / cosmetic / situational. Examples: opportunity to extract a helper that *might* pay off later, a marginally-clearer error message, a UX micro-polish. Operator can ignore without owing anyone an explanation.

**Conflict resolution:**
- If two reviewers' findings overlap, merge them into one finding at the higher severity, cross-referenced. Specialists agreeing on the same line is the highest-signal pattern in the review.
- If a reviewer's output is empty for a dimension that genuinely doesn't apply (e.g. usability on a pure backend story with no UI changes), preserve their "(N/A — no UI changes)" note rather than dropping the dimension.
- If a reviewer produced clearly broken output (no structured sections, hallucinated paths), re-run that one reviewer with a clarifying prompt. Don't synthesize garbage.

### Step 4 — Write findings back into the story file

Append (or replace, if already present) a single `## Review` section **after the existing refinement sections** in the story file:

```markdown
## Review

<!-- modernize-review: start -->

**Reviewed:** <ISO date> · **PR:** #M (or `Diff: <short-sha>..<short-sha>` in commit-only mode) · **Diff size:** N commits, M files · **Outcome:** <pass / blockers / improvements-only>

### Maintainability
- **[blocker]** <one-line finding> — `<path>:<line>`. <one-sentence why-it-matters>. **Fix:** <one-line action>.
- **[improvement]** ...
- **[nudge]** ...

### Security
- **[blocker]** ...
- **[improvement]** ...

### Usability
- **[improvement]** ...
- **[nudge]** ...

### Cross-reviewer agreements
- <when ≥2 reviewers reinforced each other on the same finding — these are the highest-signal — list them with the reviewers that agreed>

<!-- modernize-review: end -->
```

**Idempotency:** Re-running the skill on the same story **replaces** the section between the delimiter comments atomically. Everything else in the story body — including the five refinement sections — is preserved verbatim.

**No new sections elsewhere.** All review output goes inside the delimited block. Don't sprinkle TODOs throughout the file.

### Step 5 — Update frontmatter

Add or update:

```yaml
reviewed: true
reviewed_at: <today's date, ISO>
review_outcome: <pass | blockers | improvements-only>
review_blockers: <count>
review_improvements: <count>
review_nudges: <count>
```

`review_outcome`:
- `pass` — zero findings of any severity. Rare; surface it as worth celebrating in the report.
- `improvements-only` — no blockers; some improvements or nudges.
- `blockers` — at least one blocker. The story is `done` (the implement skill said so) but **not shipped** — the operator owes follow-up work before merge.

### Step 6 — File GitHub issues for blockers (autonomous when GitHub is available)

For **each blocker finding**, file a follow-up GitHub issue. This is the only artifact this skill creates outside the story file.

If the story has `github_issue: N` and `gh auth status` is OK:

1. For each blocker, run `gh issue create` with:
   - **Title:** `Review blocker (S-NNN): <one-line finding>`. Keep ≤ 72 chars.
   - **Body:** the full finding (path, why-it-matters, fix), plus `Found by review of #N` (the story's tracking issue), plus a permalink to the story file's `## Review` section.
   - **Labels** (best-effort, skip silently if absent): `review-blocker`, `story/S-NNN`. Don't auto-create missing labels.
2. Cross-link from the story's tracking issue: post one comment on `#N` listing the blocker issue numbers (`#M, #M+1, …`). One comment per review run, not one per blocker.
3. Capture the new issue numbers and reference them in the operator report.

**Improvements and nudges do not get issues.** They live in the story file. The operator decides whether to file follow-up work or accept the tax.

**Fallback when GitHub is unavailable:** skip issue creation; report the would-be issues as a numbered list in the operator output so the operator can file them manually.

### Step 7 — Report back

Print to the user:

- Story ID + title.
- **PR / diff reviewed:** PR number + URL (or SHA range in commit-only mode), commit count, file count, line-delta (+/-).
- **Outcome:** `pass` / `blockers` / `improvements-only` — bold.
- **Findings counts** by dimension × severity (compact form: `maintainability: 1B/3I/2N · security: 0B/1I/0N · usability: 0B/2I/1N`).
- **Blockers** (full list, one line each with path + which reviewer flagged it) — these need resolution before the story merges.
- **GitHub issues filed** for blockers: numbers + URLs. In fallback mode: "no GitHub — file these manually: <list>".
- **Top 3 improvements** worth surfacing in conversation even though they live in the story file.
- **Cross-reviewer agreements** (where ≥2 reviewers reinforced each other — highest-signal findings).
- Suggested next action:
  - If `blockers`: "`/modernize-rework S-NNN` to triage findings (address-now / defer / accept) → operator fixes address-now items → `/modernize-review S-NNN` to re-baseline → `/modernize-finalize S-NNN` to ship."
  - If `improvements-only`: "`/modernize-rework S-NNN` to triage the improvements (defer the ones not worth fixing now, accept the rest with rationale) → `/modernize-finalize S-NNN`."
  - If `pass`: "`/modernize-finalize S-NNN` — story is ready to ship."

## Quality bar

- **One story per invocation.** Batching is forbidden.
- **Context7 freshness pass before reviewers.** Every library / framework / SDK / API the diff touches gets its current docs fetched via Context7 (Step 1.5) and the facts handed to each reviewer. Subagents have no Context7 access — front-loading is the only way to catch deprecated APIs and dead version pins.
- **Three reviewers, one parallel batch.** Sequential spawning wastes wall-clock.
- **Anchor on the PR when available.** PR mode gives line-anchored cite-by-link in findings + a clean merge gate. Commit-only mode is the documented fallback when no PR exists.
- **Review against the contract, not against taste.** A reviewer who flags "I'd have named this differently" is wasting the operator's time. Flag what the refinement promised vs. what the code shipped.
- **Severity discipline.** Blocker = contract / ADR / invariant break, not "smells off." If you can't name what was broken, it's not a blocker.
- **Synthesis is mechanical, not editorial.** The reviewers own the findings; you own the layout. Don't paraphrase a blocker into an improvement to keep the outcome optimistic.
- **Replace, don't append, on re-run.** Reviewing twice should not double the file.
- **Maintainability is the headline.** When the three dimensions disagree about which finding leads, maintainability wins. The vision (`02-vision-and-constraints.md`) treats long-term maintainability as the rewrite's reason-for-being; review reflects that priority.
- **Blockers get issues; lesser findings don't.** Resist the urge to file an issue per finding — the story file is the right home for non-blocking work.
- **Don't review what wasn't built.** If the diff is empty for the usability dimension (pure backend story), say "(N/A — no UI changes)" and move on. Don't invent findings to fill the section.

## What this skill does *not* do

- It does not modify application code. Reviewers are read-only; the skill only writes to the story file + creates GitHub issues for blockers.
- It does not merge the PR. Merging is the operator's call after blockers clear.
- It does not modify the refinement sections. If the review reveals the refinement was wrong, that's a separate `/modernize-refine` re-run by the operator.
- It does not modify acceptance criteria. If an AC is unmeetable as built, surface it as a blocker; the operator decides whether to amend the AC or fix the code.
- It does not auto-flip `status: done` back to `in_progress`, even with blockers. The story is `done` per implementation; the blockers are follow-up work. The operator owns the gate.
- It does not run tests, generate code, or push commits. The diff is the static input.
- It does not review epics, ADRs, or cross-story integration. Per-story scope only. Cross-cutting review is the operator's call (and would warrant a different skill).
- It does not run on `status: todo` or `status: blocked` stories. There's nothing implemented to review.
- It does not transfer ownership of findings. The story file is the canonical record; the operator decides what to do with each finding.

## When done

The story file has a `## Review` section, the frontmatter reflects the review outcome, any blockers have GitHub follow-up issues filed (or are listed for manual filing), and the operator has a clear next action. The diff itself is untouched.

If the operator wants to review the next story, they invoke `/modernize-review <next-S-id>` after that story is `done`. The skill has no batch mode.
