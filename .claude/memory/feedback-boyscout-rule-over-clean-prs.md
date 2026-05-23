---
name: feedback-boyscout-rule-over-clean-prs
description: "Operator prefers boyscout-rule fixes (fix broken things you encounter) over keeping PRs single-topic-clean; surfacing scope expansion via askuserquestion is fine but the default should be \"fix it now\""
metadata: 
  node_type: memory
  type: feedback
  originSessionId: bb002e94-1656-4854-8ab1-df47661620e3
---

When mid-implementation work surfaces pre-existing bugs (broken tests, stale comments, dead code, misclassified data), **fix them in the same PR** rather than deferring to a follow-up story or filing a separate issue. The operator prefers a boyscout-rule PR that leaves the codebase cleaner over a strictly single-topic PR that ships broken things forward.

**Why:** Established 2026-05-16 during S-128 (rebrand). Pre-existing extract-module test failures (unclassified tables in tenant-rules.yaml + Flights legacy_scope semantics) surfaced when the rebrand work touched adjacent files. I initially proposed deferring the Flights structural fix to an S-011 follow-up — the operator explicitly redirected with "I prefer things being fixed immediately (boyscout rule) over clean PRs that focus on a single topic." Single-topic discipline yields cleaner git history but lets broken state rot; the operator values rot-prevention more than PR purity.

**How to apply:**
- When a build/test fails for reasons unrelated to the current story, investigate it; don't reflexively dismiss as "pre-existing, not my scope."
- If the fix is mechanical / uncontroversial (clearly the right answer): apply it in the same PR alongside the primary work. Note the bundled fix in the commit message and PR description so the reviewer can see what's in-scope vs. boyscout.
- If the fix requires a real architectural decision (touches sacred cows, ADR territory, parity contracts): surface the choice via AskUserQuestion with the trade-offs, then apply whichever the operator picks. Don't default to "defer it."
- Updating commit-message subjects to reflect the broader scope (e.g. `#N: <primary> + boyscout: <secondary>`) is preferred over hiding bundled fixes.
- Applies to fls repo specifically; [[feedback-always-squash-merge]] complements this — squash collapses bundled boyscout commits into one clean main-tip commit.

**Extension (2026-05-16): trivial cleanups never get their own PR.** Skill amendments, doc typo fixes, missing-frontmatter-stamp corrections, no-SHA scrubbing, and other small workflow-hygiene items are **rolled into the next story's PR** instead of opening a one-off PR for them. The operator explicitly redirected with "don't open a PR just for such trivial cleanups. always roll them into the next story in boyscout-rule scope."

**How to apply:**
- When something trivial needs fixing post-merge (e.g. a stamp that didn't make it onto `main`, a stale SHA reference that slipped through, an out-of-date doc), add it to [[pending-boyscout-followups]]. Don't open a PR for it.
- The next `/modernize-implement S-NNN` run reads the pending list and folds each item into its branch alongside the primary story work. The implement skill should pick these up at Step 2 (after branch creation) or Step 6.5 (doc updates) depending on what they touch.
- After folding, **remove** the item from the pending list. The list is a queue, not a log.
- If the list grows unmanageable (≥ 5 items, or one has waited > 3 stories), surface to the operator — that's the signal that "next story will pick it up" stopped working.
