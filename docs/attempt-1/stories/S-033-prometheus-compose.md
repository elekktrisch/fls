---
id: S-033
title: Prometheus in docker-compose + scrape config
epic: E-04
status: todo
depends_on: [S-030, S-032, S-039]
acceptance:
  - Prometheus runs via docker-compose with a `prometheus.yml` scrape config that targets: the backend (`/actuator/prometheus`), postgres_exporter, Caddy/Traefik metrics, Keycloak (if metrics endpoint is enabled).
  - Prometheus is provisioned as a datasource in Grafana.
  - Persistent volume for the TSDB; retention configured (default 30 days).
  - A "FLS Backend" Grafana dashboard renders HTTP request rate + p95 latency from scraped metrics.
estimate: M
adr_refs: [0011]
parity_test: none
---

## Context
The metrics half of E-04. Pairs with S-032's log half.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Add Prometheus to compose.
- [ ] Add postgres_exporter for DB metrics.
- [ ] Write `prometheus.yml` scrape config.
- [ ] Provision in Grafana.
- [ ] Set retention + volume.

## Notes
postgres_exporter goes here, not in E-02 — the exporter is observability, the DB itself is data.
