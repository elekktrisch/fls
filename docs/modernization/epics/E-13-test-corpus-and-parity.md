---
id: E-13
title: Test corpus expansion & parity validation
status: todo
adr_refs: []
---

## Goal
Close the depth-coverage gap (R14): the legacy 34 Playwright specs are happy-path-only and cannot serve as a parity oracle. Expand the suite to cover validation rejection paths, state-machine illegal transitions, time-gate boundaries, permission boundaries, glider↔tow link integrity, multi-tenant isolation per endpoint, and the rules-engine combinations enumerated in R3 — *against the legacy system first* (because they are then re-run against the new system in CI as the parity oracle). Also: capture production performance baseline (top 5 routes p95).

This epic has unusual sequencing — its early stories (S-101..S-106) run *against the legacy system* before the new system can pass them. Done autonomously, in parallel with foundational + feature work.

## Scope
- In: Playwright depth expansion across 7 dimensions; rules-engine combinatorial corpus (≥1 per production `AccountingRuleFilter` combination per club, addresses C11); production performance baseline capture; T3-equivalent smoke against new stack; full Playwright suite port to new stack.
- Out: legacy code changes — expansion targets existing behavior, no fixes.

## Stories
- [ ] S-101 — Expand Playwright depth: validation rejection paths
- [ ] S-102 — Expand Playwright depth: state-machine illegal transitions + recovery paths
- [ ] S-103 — Expand Playwright depth: time-gate boundaries (1 second before/after each gate)
- [ ] S-104 — Expand Playwright depth: permission boundaries per endpoint
- [ ] S-105 — Expand Playwright depth: glider↔tow link integrity + cascade
- [ ] S-106 — Expand Playwright depth: multi-tenant isolation *per endpoint* (not sampled)
- [ ] S-107 — Rules-engine combinatorial corpus (≥1 per production rule-filter combination per club; C11)
- [ ] S-108 — Production performance baseline (top 5 routes p95 latency)
- [ ] S-109 — Port full Playwright suite to run against the new stack
- [ ] S-110 — T3-equivalent smoke against new stack (POST /Token-equivalent → GET /users/my → PUT a flight → re-read)
- [ ] S-111 — Performance verification (don't-regress check vs. S-108 baseline)

## Done when
- Expanded Playwright suite has zero red on the legacy system (proves the new tests are accurate to legacy behavior).
- All expanded specs pass against the new system.
- Rules-engine corpus produces zero-delta when run on both systems against the same flight inputs.
- New-system p95 latencies are ≤ legacy baselines on the top 5 routes (NFR — page load < 3s, API p95 < 500ms).
