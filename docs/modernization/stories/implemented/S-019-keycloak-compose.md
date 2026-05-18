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
reviewed_at: 2026-05-18T05:45
review_outcome: blockers
review_blockers: 2
review_improvements: 4
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


See `next/auth/README.md` for the operator manual, downstream consumer table, dev-vs-prod surface, and round-trip workflow.

## Review

<!-- modernize-review: start -->

**Reviewed:** 2026-05-18 (re-review after fix-blocker pass) · **PR:** #60 · **Outcome:** blockers

### Code quality

- **[blocker]** S-021 task line still cites the pre-rebrand issuer URL — `docs/modernization/stories/S-021-angular-oidc-client.md:26`. The line `configure against localhost:8080/realms/fls` contradicts both the ADR 0007 amendment in this PR and `next/auth/README.md`. An implementer picking up S-021 will configure the wrong host port + realm. **Fix:** update to `http://localhost:8090/realms/alpenflight`.
- **[blocker]** S-019 body "Proposed ADR amendment" section not retracted after the amendment was applied — `docs/modernization/stories/implemented/S-019-keycloak-compose.md:51-53`. Section still reads "Operator's call: amend now or…" but the ADR was amended in this very PR. Future reader sees an open proposal the file's review block already resolved. **Fix:** strike the section (or one-line note: "Applied in PR #60").
- **[improvement]** S-039 (implemented) carries the pre-rebrand realm name + port throughout — `docs/modernization/stories/implemented/S-039-docker-compose-skeleton.md:141,191,214,330,490`. Cites `localhost:8080/realms/fls`. Snippets would mislead anyone reconstructing topology from the story. **Fix (per Directive 1):** annotate the story header with a one-line "topology superseded by S-019 — see `next/auth/README.md`".
- **[improvement]** `check-realm-shape.sh:97-102` clubId-block trailing clause restates the `fail` message — already expressed in the assertion. **Fix:** trim the trailing clause; keep the first sentence (why we parse the nested JSON-string).
- **[improvement]** `check-realm-shape.sh:89` token-policy block comment says nothing the section header doesn't. **Fix:** drop the comment line.
- **[improvement]** README round-trip code-block comment is 3 lines of explanation at shell indent — `next/auth/README.md:89-91`. Mix of usage + rationale. **Fix:** condense to one line, e.g. `# down -v: wipe H2 volume so IGNORE_EXISTING doesn't hide the change.`

### Parity
**Oracle:** N/A — `parity_test: none` + no `flsserver/`/`flsweb/` paths in diff. S-019 is greenfield IdP setup; replaces the legacy `/Token` password grant entirely.

### Carried over from prior review (lineage)

- **13 improvements deferred → [S-153](../S-153-s019-rework-followups.md)** — `check-realm-shape.sh` coverage gaps, `export-realm.sh` hardening, Python-heredoc extraction, misc README/docker-compose touches. Full bullet list in S-153 AC checklist.
- **1 improvement accepted** — ADR 0007 issuer URL amendment was applied in PR #60 as a boyscout meta-improvement. Propagation completeness is one of the new blockers above.

### Cross-reviewer agreements

- Maintainability + security re-review both returned `(none)` — confirms the fix-blocker pass landed cleanly. New blockers are ADR-amendment-propagation gaps surfaced by tech-writer alone.

<!-- modernize-review: end -->
