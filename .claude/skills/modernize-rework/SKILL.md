---
name: modernize-rework
description: Phase 8 — triage findings from /modernize-review. Per finding: address-now / defer (auto-files follow-up story) / accept (annotates with rationale). Step 3.5 surfaces workflow / docs / ADR optimizations from the findings as a deliberate meta-pass. Iterates with /modernize-review until clean. Trigger: /modernize-rework S-NNN.
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

### Step 3.5 — Meta-pass: workflow / docs / ADR optimizations

After processing each finding's disposition, deliberately scan the findings **holistically** for patterns that suggest the rewrite's *process or governance docs themselves* should change — not just this story's code. This is the feedback loop that keeps the `/modernize-*` workflow improving as the rewrite progresses; without it, the same class of finding shows up on story after story and the operator pays the same tax repeatedly.

The skill **proposes** candidates from the triaged findings; the **operator decides** per candidate. Auto-decisions (the `--bold` heuristics) do NOT apply to this step — meta-improvements always require operator judgment.

#### What to scan for

Three categories worth surfacing:

1. **Workflow / skill improvement.** A recurring finding pattern that a `/modernize-*` skill could prevent at source. Heuristics for spotting them:
   - **≥ 2 findings reference the same kind of drift** (e.g. multiple stale-line citations in `CONVENTIONS.md`; multiple findings about test-fixture leakage). Suggests a check should land in one of the skills (`/modernize-implement` self-review, `/modernize-review` reviewer prompt, or a CI lint).
   - **A finding's "fix" line names a manual step the operator will repeat across stories** (e.g. "remember to also update tenant-rules.yaml"). Suggests automation in `/modernize-refine` or `/modernize-implement`.
   - **A finding reveals a sequencing bug in the skills themselves** (e.g. ADR amendment got proposed mid-implement but never surfaced at finalize). Suggests a skill order-of-operations fix.

2. **ADR addition / amendment.**
   - **Story invented a pattern not covered by any existing ADR** → propose a new ADR. Capture: title, the decision the story implicitly made, the alternatives that were available.
   - **Finding reveals an existing ADR is silently being violated by the diff** → propose an ADR amendment clarifying scope, OR a finding upgrade to blocker (operator's call).
   - **An ADR's "Follow-ups" section names a story that never landed**, and the current story exposes the gap → propose either filing that follow-up story now or amending the ADR to drop the stale follow-up.

3. **CONVENTIONS.md addition or revision.**
   - **A pattern emerged in this story's code that other stories should mirror** (a new helper, a new test layout, a new column-shape decision) → propose a CONVENTIONS section citing this story's canonical example by `file:line`.
   - **A CONVENTIONS section was contradicted by this story's findings** (rare; usually surfaced as a blocker, not a meta-improvement) → propose a revision.

#### How to surface candidates

In **interactive mode**:

1. Look at the just-processed findings + their dispositions. If the operator address-now'd 5 things that share a theme (e.g. "doc consistency drift"), that's a pattern.
2. Ask the operator one batched `AskUserQuestion` (≤ 4 questions) with the top candidate patterns. For each candidate, give 3 choices:
   - **Apply now** — small PR for the workflow / doc / ADR change. Skill drafts the change; operator approves before commit. Lands in a separate branch (typically `chore/modernize-<skill>-<topic>` for workflow changes, or directly on the doc / ADR for non-protected docs).
   - **File a follow-up story** — a `S-NNN` with `origin: rework-meta` + `kind: <workflow-improvement | adr-addition | adr-amendment | conventions-update>` so it lands in the backlog at a known priority.
   - **Skip** — no action; the finding's normal triage covers it.

In **`--bold` mode**:

The auto-triage rules apply to per-finding disposition only. The meta-pass **still prompts the operator** because pattern-level synthesis benefits from human judgment more than pattern-level matching does. The skill may auto-flag candidates ("3 findings cite stale line ranges — consider a CONVENTIONS rule on stable citations?") but never auto-decides.

If the operator says "no patterns worth surfacing" or skips every candidate, the step exits silently — recorded as `rework_meta_improvements: 0` in frontmatter.

#### What to file

For **apply-now candidates**:

- **Workflow / skill change** → draft the diff against the relevant `.claude/skills/<skill>/SKILL.md` or `.claude/agents/<agent>.md`. Surface the diff for operator review. Commit on a new branch `chore/modernize-<skill>-<topic>`; PR; operator merges separately.
- **ADR amendment** → draft the amendment paragraph(s) inline in the relevant `docs/modernization/adrs/<file>.md`. Surface to operator; commit on a branch `chore/adr-<NNNN>-<topic>`; PR.
- **New ADR** → draft using the existing ADR template shape (Context / Options / Decision / Consequences); branch `chore/adr-<next-NNNN>-<slug>`; PR.
- **CONVENTIONS.md update** → draft the section diff; commit on a branch `chore/conventions-<topic>`; PR.

For **follow-up story candidates**:

- Mint the next available `S-NNN` (same algorithm as Step 3's defer path: max(S-NNN across `stories/` and `stories/implemented/`) + 1).
- Use the same frontmatter shape as a normal follow-up, but with:
  ```yaml
  origin: rework-meta
  kind: workflow-improvement | adr-addition | adr-amendment | conventions-update
  origin_story: S-NNN (originating)
  origin_pattern: <one-line summary of the pattern observed>
  ```
- The story body explains the pattern, cites which findings of the originating story surfaced it, and proposes the change.
- Append to `_ORDER.md` after the originating story's row.

#### Cumulative meta-improvements file (optional, recommended)

If the operator accepts > 3 meta-improvements over the course of the rewrite (across multiple stories), suggest a cumulative ledger at `docs/modernization/meta-improvements-log.md` listing them in chronological order. This file is operator-curated; the skill only suggests it on threshold cross.

The threshold and the file's name are advisory; the operator can ignore.

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
rework_followups: [S-XXX, S-XXY, ...] # IDs of follow-up stories created (per-finding defers)
rework_meta_improvements: <count>     # Step 3.5 candidates the operator accepted (0 if none)
rework_meta_followups:                # IDs of meta-improvement follow-up stories filed
  - { id: S-XXX, kind: workflow-improvement }
  - { id: S-XXY, kind: conventions-update }
rework_meta_prs:                      # branches / PRs opened for apply-now meta-improvements
  - { branch: chore/modernize-review-efficiency, url: https://github.com/.../pull/N }
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
- **Meta-improvements (if Step 3.5 surfaced any):** count + breakdown:
  - Apply-now meta-PRs: branch + PR URL each.
  - Meta follow-up stories: S-NNN + kind (`workflow-improvement` / `adr-addition` / `adr-amendment` / `conventions-update`).
  - "No meta-improvements surfaced" if Step 3.5 yielded nothing.
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
- **Meta-pass is mandatory but lightweight.** Step 3.5 runs on every invocation; if no patterns surface, it exits silently with `rework_meta_improvements: 0`. The point is the deliberate scan, not the volume of output — even "no patterns this time" is a useful checkpoint.
- **Meta-improvements never auto-decide.** Pattern-level synthesis benefits from operator judgment more than per-finding triage does. `--bold` accelerates the cheap end; the meta-pass is the *other* end and stays manual.

## What this skill does *not* do

- It does not edit application code. Address-now is operator work.
- It does not merge the PR. That's `/modernize-finalize`.
- It does not re-run `/modernize-review` automatically. The operator invokes it after addressing address-now items; the skill just suggests the command.
- It does not modify the refinement sections. If a finding reveals the refinement was wrong, defer it as a follow-up that re-runs `/modernize-refine` on a related story — don't silently rewrite design notes here.
- It does not delete or reorder stories in `_ORDER.md` beyond appending follow-ups. Operator owns the order.
- It does not change `status: done` back to `in_progress`, even with blockers. The implement skill said it was done; the blockers are follow-up work.
- It does not iterate. The skill runs once per cycle. Re-invocation by the operator is the loop primitive.
- It does not auto-apply meta-improvements. Step 3.5 surfaces candidates; the operator decides per candidate. Apply-now changes go through a separate branch + PR (the operator merges), not direct-to-main inline.
- It does not auto-create the meta-improvements log file. If the operator opts into the cumulative ledger (`docs/modernization/meta-improvements-log.md`), they curate it themselves — the skill only suggests it on the > 3 threshold.

## When done

The story file's `## Review` section is annotated, follow-up stories are filed, `_ORDER.md` is updated, frontmatter reflects the rework state, and the operator has a TaskCreate list of code edits to make. The triage commit is pushed.

If the operator wants to iterate, they: address the address-now items → push → run `/modernize-review S-NNN`. When review is clean, they run `/modernize-finalize S-NNN`.
