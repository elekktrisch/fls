---
id: S-037
title: External uptime probe (independent of VPS)
epic: E-04
status: todo
depends_on: []
acceptance:
  - An uptime probe runs *outside* the production VPS (Uptime Kuma on a small separate host, or BetterUptime / UptimeRobot free tier).
  - Probe hits `/actuator/health` every 60s; alert fires on 2 consecutive failures.
  - Alert channel is distinct from the in-VPS Grafana alert channel — so if the VPS dies, the operator still gets paged.
  - Drill: power off the VPS for 2 min; confirm an alert lands within 3 min.
estimate: S
adr_refs: [0011]
parity_test: none
---

## Context
The "observability stack dies with the host" failure mode is real. External probe is the safety net.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Pick the host (a $3/mo separate VPS, or a free-tier SaaS).
- [ ] Configure the probe.
- [ ] Configure a separate notification channel.
- [ ] Drill.

## Notes
Cheapest is a free-tier SaaS (UptimeRobot free; BetterStack free) — the residency constraint applies less to a yes/no health probe than to data storage.
