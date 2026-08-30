---
id: S-032
title: Loki + Promtail/Alloy + Grafana in docker-compose
epic: E-04
status: todo
depends_on: [S-031, S-039]
acceptance:
  - Loki, Promtail (or Grafana Alloy), and Grafana run via docker-compose.
  - Promtail ships container logs from the backend service to Loki.
  - Grafana is reachable on `localhost:3001` (or chosen port); a default admin user is seeded via env.
  - Loki retention is configured (default 14 days).
  - Persistent volumes are mounted so logs survive container restarts.
estimate: M
adr_refs: [0011]
parity_test: none
---

## Context
The log-storage half of E-04.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Add services to compose.
- [ ] Configure Promtail / Alloy with a container-log scrape config.
- [ ] Provision Grafana with a Loki datasource (Grafana provisioning files).
- [ ] Set retention.
- [ ] Mount volumes.

## Notes
Use Grafana Alloy (the successor to Promtail) for forward-compatibility — it's the project Grafana is steering toward.
