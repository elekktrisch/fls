---
id: S-019
title: Keycloak in docker-compose + realm export committed
epic: E-03
status: done
started_at: 2026-05-18
done_at: 2026-05-18
github_issue: 59
github_pr: 60
depends_on: []
acceptance:
  - `docker compose -p alpenflight-dev up -d keycloak` brings Keycloak online at `http://localhost:8090/realms/alpenflight` (host HTTP 8090 → container 8080; management 9090 → 9000).
  - A pre-seeded realm `alpenflight` is committed under `next/auth/realm-export.json` and imported on first boot via `--import-realm`. Distribution is via a baked `alpenflight-keycloak:local` image (built from `next/auth/Dockerfile`) because Docker Desktop's bind-mount for single files is unreliable on Windows hosts.
  - Pre-seeded entities: `alpenflight-web` SPA client (public, PKCE-S256, no direct-access-grants), `alpenflight-backend` resource-server client (bearer-only), `alpenflight-proffix` machine client (client-credentials, service-accounts-only) with dev secret `alpenflight-proffix-dev-secret`; one system-admin / one club-admin / one pilot user.
  - The export round-trips: `next/auth/scripts/export-realm.sh` rewrites `realm-export.json` from a live Keycloak; second-iteration diff is zero.
  - Realm carries a User Attribute `clubId` and a Protocol Mapper that projects it as a `clubId` claim (string) on both ID and access tokens — the load-bearing hook for S-022's `@TenantId` resolver.
  - Realm pins ADR 0007 token policy: `accessTokenLifespan=900s`, `ssoSessionIdleTimeout=30d`, `ssoSessionMaxLifespan=90d`, `revokeRefreshToken=true`, `refreshTokenMaxReuse=0`.
  - Realm pins security hygiene: `registrationAllowed=false`, `bruteForceProtected=true`, `eventsEnabled=true`, `adminEventsEnabled=true`.
  - Committed export contains no private signing key (`keys[].privateKey` absent) — enforced by `next/auth/scripts/check-realm-shape.sh` (CI guard `next-auth-realm-shape` in `.github/workflows/ci.yml`).
  - `next/auth/README.md` enumerates the dev-only surface, dual-port topology, issuer URL contract, round-trip workflow, downstream consumers.
estimate: M
adr_refs: [0007]
parity_test: none
refined: true
refined_at: 2026-05-17
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
---

## Context

The dev-loop foundation for the auth chain. Produces an OIDC issuer at `http://localhost:8090/realms/alpenflight` with a realm shape that downstream stories pin against: S-020 (Spring resource server), S-021 (Angular OIDC client), S-022 (`@TenantId` resolver), S-028 (bulk-provision tenant users), S-029 (Proffix machine client), S-134 (self-service signup), S-151 (production Keycloak deployment). When the S-019 + S-020 + S-022 bundle lands, S-048's `mock-auth` seam deletes in one commit.

## Load-bearing decisions (not visible from code alone)

- **`clubId` claim is the fast path, not the only path.** S-022's `@TenantId` resolver must fall back to a DB lookup by `sub`/`email` for federated users (Google IdP at S-134) and legacy-imported users — their JWT won't carry `clubId`. Treating "no claim = cross-tenant" is wrong.
- **Dual-port `iss` contract.** S-020 must split `jwk-set-uri` (compose-network `http://keycloak:8080`, where Spring actually reaches Keycloak) from `issuer-uri` (host-side `http://localhost:8090`, baked into every token's `iss` claim). README documents the gotcha; Spring's default discovery-and-validate path picks one URL and fails on the other.
- **Dev-only seed users are fixtures, not the cutover plan.** The 3 users here exist to make local smokes work. Real bring-up imports N clubs × M users (incl. N+ `CLUB_ADMINISTRATOR` rows) at once from a legacy FLS deployment — that's S-028 + a higher-level cutover story.
- **Bake instead of bind-mount.** Docker Desktop on Windows refuses to bind-mount the realm-export.json reliably (file-sharing limitation). Same pattern as `next/ops/pgadmin/`. Rebuild required after edits — documented in README.
- **REST export instead of `kc.sh export`.** Offline export locks the H2 file the running container holds. Online REST (`partial-export` + users + role-mappings) is the only path against a live server; round-trip stays zero-diff thanks to the deep-sort normalizer.

## Proposed ADR amendment (operator decides)

**ADR 0007 still cites `localhost:8080/realms/fls`.** Update to `http://localhost:8090/realms/alpenflight` (host port 8090 because the AlpenFlight backend owns 8080; realm renamed during the rebrand). Operator's call: amend now or batch with the next ADR-touching story.

See `next/auth/README.md` for the operator manual, downstream consumer table, dev-vs-prod surface, and round-trip workflow.
