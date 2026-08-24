---
id: S-108
title: Production performance baseline (top 5 routes p95)
epic: E-13
status: todo
depends_on: []
acceptance:
  - p95 latency captured for the top 5 most-trafficked routes on the legacy system.
  - Methodology documented (how traffic was identified, how p95 was measured — synthetic probes or log analysis).
  - Baseline committed under `docs/modernization/perf-baseline.md`.
estimate: S
adr_refs: []
parity_test: none
---

## Context
NFR — "don't regress." Without a number, there's nothing to not-regress against.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Identify the 5 routes (from legacy NLog logs if request URLs are logged, or from operator knowledge).
- [ ] Run a synthetic measurement (e.g. k6 or vegeta against a representative tenant, 100 reqs per route).
- [ ] Record p95.
- [ ] Commit.

## Notes
Vision §8 open item. Schedule for early phase 4 / mid-modernization.
