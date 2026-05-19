---
id: S-035
title: Default Grafana dashboards (JVM, HTTP, JDBC, Postgres) as code
epic: E-04
status: todo
depends_on: [S-032, S-033]
acceptance:
  - Dashboards are committed as JSON under `alpenflight/ops/grafana/dashboards/`.
  - Grafana provisioning auto-loads them on container start.
  - Four dashboards exist: JVM (heap, GC, threads), HTTP (request rate, p95/p99 latency, error rate per endpoint), JDBC pool (active/idle/wait), Postgres (connections, slow queries, table sizes).
estimate: M
adr_refs: [0011]
parity_test: none
---

## Context
Dashboards-as-code is the right discipline from day one — UI-edited dashboards drift and nobody trusts them.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Start from community Grafana dashboards (the JVM Micrometer dashboard, the standard Postgres dashboard) and customize panels to our metric names.
- [ ] Commit JSON; configure Grafana provisioning.
- [ ] Validate each dashboard renders real data.

## Notes
Business-KPI dashboards (deliveries/day, flights/day, scheduled-job duration) come later — in the relevant feature epics — because they depend on instrumenting code that doesn't exist yet.
