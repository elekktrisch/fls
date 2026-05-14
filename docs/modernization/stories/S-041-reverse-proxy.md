---
id: S-041
title: Reverse proxy — Caddy vs Traefik decision + config
epic: E-05
status: todo
depends_on: [S-039]
acceptance:
  - Decision is committed (recommended: **Caddy** — simpler config, easier TLS automation, lower cognitive cost for a solo op; Traefik is the fallback if K8s migration is imminent).
  - The proxy routes: `/` → frontend static bundle, `/api/*` → backend, `/auth/*` → Keycloak.
  - TLS via Let's Encrypt is automated (Caddy: native; Traefik: cert resolvers).
  - HSTS, secure headers (CSP, X-Frame-Options, Referrer-Policy) configured.
  - The proxy is in `docker-compose.prod.yml`.
estimate: M
adr_refs: [0010]
parity_test: none
---

## Context
TLS-terminating reverse proxy. ADR 0010 deferred the choice.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Compare Caddy vs Traefik for our needs.
- [ ] Decide and document.
- [ ] Write the proxy config.
- [ ] Test TLS with a staging domain.

## Notes
CORS (R6) gets scoped on the backend side, not the proxy — Spring Security's CORS config carries the allow-list. Proxy is just routing + TLS + secure headers.
