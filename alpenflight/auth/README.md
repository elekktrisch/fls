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
| `alpenflight-backend-admin` | confidential | client-credentials only | Backend → KC admin REST machine client (S-052). Service-account scoped to **`manage-users` + `view-users` + `query-users`** on `realm-management` only — NOT `manage-realm` / `manage-clients` / `impersonation`. Dev secret `alpenflight-backend-admin-dev-secret`; prod secret via `ALPENFLIGHT_KC_ADMIN_CLIENT_SECRET`. Rotate at deploy. |
| `alpenflight-proffix` | confidential | client-credentials only | Machine client. Service-account role `proffix-sync`. Dev secret `alpenflight-proffix-dev-secret` — rotate at deploy. |

### Realm roles

`SYSTEM_ADMINISTRATOR`, `CLUB_ADMINISTRATOR`, `FLIGHT_OPERATOR`, `PILOT`, `OFFICE_USER`, `GUEST` — mirror the legacy role catalog, consumed by S-026's `@PreAuthorize` mapping. Plus `proffix-sync` for the machine client.

### Seed users (dev only — password is `<username>-dev-2026!`)

| Username | Password | Roles | `clubId` |
|---|---|---|---|
| `sysadmin` | `sysadmin-dev-2026!` | `SYSTEM_ADMINISTRATOR` | *(unset — cross-tenant)* |
| `clubadmin1` | `clubadmin1-dev-2026!` | `CLUB_ADMINISTRATOR`, `OFFICE_USER` | `club-1` |
| `pilot1` | `pilot1-dev-2026!` | `PILOT` | `club-1` |

S-134 ships a realm `passwordPolicy="length(12) and notUsername and notEmail and specialChars(1)"`; the bare-username form (`sysadmin` / `clubadmin1` / `pilot1`) no longer satisfies it. `--import-realm` validates the seed-user credentials against the policy.

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
- **Dev secrets** — `alpenflight-proffix-dev-secret` + `alpenflight-backend-admin-dev-secret` are dev-committed (matches alpenflight-proffix precedent). Rotate both at deploy via env (`ALPENFLIGHT_KC_PROFFIX_CLIENT_SECRET` / `ALPENFLIGHT_KC_ADMIN_CLIENT_SECRET`); the committed export carries placeholders that `check-realm-shape.sh` asserts on so a real secret can't ride in unnoticed.
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
| S-028 bulk-provision users | `clientId=alpenflight-backend-admin` (S-052 wired it in) + `requiredActions: ["UPDATE_PASSWORD"]` flag (C14) |
| S-029 Proffix machine client | `clientId=alpenflight-proffix` + client-credentials grant + secret-rotation procedure |
| S-052 Users CRUD | `clientId=alpenflight-backend-admin` + `KeycloakAdminClient` typed façade in `users.infra.keycloak/` + admin-token caching with refresh-30s-before-expiry |
| S-134 self-service signup | `registrationAllowed=true`, `passwordPolicy`, `smtpServer`, `identityProviders[google]` (`trustEmail=false`, env-substituted `clientId`/`clientSecret`) |

## Self-service signup + Google IdP (S-134)

Self-service registration is on (`registrationAllowed=true`); the realm runs Keycloak's
stock `registration` + `first broker login` flows. Two surfaces light up:

- **Local signup** — the Keycloak login page shows a "Sign up" link; the SPA's
  `/signup` route deep-links into it via OIDC `prompt=create`.
- **Google federation** — `identityProviders[google]` is wired with
  `trustEmail=false` (the verify-mail challenge stays in the flow, closing the
  auto-link-to-unverified-local hijack vector). The SPA's `/signup` route deep-links
  via `kc_idp_hint=google`.

Both flavors hit `verifyEmail=true`, so first signup requires an inbox click;
subsequent logins do not re-verify (Keycloak flips `emailVerified=true` after
the first verification).

### Env vars

| Variable | Dev value | Prod value | Notes |
|---|---|---|---|
| `KEYCLOAK_GOOGLE_CLIENT_ID` | unset (button shows but errors on click) | from Google Cloud Console | OAuth 2.0 client ID, type "Web application". |
| `KEYCLOAK_GOOGLE_CLIENT_SECRET` | unset | from Google Cloud Console | Rotate at deploy. |
| `KEYCLOAK_SMTP_HOST` | `mailpit` (compose service) | real SMTP host | Required for verify-mail delivery. |
| `KEYCLOAK_SMTP_PORT` | `1025` | provider port | |
| `KEYCLOAK_SMTP_FROM` | `noreply@alpenflight.local` | `noreply@alpenflight.ch` | |
| `KEYCLOAK_SMTP_USER` | `""` | provider user | |
| `KEYCLOAK_SMTP_PASSWORD` | `""` | provider password | Never commit. |
| `KEYCLOAK_SMTP_AUTH` | `false` | `true` | |
| `KEYCLOAK_SMTP_STARTTLS` | `false` | `true` | |

Mailpit's web UI is at `http://localhost:8025` (compose). Outbound mail from
Keycloak lands there during local signup smokes — click the verify link to
complete the flow.

### Google Cloud Console — one-time setup (prod / per-developer)

1. Google Cloud Console → APIs & Services → Credentials → "Create credentials" → OAuth client ID.
2. Application type: **Web application**.
3. Authorized redirect URI: `${KEYCLOAK_PUBLIC_URL}/realms/alpenflight/broker/google/endpoint`
   — for local dev that's `http://localhost:8090/realms/alpenflight/broker/google/endpoint`.
4. Copy the generated client ID + secret into the env vars above.

Per `check-realm-shape.sh`: the committed `realm-export.json` MUST keep the
config values as `${env:KEYCLOAK_GOOGLE_CLIENT_*}` placeholders. A real secret
slipping into the file via a sloppy `export-realm.sh` round-trip fails CI loudly.

### Orphan KC users

Self-signup creates a Keycloak user (email + first/last name) BEFORE any tenant
exists in AlpenFlight. Cleanup of unverified-and-abandoned accounts is **deferred**:

- Per-IP rate-limit on `/login-actions/registration` → owned by **S-041** (reverse proxy).
- Nightly purge of `email_verified=false` users older than 14 d → owned by **S-038** (scheduled jobs) or new follow-up.

Right-to-deletion before first ingest: manual KC admin delete via
`http://localhost:8090/admin/master/console/#/alpenflight/users` (or the Admin REST
client S-052 already wired in). No app DB rows exist yet.

## Mock-auth status (post-S-026)

The backend `MockSecurityConfig` chain was deleted by S-026 — the production OAuth2 resource server is the only filter chain. The SPA's `app.config.mock.ts` seam (still alive under the `mock-auth` angular.json configuration) is now a Playwright-CI / no-Keycloak dev convenience: it stamps `Authorization: Bearer mock-sysadmin` on `/api/v1/*` requests, which the live backend rejects with 401. The Playwright SPA suite stubs the backend via `page.route(...)` so the rejection never surfaces in test runs; accidental hits against a running backend fail loudly. The SPA seam re-rips when a real-OIDC Playwright project lands (S-021 follow-up).
