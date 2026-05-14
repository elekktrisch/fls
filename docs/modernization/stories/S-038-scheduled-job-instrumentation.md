---
id: S-038
title: Scheduled-job instrumentation pattern
epic: E-04
status: todo
depends_on: [S-030, S-081]
acceptance:
  - A `@MeasuredJob` annotation or a shared base class wraps every `@Scheduled` method.
  - Each invocation emits: a `started` log event, a `completed` log event with duration, a `failed` log event with stack trace (also raises to Sentry/GlitchTip), and a Micrometer histogram `fls_job_duration_seconds{job=...}`.
  - The DeliveryCreationJob (parity-critical per R3) gets extra: per-flight counters, per-rule-application counters.
  - Grafana panel "Scheduled jobs — last 24h" renders started/completed/failed counts and median+p95 durations.
estimate: M
adr_refs: [0009, 0011]
parity_test: none
---

## Context
ADR 0009 + ADR 0011 follow-up. The pattern is established here once; every job ported in S-083..S-090 inherits it.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Build the annotation/AOP advice.
- [ ] Wire to Micrometer + structured logger.
- [ ] Add the dashboard panel.

## Notes
This story depends on S-081 (the scheduled-job infrastructure base story) since it instruments that base. Order-of-execution-wise this can fire in parallel with S-083+.
