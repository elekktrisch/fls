---
id: S-147
title: Funnel telemetry events end-to-end (landing → signup → demo → JAR → upload → trial → subscribed / deleted)
epic: E-15
status: todo
depends_on: [S-031, S-133, S-134, S-136, S-138, S-140, S-141, S-142, S-145, S-146]
acceptance:
  - A `FunnelEvent` schema captures: `event_id` (e.g. `landing.cta_click`), `actor_id` (Keycloak sub OR anon-session id), `deployment_id` + `club_id` (nullable until provisioning), `timestamp`, `properties` (event-specific JSON).
  - All E-15 stories that emit funnel events use a shared `FunnelTelemetry.emit(eventId, props)` helper.
  - Events are written as structured JSON log lines (via S-031's structured-logging pipeline). Optional: also published to a database table for ad-hoc operator queries (refine — storage cost vs. queryability).
  - Operator can run a dashboard query "from landing to trial-active, where do users drop off?" against the structured logs (via Loki / Grafana per S-032 / S-035) or against the table.
  - Event catalog documented in `alpenflight/docs/funnel-events.md`. Adding an event without updating the catalog fails a lint.
  - PII discipline: events never include email addresses, names, or raw IPs. Actor IDs are opaque; IPs bucketed to /24 (refine).
estimate: S
adr_refs: [0008]
parity_test: tests/telemetry/funnel-events.spec.ts (new — assert events fire in order during a synthetic full-funnel run)
---

## Context
Vision §4 soft preference (2026-05-17c) makes funnel telemetry first-class: O8 depends on the operator being able to ask "where did this user drop off?" without reading logs by hand. Without a unified event convention, each E-15 story would invent its own log shape and the dashboard would be unbuildable.

This story owns the convention + catalog; consuming stories emit events via the shared helper. The Deployment + Club IDs are first-class fields on every event so funnel analysis can group by Deployment without joining elsewhere.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] `FunnelEvent` DTO + `FunnelTelemetry.emit()` helper.
- [ ] SPA-side equivalent for client-side events (CTA clicks).
- [ ] Event catalog markdown + lint check.
- [ ] Loki / Grafana dashboard recipe (refine — depends on S-032 / S-035 cadence).
- [ ] PII review of every emit-site.

## Notes
- Story can land lazily — events added per-story as they're implemented. But the convention should ship with the first emitter (S-133) so all subsequent emits inherit the shape.
- Funnel events are additive to audit log: audit captures security-relevant mutations; funnel captures user-journey moments. They overlap on a few events but serve different consumers.
