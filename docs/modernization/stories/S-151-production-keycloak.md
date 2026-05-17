---
id: S-151
title: Production Keycloak deployment
epic: E-05
status: todo
depends_on: [S-019]
acceptance:
  - Production Keycloak instance is deployed on the AlpenFlight production VPS, in Switzerland or the EU (C4).
  - Realm config mirrors the `next/auth/realm-export.json` from S-019 (signup enabled + Google IdP per S-134 + production client IDs/secrets).
  - HTTPS termination via the reverse proxy (S-041); JWKS endpoint reachable from the AlpenFlight backend.
  - Backup of the Keycloak DB is part of the off-site backup story (S-042).
  - The choice "self-hosted Keycloak vs. hosted alternative (Ory / Logto / Auth0)" is recorded with a one-paragraph rationale in `next/auth/prod-idp.md`.
estimate: M
adr_refs: [0007]
parity_test: none
---

## Context
ADR 0007 picks Keycloak as the local-dev IdP and leaves production deployment open. With AlpenFlight repositioned as a self-service SaaS (vision C25), production Keycloak is the multi-tenant identity surface for every customer — needs to be up before the first non-operator signup.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Vendor comparison + decision (recommend self-hosted Keycloak: zero MAU pricing, residency-clean, same image as dev).
- [ ] Provision Keycloak on the production VPS (compose service alongside the AlpenFlight backend).
- [ ] Import realm from S-019 export; configure production secrets via env.
- [ ] Wire reverse proxy + TLS.
- [ ] Backup integration.

## Notes
Self-hosted keeps the operator's vendor surface small. Hosted alternatives stay viable as a future migration if Keycloak's ops burden becomes painful.
