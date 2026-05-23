---
name: feedback-ask-before-pushing
description: "Push freely by default; ask first only when `.github/workflows/e2e.yml` is part of the diff. Inside /modernize-implement push is also pre-authorized."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 6123186b-f3ba-41aa-96cb-265d116885af
---

Push freely by default in this repo. The earlier rule ("always ask
before `git push`") was relaxed by the user on 2026-05-15: commit and
push without asking *unless* the diff includes
`.github/workflows/e2e.yml` (or other CI workflow files that consume
runner minutes / trigger gh-pages deploys).

**Why:** The user finds the per-push prompt friction with no value for
normal code/doc changes — they can revert or re-push themselves. The
e2e workflow is the one exception they flagged: it kicks off a long,
expensive CI job and publishes to gh-pages, so they want to see the
change before it runs.

**How to apply:**

- Default: after a commit the user approved, just `git push`. No need
  to ask, no need to surface "should I push?"
- Before pushing, run a quick `git diff --name-only origin/<branch>..HEAD`
  (or check `git status` against what you just committed). If the diff
  touches `.github/workflows/e2e.yml`, **stop and ask first** — even if
  the user just authorized other commits in the same session.
- The same caution applies to anything under `.github/workflows/` that's
  expensive or visible (gh-pages, deploys, release builds). When
  unsure, ask.

**One scoped exception (operator-approved 2026-05-15):** within the
`/modernize-implement` skill, push is **pre-authorized at work-package
boundaries**. The skill commits per work-package, pushes the
locally-green slice, monitors CI via `gh run watch`, and continues. The
operator's invocation of `/modernize-implement` is the consent. This
predates the broader relaxation above and remains valid.

Linked: [[fls-modernization-workflow]]
