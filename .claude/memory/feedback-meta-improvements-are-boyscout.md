---
name: feedback-meta-improvements-are-boyscout
description: "rework meta-improvements (Step 3.5) NEVER get their own chore/* branches or separate PRs; fold into the current story's PR or the next story's PR per boyscout rule"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: fd9566e3-7c76-4560-9ec3-bf0c50bff204
---

`/modernize-rework` Step 3.5 surfaces meta-improvements (workflow / docs / ADR / CONVENTIONS optimizations). The skill's default suggestion is "Apply-now meta-PR: branch + PR; operator merges separately." **Do not do this.** Always fold meta-improvements into the current story's PR as boyscout edits — never spin a separate `chore/modernize-*` / `chore/conventions-*` / `chore/adr-*` branch.

**Why:** per [[feedback-boyscout-rule-over-clean-prs]], the operator prefers boyscout fixes bundled into existing PRs over single-topic PRs. Spinning meta-improvements into separate PRs creates:
- branch + PR overhead the operator doesn't want
- merge sequencing problems (the meta-PR could land before or after the originating story, decoupling rationale from change)
- review fragmentation (the meta-improvement's rationale lives in the originating story's `## Review`; splitting them across PRs hides the link)

**How to apply:**
- When `/modernize-rework` Step 3.5 surfaces apply-now meta-improvements, draft them as additional edits on the current story branch (e.g. `story/S-NNN-*`), include them in the rework commit, list them in the `## Review` annotations alongside per-finding decisions.
- If the originating story is already merged when the pattern is noticed, fold meta-improvements into the NEXT story's PR (track in [[pending-boyscout-followups]]).
- The frontmatter `rework_meta_prs:` field is misnamed for this workflow — use `rework_meta_changes:` or just list under `rework_address_now` as part of the story's edits.
- When prompting the operator about meta-improvements, the "Apply now" option means "fold into this PR", NOT "spin a new branch."
- When summarizing Step 3.5 outcomes to the user, don't enumerate "branches opened" — list the meta-changes as part of the story's rework deliverables.
