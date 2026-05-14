---
id: S-031
title: Structured JSON logging + canonical log schema
epic: E-04
status: todo
depends_on: [S-001, S-020, S-022]
acceptance:
  - Logback configuration outputs JSON to stdout (one event per line).
  - The canonical schema includes: `timestamp`, `level`, `logger`, `message`, `traceId`, `spanId`, `tenantClubId`, `actorUserId`, `requestId`, plus any caller-supplied MDC fields.
  - Sensitive PII (passwords, JWTs, full Person rows) is filtered before logging via a Logback filter or redaction config.
  - A request-scoped filter populates the MDC with `traceId` + `requestId` + `tenantClubId` + `actorUserId` so every log line within a request carries them.
estimate: M
adr_refs: [0011]
parity_test: none
---

## Context
NFR — structured logs queryable via LogQL. The schema is the contract Loki + Grafana dashboards depend on.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Replace Spring Boot's default Logback config with one using `logstash-logback-encoder` (or Spring Boot's built-in JSON encoder if it's mature enough).
- [ ] Build the request filter that populates MDC.
- [ ] Add a Logback PII filter (deny-list known fields).
- [ ] Document the convention: don't log full request/response bodies; use `Marker` for opt-in.

## Notes
Stdout/stderr only — no log files. Compose collects and ships them (S-032). C12 (audit log) is a separate mechanism (S-027), don't conflate; audit goes to DB, logs go to Loki.
