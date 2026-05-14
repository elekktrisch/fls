---
id: S-039
title: docker-compose.yml skeleton (backend + Postgres + Keycloak + mailpit)
epic: E-05
status: todo
depends_on: []
acceptance:
  - `docker compose up` brings: Postgres 17, Keycloak, mailpit, and the backend service (built from `next/server/Dockerfile`) — though backend can be a placeholder image until S-001 produces a real one.
  - Compose file structure: separate dev (`docker-compose.yml`) and prod (`docker-compose.prod.yml` overrides) variants.
  - `.env.example` committed; `.env` gitignored.
  - Services have liveness/readiness depends_on with `condition: service_healthy`.
estimate: M
adr_refs: [0010]
parity_test: none
---

## Context
First deployment artifact. Required by basically every other story that touches integration testing.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Author the dev compose file.
- [ ] Author the prod overlay (different ports, volumes, secrets via env).
- [ ] Author `.env.example` with documented variables (DB password, Keycloak admin password, JWT issuer URI, etc.).
- [ ] Add healthchecks for each service.
- [ ] Document the workflow in `next/ops/README.md`.

## Notes
This story may precede S-001 in calendar time if a contributor wants to bring up the DB + IdP before writing app code — the dependency arrow in the graph is "no hard dep." But in _ORDER.md it sequences after foundational stories.
