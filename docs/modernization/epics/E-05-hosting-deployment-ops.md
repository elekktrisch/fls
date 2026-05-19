---
id: E-05
title: Hosting, deployment & ops
status: todo
adr_refs: [0010]
---

## Goal
Build the production-deploy story for the multi-tenant SaaS: production Docker image, `docker-compose.yml` for prod and dev, Caddy/Traefik reverse proxy with Let's Encrypt, production Keycloak, off-site backups to Swiss object storage, restore runbook, VPS provider chosen and provisioned. All artifacts must be K8s-ready (12-factor) so the planned mid-term K8s migration is a re-orchestration, not a rewrite.

## Scope
- In: `docker-compose.yml` (dev + prod), Dockerfile, reverse-proxy config, secrets handling (`.env` out-of-tree), TLS automation, production Keycloak deployment, off-site backup + rehearsed restore, K8s migration trigger criteria (written), Helm/Kustomize stub.
- Out: actual K8s migration (trigger-criteria-driven, no fixed timeline); CI/CD pipeline beyond build (lives within each app epic).

## Stories
- [ ] S-039 — `docker-compose.yml` skeleton (backend + Postgres + Keycloak + mailpit)
- [ ] S-040 — Production Dockerfile for the Spring Boot service
- [ ] S-041 — Reverse proxy: Caddy vs Traefik decision + config
- [ ] S-042 — Off-site `pg_dump` backup to Swiss object storage
- [ ] S-043 — Restore runbook + dry-run drill
- [ ] S-044 — VPS provider selection + provisioning
- [ ] S-045 — K8s-migration trigger criteria (written threshold)
- [ ] S-046 — Helm/Kustomize manifest stub mirroring compose topology
- [ ] S-151 — Production Keycloak deployment
- [ ] S-152 — Rename `alpenflight/` → `alpenflight/` working subtree

## Done when
- A clean VPS can be brought from zero to running production stack via documented steps in <2 hours.
- Daily backup runs, lands in Swiss-region object storage, and a quarterly drill restores it into a parallel VPS that passes T3 smoke (see S-100).
- The compose file passes a `K8s-readiness check`: every config via env, no host-path mounts, stdout/stderr logging, liveness+readiness endpoints, graceful shutdown, image digests pinned.
