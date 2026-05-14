---
id: S-030
title: Spring Boot Actuator + Micrometer + Prometheus registry
epic: E-04
status: todo
depends_on: [S-001]
acceptance:
  - `/actuator/prometheus` returns Prometheus-format metrics.
  - Default JVM, HTTP server, JDBC pool, Hibernate stat metrics are exposed.
  - Custom-metric registration pattern is documented (worked example: a counter named `fls_demo_total`).
  - Sensitive Actuator endpoints (`env`, `configprops`) are restricted to admin role; health is public.
estimate: S
adr_refs: [0011]
parity_test: none
---

## Context
First step of the observability stack. Cheap to wire; foundational for everything downstream in E-04.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Add `spring-boot-starter-actuator`, `micrometer-registry-prometheus`.
- [ ] Configure `management.endpoints.web.exposure.include` for the safe-by-default set.
- [ ] Restrict admin endpoints via Spring Security.
- [ ] Add a worked-example custom counter and verify it appears in `/actuator/prometheus`.

## Notes
Actuator + Micrometer comes "free" with Spring Boot — this is mostly configuration, not code.
