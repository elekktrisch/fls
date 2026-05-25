---
name: feedback-targeted-tests-not-full-suite
description: "Avoid running the full backend test suite during implement loops; use targeted `--tests 'pkg.*'` runs covering what changed."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 21c377e9-db57-4689-9f19-8c885e984805
---

When iterating on a story, avoid `./gradlew test` (full suite) — use targeted runs:
`./gradlew test --tests 'ch.alpenflight.flights.domain.*'` or specific class names.

**Why:** the full backend suite on this stack runs > 5 minutes (sandbox: each Spring context boot + Testcontainers Postgres + every IT). Running it on every iteration burns wall time and the operator's patience. The targeted slice covers what actually changed in 30-90s.

**How to apply:**
- During implement Steps 4-6 (iterate to green, before reviewer panel): only run the specific tests that exercise the changed code paths. Look at the diff, name the touched packages, glob those.
- One final pre-push targeted scope is enough — don't run the full suite "just to be safe."
- CI runs the full suite on push anyway; that's the safety net.
- The skill's Step 5.5 long-step wallclock tracking kicks in at 5 min — if a *targeted* run is hitting that, surface mitigation options. But don't burn 8-15 min on a full local run as the baseline.
- Related: see [[feedback-fe-tests-unit-for-logic-playwright-for-dom]] for the FE-side analogue (unit for logic, Playwright for DOM, don't run Playwright in tight loops).
