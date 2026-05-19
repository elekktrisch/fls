# alpenflight/auth — Keycloak realm + dev IdP

The `alpenflight` Keycloak realm: committed source-of-truth, baked into a custom image, imported on first boot.

**Per [ADR 0007](../../docs/modernization/adrs/0007-auth-scheme.md)**: OIDC + OAuth2 protocol; self-hosted Keycloak for local dev; hosted IdP for production (vendor TBD).

## What ships here

| File | Purpose |
|---|---|
| `realm-export.json` | Source-of-truth realm shape. Three clients + seven realm roles + three seed users + `clubId` protocol mapper + ADR 0007 token policy. |
| `Dockerfile` | Bakes `realm-export.json` into a custom `alpenflight-keycloak:local` image. Used by the `keycloak` service in the root `docker-compose.yml`. |
| `scripts/export-realm.sh` | Re-export the realm from a running Keycloak. Writes to `realm-export.json`; `git diff` shows drift. |
| `scripts/normalize-realm-export.sh` | Deterministic-sorts the export. Strips volatile fields, dev-passwords-only injection, deep-sorts set-shaped arrays. |
| `scripts/check-realm-shape.sh` | CI / pre-commit guard. Asserts the load-bearing security invariants (PKCE-S256, bearer-only, no private key, etc.). |

## Bring up

```bash
# Standard: brings everything up via the wrapper.
bash alpenflight/ops/dev-up-full.sh

# Or just Keycloak:
docker compose -p alpenflight-dev up -d --wait keycloak

# Verify the realm is live:
curl -sS http://localhost:8090/realms/alpenflight/.well-known/openid-configuration | jq .issuer
# → "http://localhost:8090/realms/alpenflight"
```

After editing `realm-export.json`, rebuild the image:

```bash
docker compose -p alpenflight-dev down -v keycloak
docker compose -p alpenflight-dev build keycloak
docker compose -p alpenflight-dev up -d keycloak
```

## What's seeded

### Clients

| Client ID | Type | Flows | Notes |
|---|---|---|---|
| `alpenflight-web` | public | Authorization Code + PKCE-S256 | SPA. No direct-grants, no implicit. Redirect URIs: `http://localhost:{4200,3000}/*`. |
| `alpenflight-backend` | bearer-only | (token validator) | Spring Security 7 resource server (S-020 wires this in). |
| `alpenflight-proffix` | confidential | client-credentials only | Machine client. Service-account role `proffix-sync`. Dev secret `alpenflight-proffix-dev-secret` — rotate at deploy. |

### Realm roles

`SYSTEM_ADMINISTRATOR`, `CLUB_ADMINISTRATOR`, `FLIGHT_OPERATOR`, `PILOT`, `OFFICE_USER`, `GUEST` — mirror the legacy role catalog, consumed by S-026's `@PreAuthorize` mapping. Plus `proffix-sync` for the machine client.

### Seed users (dev only — passwords match usernames)

| Username | Roles | `clubId` |
|---|---|---|
| `sysadmin` | `SYSTEM_ADMINISTRATOR` | *(unset — cross-tenant)* |
| `clubadmin1` | `CLUB_ADMINISTRATOR`, `OFFICE_USER` | `club-1` |
| `pilot1` | `PILOT` | `club-1` |

All three: `emailVerified=true`, `locale="de"`, `@example.com` emails (RFC 2606 reserved test domain).

These are **dev fixtures**, not the cutover plan. Real-tenant bring-up lives in S-028 (single-tenant bulk-provision) and a higher-level cutover story (import N clubs × M users from a legacy FLS deployment at once).

### `clubId` protocol mapper

A realm-default client scope named `clubId` projects the `clubId` user-attribute as a `clubId` claim on both ID and access tokens (and userinfo). This is the **load-bearing hook** for S-022's `@TenantId` resolver.

**Caveat for S-022 design:** the claim is present on every Keycloak-native user, but federated users (e.g. Google OIDC at S-134) won't carry it — those flows resolve `clubId` from the local `user` table via `sub`/`email` lookup. Treating "no clubId" as automatically cross-tenant is wrong; the resolver needs a DB fallback.

## Topology — dual ports

| Endpoint | Host (browser / SPA / smoke) | Container (Spring on the compose network) |
|---|---|---|
| HTTP | `http://localhost:8090` | `http://keycloak:8080` |
| Management / health | `http://localhost:9090/health/ready` | `http://keycloak:9000/health/ready` |

The published issuer (`KC_HOSTNAME_URL`) is host-side: every token's `iss` claim is `http://localhost:8090/realms/alpenflight`, even when minted via the compose-internal listener.

**Gotcha for S-020:** Spring Security 7's `spring.security.oauth2.resourceserver.jwt.issuer-uri` does a discovery call AND validates the discovered `issuer` matches the configured URL. From inside the compose network, `issuer-uri=http://localhost:8090/...` is unreachable; `issuer-uri=http://keycloak:8080/...` succeeds at discovery but mismatches `iss`. Use the split config — `jwk-set-uri=http://keycloak:8080/realms/alpenflight/protocol/openid-connect/certs` (network) + `issuer-uri=http://localhost:8090/realms/alpenflight` for the `iss` validator (or `NimbusJwtDecoder` with explicit JWKS URI + a custom `OAuth2TokenValidator`).

## Round-trip workflow

```bash
# Edit the realm via the admin UI at http://localhost:8090 (admin/admin).
# Re-export. If git diff is non-empty, that's the intended drift.
bash alpenflight/auth/scripts/export-realm.sh
git diff alpenflight/auth/realm-export.json
# Rebuild the image AND wipe the H2 volume so the import re-runs cleanly.
# Without `down -v`, Keycloak's default IGNORE_EXISTING strategy silently
# preserves H2-resident entities and the rebuild appears not to take effect.
docker compose -p alpenflight-dev down -v keycloak
docker compose -p alpenflight-dev build keycloak
docker compose -p alpenflight-dev up -d keycloak
```

The committed export is bit-stable across round-trips (deep-sorted, no timestamps, no private keys, no auto-generated UUIDs in volatile positions).

## Dev-only surface (what changes for production)

- **Bootstrap admin (`admin`/`admin`)** — `KC_BOOTSTRAP_ADMIN_*` only seeds on a fresh H2 DB. Forbidden in prod; an operator must change before any non-localhost exposure.
- **Embedded H2** — fine for dev (single-process, single-realm, throwaway). Production uses Postgres via `KC_DB=postgres` + a managed `keycloak_db` schema. The realm-export.json is the source of truth — DB loss is recoverable by re-importing.
- **Plain HTTP** — `start-dev` + `sslRequired=external` allows plain HTTP on localhost. Production uses `start` (production mode) + TLS + `KC_HOSTNAME_URL=https://idp.example.com`.
- **Dev secrets** — `alpenflight-proffix-dev-secret` is dev-committed. Rotate at deploy.
- **Issuer URL** — host-pinned to `http://localhost:8090`. Production re-pins to the real public URL; downstream resource-server config must be env-driven (the same JSON works for both — only env differs).
- **Brute-force tuning** — Keycloak defaults (5 fails → 60s lockout, escalating). Production may want longer / permanent lockout.
- **Event log retention** — `jboss-logging` listener is dev-mode. Production extends with a forwarder (S-031) for centralized audit.

## Downstream consumers (what each story takes from here)

| Story | Consumes |
|---|---|
| S-020 Spring resource server | Issuer URL + JWKS URI (split config per the gotcha above); realm-role names |
| S-021 Angular OIDC client | Issuer URL + `clientId=alpenflight-web` + PKCE-S256 |
| S-022 `@TenantId` resolver | `clubId` claim (+ DB fallback for federated users) |
| S-026 `@PreAuthorize` mapping | Realm-role names → `ROLE_*` authorities |
| S-028 bulk-provision users | Admin REST API + `requiredActions: ["UPDATE_PASSWORD"]` flag (C14) |
| S-029 Proffix machine client | `clientId=alpenflight-proffix` + client-credentials grant + secret-rotation procedure |

## Mock-auth status (post-S-026)

The backend `MockSecurityConfig` chain was deleted by S-026 — the production OAuth2 resource server is the only filter chain. The SPA's `app.config.mock.ts` seam (still alive under the `mock-auth` angular.json configuration) is now a Playwright-CI / no-Keycloak dev convenience: it stamps `Authorization: Bearer mock-sysadmin` on `/api/v1/*` requests, which the live backend rejects with 401. The Playwright SPA suite stubs the backend via `page.route(...)` so the rejection never surfaces in test runs; accidental hits against a running backend fail loudly. The SPA seam re-rips when a real-OIDC Playwright project lands (S-021 follow-up).
