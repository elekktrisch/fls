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
reviewed: true
reviewed_at: 2026-05-18
review_outcome: blockers
review_blockers: 1
review_improvements: 17
review_parity_oracle: N/A — parity_test=none + no flsserver/flsweb in diff
review_reviewers: [maintainability, security, tech-writer]
reworked: true
reworked_at: 2026-05-18
rework_followups: [S-153]
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

## Review

<!-- modernize-review: start -->

**Reviewed:** 2026-05-18 · **PR:** #60 · **Outcome:** blockers

### Maintainability

- **[in-rework] [blocker]** Token-policy CI guard asserts 1 of 5 ADR-0007 values — `next/auth/scripts/check-realm-shape.sh:90-92`. AC + ADR 0007 list `accessTokenLifespan=900`, `ssoSessionIdleTimeout=30d`, `ssoSessionMaxLifespan=90d`, `revokeRefreshToken=true`, `refreshTokenMaxReuse=0`; only the first is asserted. **Fix:** add jq asserts for the remaining 4 values. *(Cross-flagged by security + tech-writer.)*
- **[in-rework] [improvement]** `export-realm.sh` swallows non-2xx REST responses — `next/auth/scripts/export-realm.sh:37-48`. `curl -sS` without `--fail` writes 401/404 body into intermediate files. **Fix:** `--fail-with-body` on every curl + `jq -e type` on each file before merging.
- **[deferred → S-153] [improvement]** Per-user role-mapping loop re-fetches user IDs already cached — `next/auth/scripts/export-realm.sh:46-51`.
- **[deferred → S-153] [improvement]** `trap "rm -rf $WORK" EXIT` expands `$WORK` at install time — `next/auth/scripts/export-realm.sh:34`.
- **[deferred → S-153] [improvement]** Embedded Python in bash heredoc obstructs lint/editor tooling — `next/auth/scripts/normalize-realm-export.sh:24-101`.
- **[in-rework] [improvement]** README "Round-trip workflow" snippet omits `down -v` before rebuild — `next/auth/README.md:88-91`.
- **[deferred → S-153] [improvement]** Redirect-URI guard rejects only literal `"*"` — `next/auth/scripts/check-realm-shape.sh:80-82`.
- **[deferred → S-153] [improvement]** PII regex misses `.test` TLDs — `next/auth/scripts/check-realm-shape.sh:85`.
- **[deferred → S-153] [improvement]** Healthcheck depends on bash-only `/dev/tcp` in `CMD-SHELL` — `docker-compose.yml:206-208`.

### Security

- **[in-rework] [improvement]** `clubId` user-profile permission not asserted by CI guard — `next/auth/scripts/check-realm-shape.sh:67-72`. Tenant-escalation gate; if user-edit re-enabled and re-exported, guard misses it. **Fix:** add jq assertion on `kc.user.profile.config.attributes[clubId].permissions.edit == ["admin"]`.
- **[deferred → S-153] [improvement]** Proffix dev-secret value not pinned by CI guard — `next/auth/scripts/check-realm-shape.sh:44-48`.
- **[deferred → S-153] [improvement]** `webOrigins: ["+"]` widens CORS to every registered redirect URI's origin — `next/auth/realm-export.json` (alpenflight-web client).
- **[deferred → S-153] [improvement]** `export-realm.sh` admin token persists in shell env with no explicit revoke — `next/auth/scripts/export-realm.sh:25-30`.

### Code quality

- **[accepted: applied in this PR as a boyscout meta-improvement] [improvement]** ADR 0007 still cites `localhost:8080/realms/fls` — `docs/modernization/adrs/0007-auth-scheme.md` Option C.
- **[deferred → S-153] [improvement]** `for u in $(jq -r ...)` word-splits on usernames with spaces — `next/auth/scripts/export-realm.sh:46`.
- **[deferred → S-153] [improvement]** Bare `open()` calls in Python heredoc lack context managers — `next/auth/scripts/normalize-realm-export.sh:27-29`.
- **[deferred → S-153] [improvement]** README "Downstream consumers" table omits S-134 — `next/auth/README.md:110-115`.
- **[deferred → S-153] [improvement]** `registrationAllowed=false` guard conflicts with vision C26 (self-service signup) with no in-code comment — `next/auth/scripts/check-realm-shape.sh:95`.

### Parity
**Oracle:** N/A — `parity_test: none` + no `flsserver/`/`flsweb/` paths in diff. S-019 is greenfield IdP setup; replaces the legacy `/Token` password grant entirely.

### Cross-reviewer agreements

- **Token-policy CI guard incomplete** — maintainability + security + tech-writer all flagged. Promoted to blocker. **Highest signal.**
- **`trap "$WORK"` unquoted variable expansion** — maintainability + tech-writer.

<!-- modernize-review: end -->
