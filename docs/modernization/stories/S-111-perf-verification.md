---
id: S-111
title: Performance verification (don't-regress check)
epic: E-13
status: todo
depends_on: [S-108, S-109]
acceptance:
  - The k6/vegeta script from S-108 runs against the new system.
  - p95 latencies on all 5 routes are ≤ legacy baseline (preferably better).
  - Where they're not, document why or fix.
  - Page-load p95 < 3s; API p95 < 500ms (NFR targets) — non-regression *and* absolute targets.
estimate: M
adr_refs: []
parity_test: none
---

## Context
NFR validation. Without it, the "don't regress" promise is theory.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Run the script against new system.
- [ ] Compare.
- [ ] Fix or document.

## Notes
Page-load measurement needs a real browser (Playwright trace or Lighthouse); API latency comes from the k6/vegeta side.
