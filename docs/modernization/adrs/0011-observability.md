# 0011 — Observability stack

- **Status:** Accepted
- **Date:** 2026-05-14
- **Decision criteria** (from [vision §6](../02-vision-and-constraints.md#6-decision-criteria-for-phase-3)): Swiss/EU residency · solo-operator operability · lower TCO · mature ecosystem

## Context

The current system has NLog flat files only — no metrics, no traces, no error tracking, no remote-queryable logs ([current-state §6](../01-current-state.md#server)). [Vision §2 NFRs](../02-vision-and-constraints.md#2-non-functional-requirements) pin: structured JSON logs shipped to a queryable store; request count + latency histograms + error rate per endpoint; Sentry-equivalent error tracking. Hosting is a Swiss/EU VPS today, K8s mid/long-term ([ADR 0010](0010-hosting-and-deployment.md)).

The operator chose to self-host an OSS observability stack alongside the application. This keeps the residency story under our own control (everything in the same Swiss/EU VPS region) and avoids recurring SaaS fees at the small scale we're starting at.

## Options considered

### Option A — OSS self-hosted: Loki + Prometheus + Grafana + Sentry self-hosted
- **Capabilities:**
  - **Loki** for logs. Backend logs JSON-formatted via Logback (`logstash-logback-encoder` or Spring Boot's built-in JSON support); shipped by Promtail (or Alloy, the successor) to Loki; queryable via Grafana with LogQL.
  - **Prometheus** for metrics. Spring Boot Actuator + Micrometer (Prometheus registry) exposes `/actuator/prometheus`; Prometheus scrapes the backend, Postgres (postgres_exporter), Caddy/Traefik, and Keycloak.
  - **Grafana** for dashboards + alerting. Standard dashboards for JVM, HTTP, JDBC, Postgres; custom dashboards for business metrics (deliveries/day, flights/day, scheduled-job duration).
  - **Sentry** for unhandled-exception capture with stack traces, breadcrumbs, user/tenant context. Self-hosted Sentry is operationally heavy (multi-container, requires Redis/Postgres of its own); a lighter alternative is **GlitchTip** (Sentry-protocol-compatible, single-container, much simpler).
- **Fit to criteria:** Swiss/EU residency ✓ (everything runs in our own VPS). Operability ~ (more containers to operate; mitigated by including them in the same `docker-compose.yml` and treating their state as expendable — they're recovery-from-backup-acceptable, not transaction-critical). Lower TCO ✓ (no SaaS fees). Mature ecosystem ✓ (Loki/Prometheus/Grafana are the de facto OSS observability stack).
- **Migration cost:** medium — instrumentation is mostly annotation/dependency work in Spring Boot; the wiring is config-file plumbing.
- **Ecosystem risk:** low. All projects are widely used.
- **Escape hatch:** Prometheus + Loki are widely adopted; Grafana Cloud (EU region) and Sentry SaaS (EU) accept the same data formats — migrating to managed later is a config change. K8s migration ([ADR 0010](0010-hosting-and-deployment.md)) likely moves these to in-cluster or to managed.

### Option B — Grafana Cloud (EU) + Sentry SaaS (EU)
- **Why not chosen:** good fallback if self-hosting overhead becomes a problem; not chosen day-1 because the operator preferred data under their control and the OSS stack is free at our scale.

### Option C — Single managed tool (BetterStack / Datadog / Axiom)
- **Why not chosen:** US-vendor concerns for some of these; cost scales with ingest; less control over residency.

### Option D — OpenTelemetry-first
- **Capabilities:** instrument via OpenTelemetry, ship to any compatible backend.
- **Why not chosen as the framing:** still a valid approach inside Option A — we *should* prefer OpenTelemetry-compatible instrumentation libraries where Spring Boot offers a choice, so the wire-format isn't locked to Prometheus/Loki specifically. Capture this as a "use OTel-compatible instrumentation where the cost is the same" guideline, not a separate option.

## Decision

Chosen: **Option A — OSS self-hosted: Loki + Prometheus + Grafana + Sentry/GlitchTip**, all colocated with the application in `docker-compose.yml`. OpenTelemetry-compatible instrumentation preferred where Spring Boot gives a choice, so the wire format isn't locked to Prometheus/Loki specifically. Error tracking: **GlitchTip** as the day-1 lightweight Sentry-compatible option; can migrate to self-hosted Sentry or Sentry SaaS EU later if features outgrow it.

## Consequences

- **Positive:**
  - Full control over data residency — every byte of telemetry stays on the Swiss/EU VPS.
  - No recurring SaaS fees at the operator's current scale.
  - Structured JSON logs queryable via LogQL — solves the "can't query without SSH" pain from [vision §2 rationale](../02-vision-and-constraints.md#2-non-functional-requirements).
  - Spring Boot Actuator + Micrometer give the metrics surface essentially for free (one starter dependency).
  - Grafana alerting (Grafana 11+ unified alerting) covers the "page me when something breaks" need.
  - Stack moves cleanly into K8s when [ADR 0010](0010-hosting-and-deployment.md) phase 2 triggers.

- **Negative:**
  - More containers on the VPS — incremental memory (Loki ~256 MB, Prometheus ~256 MB, Grafana ~128 MB, GlitchTip ~512 MB with its Postgres). Budget ~1.5 GB extra. Plan the VPS sizing accordingly.
  - Operator owns observability infrastructure too — outages of the observability stack itself are possible. Mitigation: keep state minimal (Loki+Prometheus retention 7–30 days), accept that observability data is recoverable-but-not-critical, and avoid double-dependency (don't put alerting on the same VPS only — at minimum, an external uptime probe like Uptime Kuma or a SaaS like BetterUptime free tier should ping `/actuator/health` independently).
  - Some learning curve on LogQL, PromQL, and Grafana dashboards. Mitigation: the dashboards-as-code (Grafana provisioning JSON) approach lets us version-control them and start with community templates.

- **Follow-ups (other ADRs / stories implied):**
  - **Story:** add Loki, Prometheus, Grafana, GlitchTip (or Sentry) to `docker-compose.yml`; mount persistent volumes for retention.
  - **Story:** Spring Boot — add `spring-boot-starter-actuator`, `micrometer-registry-prometheus`, `logstash-logback-encoder` (or equivalent), `sentry-spring-boot-starter`. Wire all four.
  - **Story:** define the JSON log schema — at minimum: timestamp, level, logger, message, traceId, spanId, tenant (clubId from the security context), actor (user subject), request-id. Sensitive PII filtered.
  - **Story:** define the metric set — HTTP server metrics (default), JVM metrics (default), JDBC pool metrics, business metrics (deliveries-created/day, flights-validated/day, scheduled-job duration histogram).
  - **Story:** Grafana dashboards as code — provision via `grafana-dashboards/` folder + Grafana provisioning. Default dashboards: JVM, HTTP, Postgres, Business KPIs.
  - **Story:** alerts as code — Grafana alerting rules in YAML, version-controlled. Initial alerts: 5xx error rate > X%, p95 latency over budget, scheduled-job failure, disk space, certificate expiry.
  - **Story:** external uptime probe (independent of the VPS) — Uptime Kuma on a free tier or a separate small instance, alerts to email/Telegram/Slack.
  - **Story:** error-tracking integration — Sentry SDK in both Spring Boot and Angular; release tagging so errors are traceable to the deploy. Tenant + user context attached so a club admin's issue is debuggable.
  - **Story:** define retention policy — logs 7–14 days, metrics 14–30 days, errors 90 days. Tune later based on disk usage.
  - **Story:** instrument scheduled jobs ([ADR 0009](0009-background-job-mechanism.md)) with `started/completed/failed` events + duration histograms. Critical for the parity-sensitive delivery-creation job ([R3](../01-current-state.md#r3--accounting-rules-engine-parity-critical-customer-configurable)).
