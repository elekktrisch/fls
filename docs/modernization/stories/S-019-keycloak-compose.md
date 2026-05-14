---
id: S-019
title: Keycloak in docker-compose + realm export committed
epic: E-03
status: todo
depends_on: []
acceptance:
  - `docker compose up` brings Keycloak online at `localhost:8080/realms/fls`.
  - A pre-seeded realm `fls` is committed under `next/auth/realm-export.json` and imported on first boot.
  - Pre-seeded entities: an `fls-web` SPA client (public, PKCE), an `fls-backend` resource-server client, an `fls-proffix` machine client (client-credentials), one admin user, one club-admin user, one regular pilot user — for dev/test.
  - The export round-trips: export from a running Keycloak overwrites the committed JSON without losing seed entities.
estimate: M
adr_refs: [0007]
parity_test: none
---

## Context
ADR 0007 chose Keycloak for local dev. This story builds the dev-loop foundation.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Add `keycloak` service to `docker-compose.yml` (image: `quay.io/keycloak/keycloak:latest` or pinned version).
- [ ] Configure import-on-start with `--import-realm`.
- [ ] Build the seed realm: clients, users, roles. Use the Keycloak admin UI to bootstrap, then export.
- [ ] Commit `realm-export.json` to `next/auth/`.
- [ ] Document the dev workflow: change in UI → `kc.sh export` → commit JSON.

## Notes
The Keycloak admin DB (its own internal Postgres or h2) is treated as ephemeral state; the realm-export.json is the source of truth.

The dev SPA client must allow `http://localhost:4200` and `http://localhost:3000` as redirect URIs.
