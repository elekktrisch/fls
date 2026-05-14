---
name: modernize-implement
description: Phase 6 of the modernization workflow. Implements a single refined user story end-to-end — writes code per the design notes, writes tests per the test plan, runs them until green, updates story status. Trigger when the user invokes /modernize-implement <story-id> (e.g. /modernize-implement S-058), after phase 5 (modernize-refine) has produced the refinement sections.
---

# Phase 6 — Story Implementation

You are running phase 6 of the modernization workflow. Your job is to take **one** refined story (S-NNN) and ship it: write the code per the architect's design notes, write the tests per the QA plan, honor the security and performance plans, run the full test suite green, and update the story's status to `done`.

This is the only phase that produces code. Everything before it has been planning.

## Preconditions (verify before doing any work)

1. The argument is a single story ID `S-NNN`. If missing, ask.
2. The story file exists at `docs/modernization/stories/S-NNN-*.md`.
3. The story has `refined: true` in its frontmatter. If not, **bail** with: "Story not refined. Run `/modernize-refine S-NNN` first."
4. The story has `status: todo` (not `in_progress`, not `done`, not `blocked`). If `in_progress`, ask whether to resume. If `done`, refuse. If `blocked`, refuse and surface the block reason.
5. Every story in `depends_on` has `status: done`. If not, **bail** with: "Dependency S-NNN is `<status>`. Resolve before implementing this story."
6. The working tree is clean (no uncommitted changes outside of test snapshots / lockfiles you yourself will produce). If dirty, ask before proceeding — uncommitted changes from a half-done previous story are a footgun.

These are the only legitimate blocking conditions. Everything else is derivable.

## How to implement

### Step 1 — Load the full spec

Read in parallel:
- The story file in full (frontmatter + body + all refinement sections).
- Every ADR listed in `adr_refs`.
- The legacy code paths cited in the acceptance criteria + design notes (file:line references — open them, don't paraphrase).
- The `parity_test` reference if any.
- The `00-seed.md` sacred cows.

Skim is not enough. The refinement sections give you what to build; the legacy code gives you the *behavior you must match*. Read both with the same attention.

### Step 2 — Flip status to `in_progress` immediately

Update the story's frontmatter:

```yaml
status: in_progress
started_at: <ISO date>
```

This is so a concurrent operator / agent can see the story is taken. Re-saving the file at the start (not at the end) means crashes don't leak a permanent `todo` lie.

### Step 3 — Build a working plan

Use TaskCreate to track sub-steps. Default ordering:

1. **DB migration** (if Domain model changed) — write the new `V*__name.sql` migration first; verify Flyway picks it up; assert the schema is what the design specified.
2. **Test scaffolding** — write the test cases from the Test plan in skeleton form (assertion stubs that fail). This locks the contract before the code exists.
3. **Backend code** — entity → repository → service → controller, in that order. Honor the Security plan's `@PreAuthorize` annotations and validation rules; honor the Performance plan's indexes (already in the migration) and fetch strategies.
4. **Frontend code** — Signal Store → component → route, in that order, all consuming the generated TS client.
5. **Make the tests pass.** Iterate the unit + integration + parity tests until green. Don't skip a failing test; if it's wrong, fix the test before fixing the code.
6. **Run the full local suite.** Don't break previously-done stories. If a previously-green test now fails, stop — that's a regression worth surfacing before continuing.
7. **Verify acceptance criteria one by one.** Each criterion in `acceptance:` must map to a passing test (or a manual check note in your report).
8. **Parity test if any.** Run the `parity_test` file or invoke the parity-verification harness; assert zero-delta (or known-delta per the cutover gate).

### Step 4 — Honor the refinement sections, don't override them

The five refinement sections are contracts:
- **Design notes:** the module layout / API shape is decided. Don't redesign mid-implementation. If the design is wrong, stop and escalate; don't silently improvise.
- **Security plan:** every `@PreAuthorize`, every validation rule, every audit event is specified. Implement exactly. If you discover a gap, flag it; don't paper over.
- **Test plan:** every test case is specified. Write each one. If a case turns out to be impossible to test at the specified layer, surface it in the report and write the closest-equivalent at the next layer up.
- **Performance plan:** every required index is in the migration. Every N+1 risk is mitigated (fetch join, `@EntityGraph`, batch size). Latency budget is the post-implement verification target.
- **Open design questions:** if populated, stop and ask the user before continuing. The refine phase explicitly flagged these as un-resolved.

### Step 5 — Escalation triggers (stop and ask)

Stop and surface to the user — do not improvise — if:

- A parity test fails and the only way to make it pass is to change behavior (not a bug fix). The story's parity oracle is wrong, or the new system has a real divergence.
- A previously-green test in another story fails because of this story's changes. Regression — stop, don't bundle.
- A `depends_on` story's artifact is missing despite the story being `done`. Something rotted.
- The legacy code you're porting has an apparent bug. Don't silently fix it; ask whether to preserve.
- An acceptance criterion is unmeetable as written, given what the code actually does or what the platform allows.

For each, ask the user with a precise, single question. Don't pile up alternatives.

### Step 6 — Final verification

- Every acceptance criterion green (point to the test name or manual-check note).
- Test suite green (full server `./gradlew test`, full web `pnpm test`, full Playwright `playwright test` if applicable).
- Parity test green or zero-delta verified.
- Performance plan's latency target met (where measurable in test; defer prod measurement to S-111).
- No regression in previously-done stories.

### Step 7 — Update status and report

Update the story's frontmatter:

```yaml
status: done
done_at: <ISO date>
```

Do not commit. The operator reviews + commits. Print to the user:

- Story ID + title.
- Files changed (count + summary by area: backend / frontend / db / tests).
- Tests added (count by layer).
- Acceptance criteria status: each criterion + how it was verified.
- Parity test result (if applicable): pass / known-delta-with-rationale.
- Performance test result (if applicable): measured latency vs. budget.
- **What the user must review before committing**: the diff scope, any unusual files, any test fixtures added.
- Suggested next action: `/modernize-refine <next-S-id>` from `_ORDER.md`.

## Quality bar

- **One story per invocation.** Never bundle stories. Even tiny S-estimated stories run independently.
- **Refinement must exist.** `refined: false` is a hard bail.
- **Tests first, then code.** Even when a story is too small to TDD literally, write the tests *before* declaring done.
- **Honor the refinement contracts.** The five sections are not suggestions.
- **Don't commit.** Markdown + code edits land in the working tree.
- **Don't break green.** A regression in another story's tests is a stop condition.
- **Acceptance criteria → tests.** Each criterion has a named test (or a documented manual-check).
- **Update status atomically.** `in_progress` at the start, `done` at the end. No half-states.

## What this skill does *not* do

- It does not write or modify ADRs.
- It does not split stories or rewrite acceptance criteria. If the story is wrong, stop and escalate.
- It does not commit or push.
- It does not run the production deploy. That's S-121.
- It does not refine — that's `/modernize-refine`. If a story arrives with `refined: false`, bail.
- It does not update `_ORDER.md`. The order is fixed at decompose time.

## When done

The story is `status: done`, the tests are green, the user has a clear next action (the next story in `_ORDER.md`), and the working tree has the diff staged for the operator to review.

If the user wants to keep going, they invoke `/modernize-refine <next>` followed by `/modernize-implement <next>`. No batch mode.
