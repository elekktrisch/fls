---
id: E-04
title: Observability foundation
status: todo
adr_refs: [0011]
---

## Goal
Stand up the self-hosted Loki + Prometheus + Grafana + GlitchTip stack alongside the application so that — by the time the first feature ships in E-06 — every request has a structured log line, every endpoint has request-count + latency + error-rate metrics, every unhandled exception lands in GlitchTip with tenant/actor context, and an external uptime probe pings `/actuator/health` independently of the VPS.

## Scope
- In: Actuator + Micrometer + Prometheus registry on the backend; logstash-logback-encoder for JSON logs; Loki + Promtail (or Grafana Alloy) + Grafana in compose; GlitchTip (Sentry-compatible) in compose; default dashboards (JVM/HTTP/JDBC/Postgres); alert rules (5xx rate, p95 latency, scheduled-job failures, disk, cert expiry); external uptime probe; scheduled-job instrumentation pattern.
- Out: business-KPI dashboards that depend on features not yet ported (those live in their feature epics and reference this epic's plumbing).

## Stories
- [ ] S-030 — Spring Boot Actuator + Micrometer + Prometheus registry
- [ ] S-031 — Structured JSON logging + canonical log schema
- [ ] S-032 — Loki + Promtail/Alloy + Grafana in docker-compose
- [ ] S-033 — Prometheus in docker-compose + scrape config
- [ ] S-034 — GlitchTip in compose + Spring + Angular SDK integration
- [ ] S-035 — Default Grafana dashboards (JVM, HTTP, JDBC, Postgres) as code
- [ ] S-036 — Alert rules as code (initial set)
- [ ] S-037 — External uptime probe (independent of VPS)
- [ ] S-038 — Scheduled-job instrumentation pattern (started/completed/failed + duration)

## Done when
- `/actuator/prometheus` is scraped; the four default dashboards render real data.
- A backend log line appears in Grafana → Loki within 5s, queryable by `traceId`, `tenant`, `actor`.
- A test exception thrown from any controller appears in GlitchTip within 1 min with tenant + actor context attached.
- The uptime probe pages a configured channel within 2 min of `/actuator/health` returning 503 (drill it).
