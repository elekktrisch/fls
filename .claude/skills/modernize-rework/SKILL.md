---
name: modernize-rework
description: Phase 8 — triage findings from /modernize-review. Per finding: address-now / defer (auto-files follow-up story) / accept (annotates with rationale). Iterates with /modernize-review until clean. Trigger: /modernize-rework S-NNN.
---

# Phase 8 — Story Rework (post-review triage)

You are running phase 8 of the modernization workflow. Your job is to take **one** just-reviewed story (S-NNN) and walk the operator through every finding in `## Review`, producing one of three decisions per finding: **address-now**, **defer**, **accept**.

This skill does not edit application code. It produces a triage state: a TaskCreate list for address-now items, a set of follow-up story files for defer items, and annotations on the current story for accept items. The operator does the actual code edits between this skill and the next `/modernize-review` re-run.

Rework is **iterative**. A story can need 1–3 review→rework cycles before finalize. Each cycle:

1. `/modernize-review` produces findings.
2. `/modernize-rework` triages them.
3. Operator addresses address-now items, pushes to the same branch.
4. CI re-runs.
5. Operator re-invokes `/modernize-review` to re-baseline.

Eventually a review run has no blockers and the operator is comfortable with the remaining improvements / nudges. Then `/modernize-finalize` ships it.

## Invocation modes

The skill has two modes, selected by the operator at invocation:

- **`/modernize-rework S-NNN`** — *interactive mode* (default). Every open finding gets a per-finding `AskUserQuestion` prompt. Operator owns every decision.
- **`/modernize-rework S-NNN --bold`** — *auto-triage mode*. The skill mechanically resolves the cheap end of the spectrum and only asks the operator about findings that genuinely need judgment. Specifically:
  - **Nudges** auto-accept with a derived rationale.
  - **Improvements** whose suggested-fix line is a single sentence *and* whose finding cites a single `path:line` auto-address-now (still added to the TaskCreate checklist for the operator to actually fix).
  - **Blockers** *always* prompt — never auto-decided regardless of mode.
  - **Improvements that fail the auto-heuristic** (multi-file, ambiguous fix, no path cite) still prompt.
  - Every auto-decision is annotated distinctly (`[auto-accepted: ...]` / `[auto-in-rework]`) so the operator can audit + reverse at finalize time.

`--bold` is for delivery-speed throughput — it pays off when running many low-complexity stories back to back. It is opt-in by design: blanket auto-triage erodes the judgment that makes the rework phase valuable. Don't make it the default.

## Preconditions

1. The argument is a single story ID `S-NNN`, optionally followed by `--bold`. If missing, ask.
2. The story file exists at `docs/modernization/stories/S-NNN-*.md` (top-level). If not found there, also check `docs/modernization/stories/implemented/S-NNN-*.md`:
   - If found in `implemented/`: refuse with "Story S-NNN is already finalized (in stories/implemented/). Rework triage runs before finalize — once shipped, follow-ups land as fresh stories. If you genuinely need to re-triage, copy the file back to stories/ first."
   - If not found in either location: bail.
3. The story has `reviewed: true`. If not, bail — there's nothing to rework. Run `/modernize-review S-NNN` first.
4. The story's `review_outcome` is `blockers` or `improvements-only`. If `pass`, refuse with: "Nothing to rework — review found no findings. Run `/modernize-finalize S-NNN` instead."
5. The `## Review` section is present and parseable (between the `<!-- modernize-review: start -->` / `end -->` delimiters). If malformed, bail with: "Review section corrupted. Re-run `/modernize-review S-NNN` first."

These are the only legitimate `AskUserQuestion` calls for *preconditions*. Per-finding triage (Step 2) is its own legitimate set of prompts in interactive mode; in `--bold` mode it's a smaller set, scoped to blockers + ambiguous improvements.

## How to rework

### Step 1 — Load findings

Read in parallel:
- The story file (frontmatter + body + `## Review` section).
- Every ADR listed in `adr_refs` (for context — finding rationale may cite ADRs).

Parse the `## Review` section into a structured list:

```
[
  { dimension: "maintainability", severity: "blocker", text: "...", path: "...", line: ..., status: "open" },
  { dimension: "maintainability", severity: "improvement", text: "...", status: "open" },
  { dimension: "security", severity: "improvement", text: "...", status: "open" },
  { dimension: "usability", severity: "nudge", text: "...", status: "open" },
]
```

**Skip findings already marked `[accepted: ...]`, `[auto-accepted: ...]`, or `[deferred → S-XXX]`** in the existing `## Review` section. They were triaged in a prior rework pass; don't re-prompt. Findings marked `[in-rework]` or `[auto-in-rework]` are re-prompted (or re-auto-decided in `--bold` mode), because the operator may now want a different disposition; the prior annotation is replaced, not stacked.

### Step 2 — Triage each finding

**Interactive mode (default):** one `AskUserQuestion` prompt per open finding, in `## Review` order.

**`--bold` mode:** pre-classify each finding; only prompt for the residue.

For each remaining open finding, present (in either mode):

- **Dimension + severity**: e.g. "Maintainability · blocker".
- **Path:line**: clickable.
- **Finding text** (one or two lines from the review).
- **Suggested fix** (one line, from the review).

Ask the operator (or auto-decide per the table below) between:

- **address-now** — fix in the current PR before re-review.
- **defer** — open a follow-up story; don't touch in this PR.
- **accept** — leave as-is in this PR; record rationale (one short line).

**Blockers default to address-now and cannot be deferred or accepted without an extra confirmation.** A blocker is a contract/ADR/invariant break; deferring or accepting one bypasses the rewrite's quality gates. If the operator picks defer/accept on a blocker, confirm once: "This is a blocker (contract/invariant break). Defer/accept it anyway? <yes / re-choose>." A second confirmation lands the decision.

Improvements and nudges have no escalation — any of the three choices is valid.

#### `--bold` auto-triage rules

When the operator invoked with `--bold`, apply these rules *before* prompting. Findings that don't match any auto-rule fall through to a normal `AskUserQuestion`.

| Severity | Heuristic | Auto-decision | Annotation |
|---|---|---|---|
| `blocker` | (any) | **never auto-decide — always prompt** | (operator-driven) |
| `improvement` | finding cites a single `path:line` **and** `**Fix:**` line is a single sentence | `auto-address-now` | `[auto-in-rework]` |
| `improvement` | multi-file / multi-line cite, ambiguous fix, or no `path:` cite | prompt | (operator-driven) |
| `nudge` | (any) | `auto-accept` with derived rationale | `[auto-accepted: <rationale>]` |

**Rationale derivation for auto-accepted nudges:**

- If the finding text already contains a rationale-like clause (e.g. "minor / cosmetic / situational"), use the first ≤ 80-char phrase from the finding's why-it-matters sentence.
- Otherwise default to: `auto-accepted via --bold: <severity> severity, no contract impact`.

**Auto-address-now still creates the TaskCreate item.** The skill never writes code; auto-address-now means "we agree this should be fixed in this PR" — the operator still does the fix between this skill's exit and the next `/modernize-review` re-run. The only thing `--bold` skips is the prompt; the TaskCreate ledger is identical to interactive mode.

**Prompt residue in `--bold` mode:** the operator only sees prompts for blockers and ambiguous improvements. If every finding was auto-decidable, no prompts run and the skill goes straight from Step 1 to Step 3.

### Step 3 — Process the decisions

**For each `address-now` (or `auto-address-now`):**

- Add a TaskCreate task: `Rework S-NNN: <finding>` (file:line). Mark `in_progress`.
- The skill does not write the code. The operator does. The TaskCreate list is the operator's checklist.
- Annotate the corresponding `## Review` entry: prepend `[in-rework]` (interactive) or `[auto-in-rework]` (`--bold` auto-decided) to the bullet — e.g. `- **[blocker]** [in-rework] missing @PreAuthorize ...` or `- **[improvement]** [auto-in-rework] rename helper ...`.

**For each `defer`:**

- Generate the next available story ID. Find the highest `S-NNN` across **both** `docs/modernization/stories/S-*.md` and `docs/modernization/stories/implemented/S-*.md` (implemented stories still own their IDs — minting a fresh ID must avoid collisions with shipped work), then increment.
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

**For each `accept` (or `auto-accept`):**

- Interactive: prompt the operator for a one-line rationale.
- `--bold` auto-accept: use the derived rationale (per the Step 2 table).
- Annotate the corresponding `## Review` entry: prepend `[accepted: <rationale>]` (interactive) or `[auto-accepted: <rationale>]` (`--bold`) to the bullet — e.g. `- **[improvement]** [accepted: legacy parity is more important than the cleanup here] duplicated helper ...` or `- **[nudge]** [auto-accepted: nudge severity, no contract impact] minor wording ...`.

**Idempotency:** annotations replace any prior `[in-rework]` / `[auto-in-rework]` / `[deferred → S-XXX]` / `[accepted: ...]` / `[auto-accepted: ...]` prefix on the same bullet. A re-run of rework should produce the same annotations, not stack them.

**A re-run upgrades `auto-*` to non-`auto-*` if the operator interactively re-decides.** A re-run *never* downgrades a manual decision to an auto one. Operator decisions are sticky.

### Step 4 — Update story file + frontmatter

Replace the `## Review` section in place (annotations applied per Step 4). Don't touch anything else in the body.

Update frontmatter:

```yaml
reworked: true
reworked_at: <ISO date>
rework_mode: interactive | bold  # 'bold' if invoked with --bold, else 'interactive'
rework_address_now: <count>           # total, both interactive + auto
rework_deferred: <count>
rework_accepted: <count>              # total, both interactive + auto
rework_auto_decisions: <count>        # subset of the above resolved by --bold heuristics (omit/zero in interactive mode)
rework_followups: [S-XXX, S-XXY, ...] # IDs of follow-up stories created
```

**Commit the rework state** (annotations + new follow-up stories + `_ORDER.md` update + frontmatter) as a single commit on the story's branch with message `#N: rework triage — <X address-now / Y deferred / Z accepted>` (or `S-NNN: rework triage — ...` in fallback mode). In `--bold` mode the message subject appends ` (--bold)` so the commit log records the mode used.

This commit is **bookkeeping, not code**. CI will run but should be a no-op for any code tests; only doc-level diffs.

### Step 5 — Report + next-action prompt

Print to the user:

- Story ID + title.
- **Mode:** `interactive` or `bold`.
- **Findings triaged:** total count.
- **Decisions:** `<X> address-now · <Y> deferred → <follow-up IDs> · <Z> accepted`.
- **Auto-decisions (if `--bold`):** count + breakdown by severity (e.g. `5 auto-decisions: 4 nudges auto-accepted, 1 improvement auto-address-now`). **Operator audit prompt at end of report:** "Audit the auto-decisions before re-review? They are listed inline in the `## Review` section with `[auto-*]` prefixes; any can be re-decided by re-running `/modernize-rework S-NNN` without `--bold`."
- **Address-now list** (the TaskCreate items, one line each with path; mark `[auto]` for auto-decided items).
- **Deferred follow-ups:** new story IDs + titles. These are now in `_ORDER.md`.
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
- **Blockers never auto-decide.** Even in `--bold` mode, every `[blocker]` finding gets a per-finding prompt. The flag accelerates the cheap end of the spectrum; the gate at the contract-breach end stays manual.
- **Auto-decisions are auditable and reversible.** Every auto-decision is annotated with `[auto-*]` so the operator can spot-check after the skill exits, and a non-bold re-run replaces auto annotations with operator-chosen ones (the reverse never happens — operator decisions are sticky).
- **`--bold` is opt-in.** Don't make it the default in any orchestrator that wraps this skill — blanket auto-triage erodes the rework phase's purpose.

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
