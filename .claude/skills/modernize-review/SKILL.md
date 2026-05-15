---
name: modernize-review
description: Phase 7 — review one implemented story across 4 streams: 3 specialist subagents (maintainability/security/usability) + Copilot on the PR. Synthesizes into a ## Review section; files issues for blockers. Trigger: /modernize-review S-NNN.
---

# Phase 7 — Story Review (post-implement)

You are running phase 7 of the modernization workflow. Your job is to take **one** just-implemented story (S-NNN) and produce an honest, actionable review of the code it landed — primarily focused on **maintainability**, secondarily on **security** and **usability**, with **GitHub Copilot** as an independent fourth lens.

The implement phase optimized for getting tests green and the story `done`. Review is the deliberate second pass that asks the questions implementation pressure suppresses: is this code something the team will want to extend a year from now? did the security plan actually land in the code, or just in the design notes? is the user-facing surface internally consistent with what already exists?

**Anchored on the PR.** The implement skill leaves a ready-for-review PR per story. This skill operates against that PR: requests Copilot review, runs the three specialist subagents against the same diff, and synthesizes findings into the story file. The PR is also where the operator merges from after blockers clear.

Review is **just-in-time, not batch** — never review more than one story per invocation. Stale review is worse than no review. Most stories will be reviewed once and the findings either merged or rejected by the operator.

## Preconditions

1. The argument is a single story ID `S-NNN`. If missing, ask.
2. The story file exists at `docs/modernization/stories/S-NNN-*.md`.
3. The story has `status: done` in its frontmatter. If `in_progress`, ask whether to review the partial work (early-feedback mode) or wait. If `todo` or `blocked`, refuse. If already `reviewed: true`, warn and ask: re-review (overwrite the existing `## Review` section) or abort.
4. The story has `refined: true`. Review compares the diff against the refinement contracts (design notes / security plan / test plan / performance plan); without those there's no baseline to assess against. Bail with: "Story not refined — review needs the refinement sections as the contract baseline. Run `/modernize-refine S-NNN` first if you want to retro-spec, or skip review."
5. A diff is locatable for the story (see Step 1 below). If no PR exists for the story and no commits reference the story's GitHub issue and no commits fall in the `started_at` → `done_at` window, bail — there's nothing to review.
6. A PR exists for the story (`github_pr:` in frontmatter, status `OPEN` and `READY_FOR_REVIEW`). If the PR is `DRAFT`, ask whether to review the draft (early-feedback mode) or wait. If `MERGED`, proceed read-only against the merge commit. If absent entirely (legacy / fallback story), proceed in **commit-only mode** — 3 reviewer subagents but no Copilot stream.

These are the only legitimate `AskUserQuestion` calls. Everything else is derived from the story file + PR + diff + refinement sections.

## How to review

### Step 1 — Locate the PR + diff, load context

Find the artifact under review:

1. **Preferred — by PR.** If frontmatter has `github_pr: M`, run `gh pr view M --json number,state,headRefName,baseRefName,headRefOid,baseRefOid,reviews,comments`. The diff is `<baseRefOid>..<headRefOid>` (compare-against-merge-base). The PR is also where Copilot review lives.
2. **Fallback — by commits.** If no `github_pr:` (legacy / fallback mode), enumerate commits as before: by issue ref (`git log --all --grep="#N\b"`), then story-ID prefix (`^S-NNN:`), then time window. The diff is `<first-commit>^..<last-commit>`.

Capture the diff as a workable artifact: `git diff <range> --stat` for the file map; `git diff <range> -- 'next/server/**'` and `git diff <range> -- 'next/web/**'` for the slices a specialist will want.

Then read in parallel:
- The story file in full (frontmatter, body, all five refinement sections).
- Every ADR listed in `adr_refs`.
- The legacy code paths cited in the story's acceptance criteria + design notes (file:line refs — open them, don't paraphrase).
- `00-seed.md` sacred cows + `02-vision-and-constraints.md` for the project invariants.

The refinement sections are the **contract**. The review's first question is always: did the implementation honor the contract?

### Step 1.5 — Trigger Copilot review (PR mode only; skip in commit-only mode)

GitHub Copilot can review PRs. Either it's already running (repo-level auto-review enabled) or you trigger it explicitly. The skill handles both.

1. **Detect existing Copilot review.** From the `gh pr view --json reviews` output, scan for a review where the author is the Copilot bot (`author.is_bot` true AND login matches `copilot-pull-request-reviewer` / `github-copilot` / similar). If present and recent (≤ diff's last-commit timestamp), Copilot already reviewed — proceed to Step 2.
2. **Request a review** if none present:
   - `gh pr comment <PR> --body "@copilot review"` (the comment triggers Copilot review on demand).
   - Alternative (if the comment trigger fails): `gh pr edit <PR> --add-reviewer "copilot-pull-request-reviewer[bot]"` — depends on repo configuration. Try the comment first.
3. **Poll for Copilot's review.** Copilot review typically lands in 30s–3min. Poll `gh pr view <PR> --json reviews` every 30s up to a 5-minute ceiling. While polling, **do not block** — dispatch Step 2's reviewer subagents in parallel.
4. **Capture findings** when the review lands:
   - The review **summary** (`reviews[?].body` for the bot author) — top-level commentary.
   - **Inline comments** via `gh api repos/{owner}/{repo}/pulls/{PR}/comments` — line-anchored findings; filter by `user.login` matching the bot.
5. **Timeout / unavailable:** if no review appears within 5 minutes, mark Copilot stream `unavailable` and note the reason (timeout / bot disabled / API limit). Continue with 3 streams — Copilot is **best-effort**, not blocking.

### Step 2 — Spawn the three reviewers in parallel (while Copilot is polling)

Launch all three subagents in a single message with three Agent tool calls. Copilot's review is already in flight (Step 1.5); these three run concurrently with it.

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

While these run, finish polling for Copilot's review per Step 1.5. By the time the three subagents return, Copilot's review is either captured or marked `unavailable`.

### Step 3 — Synthesize, don't re-decide

The four streams (3 reviewer subagents + Copilot) are inputs, not drafts. You compose them into the story file's `## Review` section. You do not re-argue what they found.

**Severity rubric (apply when synthesizing — don't let reviewers downgrade their own findings):**

- **blocker** — the implementation breaks a refinement contract, an ADR, a sacred cow, or a security invariant. Examples: missing `@PreAuthorize` on a mutating endpoint; cross-tenant query without `@TenantId`; an acceptance criterion has no passing test; a Flyway migration is destructive without rollback. Blockers must be fixed before the story is considered shipped.
- **improvement** — code works and honors the contract but the next maintainer will pay a tax. Examples: function-length, naming clarity, duplicated helper, missing i18n key, test that exercises the wrong layer, dependency added without justification, a comment that explains the what instead of the why.
- **nudge** — minor / cosmetic / situational. Examples: opportunity to extract a helper that *might* pay off later, a marginally-clearer error message, a UX micro-polish. Operator can ignore without owing anyone an explanation.

**Handling Copilot's stream:** Copilot doesn't use this severity vocabulary. Map its findings as follows:

- Copilot finding **contradicts a refinement contract / ADR / invariant** → blocker, same as our reviewers.
- Copilot finding **flags a real bug or security gap** (null deref, off-by-one, missing escape, race) → blocker.
- Copilot finding **suggests a cleanup or alternative** that doesn't violate a contract → improvement or nudge depending on impact.
- Copilot finding **is style noise** ("consider const", "this could be a one-liner") that the formatter would already enforce → drop. Don't pollute the review with formatter-domain findings.
- Copilot finding **duplicates a specialist reviewer's finding** → merge them under that reviewer's dimension; note "+ Copilot concurs" in the entry. Independent agreement is a strong signal.

**Conflict resolution:**
- If two streams' findings overlap, merge them into one finding at the higher severity, cross-referenced. A specialist + Copilot concurring on the same line is the highest-signal pattern in the review.
- If a reviewer's output is empty for a dimension that genuinely doesn't apply (e.g. usability on a pure backend story with no UI changes), preserve their "(N/A — no UI changes)" note rather than dropping the dimension.
- If a reviewer produced clearly broken output (no structured sections, hallucinated paths), re-run that one reviewer with a clarifying prompt. Don't synthesize garbage.
- If Copilot was `unavailable`, note that in the synthesis — don't pretend the stream existed.

### Step 4 — Write findings back into the story file

Append (or replace, if already present) a single `## Review` section **after the existing refinement sections** in the story file:

```markdown
## Review

<!-- modernize-review: start -->

**Reviewed:** <ISO date> · **PR:** #M (or `Diff: <short-sha>..<short-sha>` in commit-only mode) · **Diff size:** N commits, M files · **Outcome:** <pass / blockers / improvements-only>
**Streams:** maintainability ✓ · security ✓ · usability ✓ · copilot ✓ (or `unavailable: <reason>`)

### Maintainability
- **[blocker]** <one-line finding> — `<path>:<line>`. <one-sentence why-it-matters>. **Fix:** <one-line action>.
- **[improvement]** ... (+ Copilot concurs)
- **[nudge]** ...

### Security
- **[blocker]** ...
- **[improvement]** ...

### Usability
- **[improvement]** ...
- **[nudge]** ...

### Copilot review
<!-- findings unique to Copilot's stream — those that didn't merge into the three dimensions above -->
- **[improvement]** <one-line finding> — `<path>:<line>`. Copilot-summary excerpt or paraphrase. **Fix:** <one-line action>.
- ...
<!-- if Copilot ran but had nothing unique to add: "(no additional findings beyond what the specialist reviewers covered)" -->
<!-- if Copilot was unavailable: "(unavailable: <reason>; review proceeded with 3 specialist streams)" -->

### Cross-stream agreements
- <when ≥2 streams reinforced each other on the same finding — these are the highest-signal — list them with the streams that agreed>

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
review_copilot: <ran | unavailable | skipped-commit-mode>
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
- **Streams:** `maintainability ✓ · security ✓ · usability ✓ · copilot <ran|unavailable:reason|skipped>`.
- **Findings counts** by dimension × severity (compact form: `maintainability: 1B/3I/2N · security: 0B/1I/0N · usability: 0B/2I/1N · copilot: 0B/1I/0N`).
- **Blockers** (full list, one line each with path + which stream(s) flagged it) — these need resolution before the story merges.
- **GitHub issues filed** for blockers: numbers + URLs. In fallback mode: "no GitHub — file these manually: <list>".
- **Top 3 improvements** worth surfacing in conversation even though they live in the story file.
- **Cross-stream agreements** (where ≥2 streams reinforced each other — highest-signal findings).
- Suggested next action:
  - If `blockers`: "`/modernize-rework S-NNN` to triage findings (address-now / defer / accept) → operator fixes address-now items → `/modernize-review S-NNN` to re-baseline → `/modernize-finalize S-NNN` to ship."
  - If `improvements-only`: "`/modernize-rework S-NNN` to triage the improvements (defer the ones not worth fixing now, accept the rest with rationale) → `/modernize-finalize S-NNN`."
  - If `pass`: "`/modernize-finalize S-NNN` — story is ready to ship."

## Quality bar

- **One story per invocation.** Batching is forbidden.
- **Four streams, three subagents in one parallel batch + Copilot async.** The three specialist subagents launch together; Copilot review is requested up front and polled in parallel. Don't serialize.
- **Anchor on the PR when available.** PR mode unlocks Copilot, line-anchored cite-by-link, and a clean merge gate. Commit-only mode is the documented fallback when no PR exists.
- **Copilot is best-effort, not blocking.** A 5-minute poll ceiling. If unavailable, 3-stream review still ships. Don't stall on the bot.
- **Review against the contract, not against taste.** A reviewer who flags "I'd have named this differently" is wasting the operator's time. Flag what the refinement promised vs. what the code shipped.
- **Severity discipline.** Blocker = contract / ADR / invariant break, not "smells off." If you can't name what was broken, it's not a blocker.
- **Synthesis is mechanical, not editorial.** The reviewers own the findings; you own the layout. Don't paraphrase a blocker into an improvement to keep the outcome optimistic.
- **Replace, don't append, on re-run.** Reviewing twice should not double the file.
- **Maintainability is the headline.** When the four streams disagree about which finding leads, maintainability wins. The vision (`02-vision-and-constraints.md`) treats long-term maintainability as the rewrite's reason-for-being; review reflects that priority. Copilot's findings are a complement to this, not a substitute — Copilot is strongest at "did you miss something obvious," weakest at "does this fit the project's conventions."
- **Blockers get issues; lesser findings don't.** Resist the urge to file an issue per finding — the story file is the right home for non-blocking work.
- **Don't review what wasn't built.** If the diff is empty for the usability dimension (pure backend story), say "(N/A — no UI changes)" and move on. Don't invent findings to fill the section.

## What this skill does *not* do

- It does not modify application code. Reviewers are read-only; the skill only writes to the story file + creates GitHub issues for blockers.
- It does not merge the PR. Merging is the operator's call after blockers clear.
- It does not engage Copilot in a conversation. If Copilot's review is wrong or incomplete, the operator can `@copilot` it directly on the PR; the skill doesn't iterate with the bot.
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
