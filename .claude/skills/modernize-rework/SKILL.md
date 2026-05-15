---
name: modernize-rework
description: Phase 8 — triage findings from /modernize-review (incl. Copilot inline comments). Per finding: address-now / defer (auto-files follow-up story) / accept (annotates with rationale). Iterates with /modernize-review until clean. Trigger: /modernize-rework S-NNN.
---

# Phase 8 — Story Rework (post-review triage)

You are running phase 8 of the modernization workflow. Your job is to take **one** just-reviewed story (S-NNN) and walk the operator through every finding in `## Review` + every unresolved Copilot inline comment on the PR, producing one of three decisions per finding: **address-now**, **defer**, **accept**.

This skill does not edit application code. It produces a triage state: a TaskCreate list for address-now items, a set of follow-up story files for defer items, and annotations on the current story for accept items. The operator does the actual code edits between this skill and the next `/modernize-review` re-run.

Rework is **iterative**. A story can need 1–3 review→rework cycles before finalize. Each cycle:

1. `/modernize-review` produces findings.
2. `/modernize-rework` triages them.
3. Operator addresses address-now items, pushes to the same branch.
4. CI re-runs.
5. Operator re-invokes `/modernize-review` to re-baseline.

Eventually a review run has no blockers and the operator is comfortable with the remaining improvements / nudges. Then `/modernize-finalize` ships it.

## Preconditions

1. The argument is a single story ID `S-NNN`. If missing, ask.
2. The story file exists at `docs/modernization/stories/S-NNN-*.md`.
3. The story has `reviewed: true`. If not, bail — there's nothing to rework. Run `/modernize-review S-NNN` first.
4. The story's `review_outcome` is `blockers` or `improvements-only`. If `pass`, refuse with: "Nothing to rework — review found no findings. Run `/modernize-finalize S-NNN` instead."
5. The `## Review` section is present and parseable (between the `<!-- modernize-review: start -->` / `end -->` delimiters). If malformed, bail with: "Review section corrupted. Re-run `/modernize-review S-NNN` first."

These are the only legitimate `AskUserQuestion` calls for *preconditions*. Per-finding triage (Step 3) is its own legitimate set of prompts.

## How to rework

### Step 1 — Load findings

Read in parallel:
- The story file (frontmatter + body + `## Review` section).
- If `github_pr: M` is set: `gh pr view M --json reviews,comments` to get Copilot's inline comments. Also `gh api repos/{owner}/{repo}/pulls/M/comments` for the line-anchored review comments.
- Every ADR listed in `adr_refs` (for context — finding rationale may cite ADRs).

Parse the `## Review` section into a structured list:

```
[
  { dimension: "maintainability", severity: "blocker", text: "...", path: "...", line: ..., status: "open" },
  { dimension: "maintainability", severity: "improvement", text: "...", status: "open" },
  ...
  { dimension: "copilot", severity: "improvement", text: "...", anchored_to: "<comment-id>", status: "open" },
]
```

For Copilot inline comments not yet merged into the `## Review` synthesis: include them as separate findings with `dimension: "copilot-inline"`. These are the ones the synthesis step in `/modernize-review` dropped as "formatter-domain noise" or didn't surface — but the operator may still want to triage some explicitly.

**Skip findings already marked `[accepted]` or `[deferred]`** in the existing `## Review` section. They were triaged in a prior rework pass; don't re-prompt.

### Step 2 — Auto-resolve obsolete Copilot inline comments

Before prompting the operator, sweep Copilot's inline comments for ones that no longer apply:

1. For each Copilot inline comment, check: does the line it points at still exist in the current HEAD of the story's branch? Has it been edited since the comment was posted?
2. If the line was removed or non-trivially edited (whitespace-only edits don't count): auto-resolve via `gh api -X POST repos/{owner}/{repo}/pulls/comments/{comment-id}/replies` with body `Auto-resolved by /modernize-rework — line no longer present or substantially edited.` Then mark the comment resolved.
3. Report the count of auto-resolved comments in the operator output.

This avoids dragging the operator through findings that the rework cycle has already invalidated.

### Step 3 — Triage each finding (one prompt per finding)

For each remaining open finding, present:

- **Dimension + severity**: e.g. "Maintainability · blocker".
- **Path:line**: clickable.
- **Finding text** (one or two lines from the review).
- **Suggested fix** (one line, from the review).

Ask the operator to pick:

- **address-now** — operator will fix this in the current PR before re-review.
- **defer** — open a follow-up story; don't touch in this PR.
- **accept** — leave as-is in this PR; record rationale (one short line).

**Blockers default to address-now and cannot be deferred or accepted without an extra confirmation.** A blocker is a contract/ADR/invariant break; deferring or accepting one bypasses the rewrite's quality gates. If the operator picks defer/accept on a blocker, confirm once: "This is a blocker (contract/invariant break). Defer/accept it anyway? <yes / re-choose>." A second confirmation lands the decision.

Improvements and nudges have no escalation — any of the three choices is valid.

### Step 4 — Process the decisions

**For each `address-now`:**

- Add a TaskCreate task: `Rework S-NNN: <finding>` (file:line). Mark `in_progress`.
- The skill does not write the code. The operator does. The TaskCreate list is the operator's checklist.
- Annotate the corresponding `## Review` entry: prepend `[in-rework]` to the bullet (e.g. `- **[blocker]** [in-rework] missing @PreAuthorize ...`).

**For each `defer`:**

- Generate the next available story ID. Find the highest `S-NNN` in `docs/modernization/stories/`, increment.
- Slugify the finding text (lowercase, kebab-case, ≤ 6 words).
- Create `docs/modernization/stories/S-NNN-<slug>.md` with frontmatter:
  ```yaml
  ---
  id: S-NNN
  title: <finding text, ≤ 70 chars>
  epic: <originating story's epic, if any>
  status: todo
  estimate: <S | M | L — default S unless finding looks bigger>
  parity_test: <empty unless the originating finding was parity-sensitive>
  depends_on: []
  adr_refs: <inherit from originating story if relevant>
  refined: false
  origin: rework
  origin_story: S-NNN (originating)
  origin_finding: <one-line summary of the finding>
  ---

  ## Context

  Follow-up from review of S-NNN (originating story). The originating story's review found:

  > <finding text verbatim>
  > **Suggested fix:** <suggested fix verbatim>
  > **Path:** <path:line>

  See [`S-NNN-<originating-slug>.md`](S-NNN-<originating-slug>.md#review) for full review context.

  ## Acceptance criteria

  - [ ] <one-line restatement of the fix, as a testable criterion>
  ```
- Append the new story ID to `_ORDER.md` (after the originating story's row), with a one-line note indicating it's a rework follow-up.
- Annotate the corresponding `## Review` entry: prepend `[deferred → S-XXX]` to the bullet.

**For each `accept`:**

- Prompt the operator for a one-line rationale.
- Annotate the corresponding `## Review` entry: prepend `[accepted: <rationale>]` to the bullet (e.g. `- **[improvement]** [accepted: legacy parity is more important than the cleanup here] duplicated helper ...`).

**Idempotency:** annotations replace any prior `[in-rework]` / `[deferred → S-XXX]` / `[accepted: ...]` prefix on the same bullet. A re-run of rework should produce the same annotations, not stack them.

### Step 5 — Update story file + frontmatter

Replace the `## Review` section in place (annotations applied per Step 4). Don't touch anything else in the body.

Update frontmatter:

```yaml
reworked: true
reworked_at: <ISO date>
rework_address_now: <count>
rework_deferred: <count>
rework_accepted: <count>
rework_followups: [S-XXX, S-XXY, ...]  # IDs of follow-up stories created
```

**Commit the rework state** (annotations + new follow-up stories + `_ORDER.md` update + frontmatter) as a single commit on the story's branch with message `#N: rework triage — <X address-now / Y deferred / Z accepted>` (or `S-NNN: rework triage — ...` in fallback mode). Push.

This commit is **bookkeeping, not code**. CI will run but should be a no-op for any code tests; only doc-level diffs.

### Step 6 — Report + next-action prompt

Print to the user:

- Story ID + title.
- **Findings triaged:** total count.
- **Decisions:** `<X> address-now · <Y> deferred → <follow-up IDs> · <Z> accepted`.
- **Address-now list** (the TaskCreate items, one line each with path).
- **Deferred follow-ups:** new story IDs + titles. These are now in `_ORDER.md`.
- **Auto-resolved Copilot comments:** count + (if non-zero) "Marked obsolete because the lines they pointed at have been edited or removed."
- **Next action:**
  - If `address-now > 0`: "Fix the address-now items (TaskCreate list above), push to `story/S-NNN-<slug>`, then re-run `/modernize-review S-NNN` to re-baseline. After review is clean, `/modernize-finalize S-NNN`."
  - If `address-now == 0` (everything was deferred or accepted): "No code rework needed. Re-run `/modernize-review S-NNN` to confirm the annotations don't reveal any new blockers, then `/modernize-finalize S-NNN`."

## Quality bar

- **One story per invocation.** Batching is forbidden.
- **One decision per finding, no skipping.** Every open finding gets address-now / defer / accept. "I'll think about it" is not an option — the skill exists to drive findings to a state.
- **Blocker escalation is mandatory.** Defer or accept on a blocker requires a second confirmation. Don't let blockers slip silently.
- **Annotations replace, not stack.** A re-run of rework on the same story produces the same annotations, not a layered history. The `## Review` section stays scannable.
- **Skill does not write code.** Address-now items go into TaskCreate for the operator. Don't try to auto-fix a "missing @PreAuthorize" or "rename this method" finding — those are operator-judgement calls.
- **Follow-up stories carry provenance.** `origin: rework` and `origin_story: S-NNN` in frontmatter so the relationship is traceable from either side.
- **Auto-resolve Copilot comments only when safe.** Line removed or substantially edited is safe. Whitespace-only or comment-only edits don't count — those don't change behavior and the original Copilot finding may still apply.

## What this skill does *not* do

- It does not edit application code. Address-now is operator work.
- It does not merge the PR. That's `/modernize-finalize`.
- It does not re-run `/modernize-review` automatically. The operator invokes it after addressing address-now items; the skill just suggests the command.
- It does not modify the refinement sections. If a finding reveals the refinement was wrong, defer it as a follow-up that re-runs `/modernize-refine` on a related story — don't silently rewrite design notes here.
- It does not delete or reorder stories in `_ORDER.md` beyond appending follow-ups. Operator owns the order.
- It does not change `status: done` back to `in_progress`, even with blockers. The implement skill said it was done; the blockers are follow-up work.
- It does not iterate. The skill runs once per cycle. Re-invocation by the operator is the loop primitive.

## When done

The story file's `## Review` section is annotated, follow-up stories are filed, `_ORDER.md` is updated, frontmatter reflects the rework state, and the operator has a TaskCreate list of code edits to make. The triage commit is pushed.

If the operator wants to iterate, they: address the address-now items → push → run `/modernize-review S-NNN`. When review is clean, they run `/modernize-finalize S-NNN`.
