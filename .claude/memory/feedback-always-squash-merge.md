---
name: feedback-always-squash-merge
description: "For this repo, always squash-merge PRs without asking — skip the merge-strategy question in /modernize-finalize"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 5c941d23-6332-4088-a757-9c95e2f2728c
---

In this repo (fls), **always squash-merge PRs**. Do not ask the operator about merge strategy.

**Why:** The modernize-* workflow's stated default is squash (one commit per story on `main`, scannable `git log main`). The operator has confirmed they want this every time; the merge-strategy question in `/modernize-finalize` was just ceremony for them.

**How to apply:**
- In `/modernize-finalize`: skip Step 2's `AskUserQuestion` entirely. Proceed directly to Step 2.5 bookkeeping → Step 3 squash-merge.
- Use `gh pr merge <N> --squash --delete-branch --subject "S-NNN: <title>" --body "Closes #<issue>"`.
- If the operator ever wants merge-commit or rebase, they'll say so explicitly; default stays squash.
- Outside `/modernize-finalize`: still respect the general "ask before risky shared-state actions" rule for unusual situations (force-push to main, large multi-PR consolidations).

**Origin:** operator instruction at S-011 finalize, 2026-05-16: "From now on, don't ask merge strategy for this repo. Always squash merge."

Related: [[fls-modernization-workflow]]
