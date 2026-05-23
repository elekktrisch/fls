---
name: feedback-no-shas-in-committed-docs
description: "Don't write git commit SHAs into files that are themselves committed (docs, frontmatter, READMEs); they're stale by construction (the citing commit can't be the cited commit) and erased entirely by squash-merge"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: bb002e94-1656-4854-8ab1-df47661620e3
---

Never embed a git commit SHA into a file that's being committed itself. The reference is broken by construction:

1. **Causality violation.** The commit doing the embedding can't be the commit being referenced — the referenced SHA is at best one commit behind the citing one, and the gap is invisible to a future reader who treats the cited SHA as "the relevant commit."
2. **Squash-merge erases the SHA entirely.** All per-commit SHAs on a story branch collapse into one squash SHA on `main`. A `## Review` section that says "fixed in `c1ef2f7`" is referencing a SHA that **does not exist anywhere** post-merge + branch-delete. `git show c1ef2f7` returns nothing.

**Why:** Established 2026-05-16 during S-128 finalize. I drafted a `## Review` section that said "(2 blockers fixed in `c1ef2f7`)" and went to commit it to `main` post-squash. The operator caught it: that SHA literally does not exist on `main` after the squash + branch-delete, and even if it did, the citing commit would be `c1ef2f8`-or-later, so the chronological inversion is permanent.

**How to apply:**
- **Forbidden in committed files:** `.md` docs, story frontmatter, ADR bodies, READMEs, `CONVENTIONS.md`, any text that ships in a commit. No `c1ef2f7`-style references, no `(fixed in <commit>)`, no `(introduced by <commit>)`.
- **Allowed in ephemera:** issue comments, PR descriptions, the operator-facing done report, slack/chat. These are read once and disposable; SHAs work there because the reader checks them against the live PR within minutes.
- **Allowed in frontmatter ONLY when the value is genuinely post-merge stamped:** the modernize-finalize skill explicitly forbids `merge_commit:` for the same reason. Don't reintroduce it.
- **Substitutes that age well:**
  - Cite by commit *subject*: "fixed in the self-review-fixes commit" not "fixed in `c1ef2f7`".
  - Cite by file:line: "see `RebrandConventionsTest.java:67`" not "see commit X that added it".
  - Cite by PR number: "per PR #31" — PR numbers persist post-merge.
  - Cite by story ID: "per S-128 Step 6.7" — story IDs are stable.
- **When narrating a sequence of work in a doc:** describe the *change* (added the ratchet, then fixed the test naming) rather than tagging each change with a SHA.

**Where the habit comes from** (root-cause notes for future me):
- `modernize-implement/SKILL.md` Step 7's CI-failure issue-comment template includes `<sha>` references — those are operator-facing ephemera (issue comments), which leaks into my head as "SHA citations are normal."
- `modernize-finalize/SKILL.md` Step 5 says post "Merged in <merge-commit-SHA>" on the issue — that's fine (the merge SHA is permanent on `main`), but it primes me to drop SHAs into committed text too.
- Specialist agents (especially `maintainability-reviewer`) cite SHAs in their reports. Their output becomes my input — and I copy-paste them into the synthesized doc text.
- My own narrative tic: citing the fix commit feels precise; the post-squash hindsight is missed in the moment.

Operator-approved fix scope (2026-05-16): amend both `.claude/skills/modernize-implement/SKILL.md` and `.claude/skills/modernize-finalize/SKILL.md` to call this out explicitly. Agents stay as-is (they're reused outside modernize-* contexts; their SHA citations in transient reports are fine).

Related: [[feedback-boyscout-rule-over-clean-prs]] — the skill amendments themselves get rolled into the next story's PR, not their own.
