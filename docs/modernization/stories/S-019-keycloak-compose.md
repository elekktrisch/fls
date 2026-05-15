---
id: S-019
title: Keycloak in docker-compose + realm export committed
epic: E-03
status: todo
depends_on: []
acceptance:
  - `docker compose up` brings Keycloak online at `localhost:8080/realms/fls`.
  - A pre-seeded realm `fls` is committed under `next/auth/realm-export.json` and imported on first boot.
  - Pre-seeded entities: an `fls-web` SPA client (public, PKCE-S256, no direct-access-grants), an `fls-backend` resource-server client (bearer-only), an `fls-proffix` machine client (client-credentials, service-accounts-only), one system-admin user, one club-admin user, one pilot user — for dev/test.
  - The export round-trips: export from a running Keycloak, normalize, diff against committed JSON exits 0 (modulo timestamps and pinned-elsewhere IDs).
  - Realm carries a User Attribute `clubId` and a Protocol Mapper that projects it as a `clubId` claim (string) on both ID and access tokens — the load-bearing hook for S-022's `@TenantId` resolver.
  - Realm pins ADR 0007 token policy: `accessTokenLifespan=900s`, `ssoSessionIdleTimeout=30d`, `ssoSessionMaxLifespan=90d`, `revokeRefreshToken=true`, `refreshTokenMaxReuse=0`.
  - Realm pins security hygiene: `registrationAllowed=false`, `bruteForceProtected=true`, `eventsEnabled=true`, `adminEventsEnabled=true` with `adminEventsDetailsEnabled=true`.
  - Committed export contains no private signing key (`keys[].privateKey` absent) — verified by CI guard.
  - `next/auth/README.md` enumerates the dev-only surface (bootstrap admin creds, embedded H2, plain HTTP, dev passwords) and what must be replaced for production.
estimate: M
adr_refs: [0007]
parity_test: none
refined: true
refined_at: 2026-05-15
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
refined_speculative: true
refined_speculative_at: 2026-05-15
---

## Context
ADR 0007 chose Keycloak for local dev. This story builds the dev-loop foundation: a one-command `docker compose up` produces a real OIDC issuer at `localhost:8080/realms/fls` with a realm shape that downstream stories (S-020 resource server, S-021 Angular OIDC client, S-022 `@TenantId` resolver, S-028 cutover user import, S-029 Proffix machine client) can pin against.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Build the seed realm interactively in the Keycloak admin UI (run `quay.io/keycloak/keycloak:26.5` in `start-dev` mode), then export with `kc.sh export --realm fls --file /tmp/realm-export.json --users realm_file`.
- [ ] Commit `next/auth/realm-export.json` (the single-file export — see Design notes for alternative considered).
- [ ] Write `next/auth/scripts/normalize-realm-export.sh` (jq-based: strip `createdTimestamp`, `notBefore`, regenerated component IDs; `--sort-keys`; pin client + user `id` fields in the source so round-trip diff is genuinely zero).
- [ ] Write `next/auth/scripts/export-realm.sh` (orchestrates: `compose exec` → `compose cp` → `normalize` → write to committed path).
- [ ] Pin Keycloak image tag in the Compose contract handed to S-039 (no `:latest`; pin a minor version).
- [ ] Author `next/auth/README.md` per the Security plan (dev-only callouts, round-trip workflow, what's seeded, claim contract, dev-vs-prod gap).
- [ ] Hand the Compose service contract verbatim to S-039 (Design notes §"Compose contract handed to S-039").
- [ ] Wire CI guards: no-private-key check, no-real-PII check, PKCE-S256 enforced, direct-access-grants disabled, round-trip diff is zero.

<!-- modernize-refine: start -->

## Design notes

### Module layout

- **`next/auth/realm-export.json`** — committed single-file realm export; source of truth for the `fls` realm (clients, roles, seed users, protocol mappers, realm settings). Mounted read-only into the container at `/opt/keycloak/data/import/realm-export.json`; consumed by `kc.sh start-dev --import-realm`.
- **`next/auth/README.md`** — operator guide: what's seeded (clients + roles + users table), round-trip export recipe, dev-vs-prod gap (embedded H2 vs. Postgres, `KC_HTTP_ENABLED=true` is dev-only, bootstrap admin creds are dev-only, signing-key generation, hostname mode), issuer URL contract (`http://localhost:8080/realms/fls`), claim contract (`clubId` is load-bearing for tenancy — do not rename without lockstep updates to S-019, S-020, S-022, S-025).
- **`next/auth/scripts/normalize-realm-export.sh`** — jq-based post-processor invoked after every export. Strips volatile fields (timestamps, regenerated UUIDs for components Keycloak owns), sorts keys deterministically. Documented in the README.
- **`next/auth/scripts/export-realm.sh`** — three-line wrapper around `compose exec`, `compose cp`, and the normalize script.
- **No code in `next/server/` or `next/web/` this story.**
- **No Compose changes this story.** S-039 owns `docker-compose.yml`. This story produces the **Compose contract** (below) that S-039 wires verbatim.

### Realm shape (`realm-export.json`)

**Realm `fls`** (top-level settings):

| Field | Value | Rationale |
|---|---|---|
| `realm` | `"fls"` | — |
| `displayName` | `"FLS"` | — |
| `enabled` | `true` | — |
| `registrationAllowed` | `false` | C14 — users land via cutover (S-028) or admin UI; no public signup pre-prod. |
| `resetPasswordAllowed` | `true` | C14 — forced password reset at cutover. |
| `rememberMe` | `true` | UX. |
| `verifyEmail` | `true` | Production posture; in dev, seed users carry `emailVerified=true`. |
| `editUsernameAllowed` | `false` | Stable subject claims. |
| `sslRequired` | `"external"` | Dev `localhost` plain HTTP allowed; external hosts get TLS. |
| `accessTokenLifespan` | `900` (15 min) | ADR 0007. |
| `ssoSessionIdleTimeout` | `2592000` (30 days) | ADR 0007 refresh idle. |
| `ssoSessionMaxLifespan` | `7776000` (90 days) | ADR 0007 refresh absolute. |
| `revokeRefreshToken` | `true` | Rotation. |
| `refreshTokenMaxReuse` | `0` | Reject reuse → forces single-use refresh. |
| `bruteForceProtected` | `true`, defaults (5 fails → 60s lockout, escalating to 900s; non-permanent) | A07 mitigation. |
| `internationalizationEnabled` | `true`; supported `["de","fr","it","en"]`; default `"de"` | C4 Swiss/EU locales. |
| `eventsEnabled` | `true`, `enabledEventTypes` covers `LOGIN`, `LOGIN_ERROR`, `LOGOUT`, `REFRESH_TOKEN`, `REFRESH_TOKEN_ERROR`, `RESET_PASSWORD`, `UPDATE_PASSWORD`, `SEND_RESET_PASSWORD`, `CLIENT_LOGIN`, `CLIENT_LOGIN_ERROR` | A09 + forensic trail; S-031 forwards. |
| `adminEventsEnabled` / `adminEventsDetailsEnabled` | both `true` | Before/after for IdP config changes. |
| `eventsListeners` | `["jboss-logging"]` | Dev-mode listener; production extends in S-031. |

**Clients** (exactly three):

| Client ID | Type | Auth | Flows | Redirect URIs | Notes |
|---|---|---|---|---|---|
| `fls-web` | public | none (PKCE-S256 required) | standardFlow ✓, directAccessGrants ✗, implicit ✗, serviceAccounts ✗ | `http://localhost:4200/*`, `http://localhost:3000/*` | `attributes."pkce.code.challenge.method" = "S256"`; `webOrigins = ["+"]`; `postLogoutRedirectUris = ["+"]`. |
| `fls-backend` | bearer-only | none | none (token validator) | none | `bearerOnly: true`. Spring resource server validates against JWKS; no client credentials needed. |
| `fls-proffix` | confidential | client-secret | serviceAccounts ✓, standardFlow ✗, directAccessGrants ✗ | none | Machine client. Dev secret committed (`fls-proffix-dev-secret`) — README marks dev-only; rotated at deploy. Service-account user granted realm role `proffix-sync` (minimal scope); scope catalog firms in S-029. |

**Realm roles** (mirror the legacy role catalog so S-026 can map `@PreAuthorize`):

`SYSTEM_ADMINISTRATOR`, `CLUB_ADMINISTRATOR`, `FLIGHT_OPERATOR`, `PILOT`, `OFFICE_USER`, `GUEST`, plus the machine-only `proffix-sync` for the Proffix client.

Roles are realm roles (not client roles) so they surface on the token as `realm_access.roles[]` per Keycloak default. S-020's `JwtAuthenticationConverter` maps `ROLE_*` from these names.

**Seed users** (dev-only; passwords explicitly dev-only in README):

| Username | Password | Realm roles | `clubId` attribute | Email |
|---|---|---|---|---|
| `sysadmin` | `sysadmin` | `SYSTEM_ADMINISTRATOR` | *(unset — cross-tenant principal)* | `sysadmin@example.com` |
| `clubadmin1` | `clubadmin1` | `CLUB_ADMINISTRATOR`, `OFFICE_USER` | `club-1` | `clubadmin1@example.com` |
| `pilot1` | `pilot1` | `PILOT` | `club-1` | `pilot1@example.com` |

All three: `emailVerified=true`, `enabled=true`, `requiredActions=[]` (no forced reset in dev), `locale="de"`. **Pin user `id` UUIDs in the export** so downstream test fixtures can hard-reference subject IDs.

**Custom protocol mapper — the structural-multi-tenancy hook for S-022:**

- Type: `oidc-usermodel-attribute-mapper`
- User attribute: `clubId`
- Token claim name: `clubId`
- Claim type: `String`
- Add to ID token: `true`
- Add to access token: `true`
- Add to userinfo: `true`
- Scope: realm-default client scope (applies to all clients without per-client opt-in).
- The `account` client must NOT whitelist `clubId` as a user-editable attribute (Keycloak account console exposes only whitelisted attrs).

### Compose contract handed to S-039

Pinned verbatim — S-039 wires, does not decide:

```yaml
keycloak:
  image: quay.io/keycloak/keycloak:26.5@sha256:<pin-at-S-039-time>
  command: ["start-dev", "--import-realm"]
  environment:
    KC_BOOTSTRAP_ADMIN_USERNAME: admin
    KC_BOOTSTRAP_ADMIN_PASSWORD: admin          # dev-only — see README
    KC_HOSTNAME: localhost
    KC_HTTP_ENABLED: "true"                     # dev-only
    KC_HEALTH_ENABLED: "false"                  # dev-mode has no /health endpoint anyway
    KC_LOG_LEVEL: INFO
  volumes:
    - ./next/auth/realm-export.json:/opt/keycloak/data/import/realm-export.json:ro
  ports:
    - "8080:8080"
  healthcheck:
    test: ["CMD-SHELL", "curl -fsS http://localhost:8080/realms/fls || exit 1"]
    interval: 10s
    timeout: 3s
    retries: 6
    start_period: 30s
  profiles: [dev]
```

- `depends_on`: none — embedded H2 in dev-mode.
- Read-only mount on the import path — round-trip export uses a separate one-shot command (see below) so the container can't accidentally rewrite the source-of-truth file.
- `profiles: [dev]` gates the bootstrap-admin block and the `start-dev`/`--import-realm` flag — production needs a fundamentally different shape (see "Out of scope").
- Image digest pin per ADR 0010 hygiene rule 9; S-039 resolves the digest at compose-write time.

### Round-trip export procedure

```bash
docker compose exec keycloak \
  /opt/keycloak/bin/kc.sh export \
  --realm fls \
  --file /tmp/realm-export.json \
  --users realm_file

docker compose cp keycloak:/tmp/realm-export.json ./next/auth/realm-export.raw.json

./next/auth/scripts/normalize-realm-export.sh \
  ./next/auth/realm-export.raw.json \
  > ./next/auth/realm-export.json

git diff ./next/auth/realm-export.json
```

`normalize-realm-export.sh` strips: `createdTimestamp`, `notBefore`, regenerated component IDs for entities where Keycloak owns the UUID; sorts arrays with non-deterministic emit order (`defaultDefaultClientScopes`, `defaultOptionalClientScopes`, `requiredActions`); `jq --sort-keys`. Pin client + user `id` fields in the source so the diff is genuinely zero.

### Integration with downstream stories

| Downstream | What it consumes from S-019 |
|---|---|
| S-020 (Spring Security 7 resource server) | Issuer URL `http://localhost:8080/realms/fls`; JWKS via discovery; realm-role names. |
| S-021 (Angular OIDC client) | Same issuer; `clientId="fls-web"`; PKCE flow; redirect URIs whitelisted. |
| S-022 (`@TenantId` resolver) | Claim name `clubId` (String); absence-of-claim + `SYSTEM_ADMINISTRATOR` role = cross-tenant principal. |
| S-025 (tenant-from-URL + authorization) | Same role catalog and claim contract. |
| S-026 (`@PreAuthorize` mapping) | Realm-role names → `ROLE_*` authorities. |
| S-028 (cutover user export-and-import) | Admin REST API at `/admin/realms/fls`; bootstrap admin grant; `requiredActions: ["UPDATE_PASSWORD"]` flag for C14. |
| S-029 (Proffix machine client) | Client ID `fls-proffix`; client-credentials grant; secret rotation procedure. |
| S-039 (docker-compose orchestration) | The Compose contract above, verbatim. |

### Alternatives considered

- **Option A (chosen):** Keycloak 26.5 + single-file `realm-export.json` + dev-mode embedded H2. Mature, ADR 0007-mandated, one-command bring-up, reviewable diffs.
- **Option B (rejected):** Authentik. ADR 0007 left it on the table; rejected because Keycloak's realm-as-JSON export/import is more mature and Spring + Keycloak inertia is heavier. Future swap is a config change (OIDC is OIDC), not a rewrite.
- **Option C (rejected for now):** Directory-split export (`kc.sh export --dir`). Smaller per-file diffs, easier merges, but ~12 files to keep mentally aligned. Trigger to revisit: single file > ~1 MB or recurring merge conflicts on `realm-export.json`.
- **Option D (rejected for dev, deferred to prod):** Postgres-backed Keycloak (`KC_DB=postgres`). Adds a container + healthcheck dependency to the dev `compose up`. Embedded H2 matches "one command up"; realm export is the source of truth so DB-loss is recoverable. README flags as the prod switch (ADR 0010 territory).
- **Option E (rejected):** Spring Authorization Server (ADR 0007 Option A) — already settled.

## Edge cases & hidden requirements

- **`data/import/` empty on first boot:** Keycloak comes up with only the master realm; `/realms/fls` 404s. Compose healthcheck (above) catches this — `compose up --wait` fails fast.
- **Subsequent boots:** `--import-realm` re-imports on every start. With Keycloak 26.5's default `IGNORE_EXISTING` strategy, dev edits to existing entities are preserved; new entities are still added. **Pick `OVERWRITE_EXISTING` for predictability if the realm export is the source of truth** — flag in README which mode is active and the tradeoff. Recommended: stay on default (`IGNORE_EXISTING`) but document that committed-realm drift is recovered by `docker compose down -v` (wipe H2 volume).
- **Bootstrap admin idempotency:** `KC_BOOTSTRAP_ADMIN_*` only seeds on a fresh DB. Re-running with a different password silently no-ops — document "delete the H2 volume to re-bootstrap admin."
- **Persistent volume vs. throwaway DB:** No named volume by default (embedded H2 file lives inside the container layer). `docker compose down` (without `-v` flag) preserves; `docker compose down -v` wipes. Document.
- **Realm export round-trip drift:** `kc.sh export` emits new timestamps + UUIDs + re-orders arrays. The normalize script + pinned IDs (clients, users, mappers) close this. Without the normalize script, the round-trip AC is unverifiable.
- **Round-trip export from a running vs. stopped container:** Keycloak 26 supports both. Pin the running-instance path (`docker compose exec`) — it's the dev-flow operators actually use.
- **Writeable bind-mount on Docker Desktop:** macOS/Windows surface permission quirks on shared paths; the round-trip flow uses `docker compose cp` (container → host) which bypasses bind-mount semantics. Read-only bind-mount on the import side avoids accidental container-side writes.
- **Redirect URI `localhost` vs. `127.0.0.1` vs. tunnel host:** the whitelist explicitly lists `http://localhost:{4200,3000}/*` only. Devs using `127.0.0.1` or a tunnel must add their URI to the export and re-import (or commit it).
- **PKCE S256 must be explicit:** `attributes."pkce.code.challenge.method" = "S256"` on the `fls-web` client. Not setting it means PKCE is *allowed* but not *required* — fails the SPA's threat model.
- **Issuer URL stability dev↔prod:** local issuer is `http://localhost:8080/realms/fls`; production issuer differs. The `iss` claim is baked into every token. Downstream resource-server config must be env-driven (flag for S-020).
- **Stable subject (`sub`) IDs:** pin `id` for all three seed users in the export so fixtures referencing them by `sub` remain valid across re-imports.
- **`emailVerified=true` on seed users:** without this, OIDC login forces a verification screen → breaks every smoke test.
- **Locale on seed users:** `locale="de"` so Keycloak-rendered login/reset/error pages default to German (matches C4 Swiss-first posture); also matters when S-082 renders email templates.
- **Service-account user for `fls-proffix`:** client-credentials clients have an implicit service-account user. Its realm-role mapping (`proffix-sync`) must be in the export — easy to forget; would break the Proffix smoke later.
- **Port 8080 collision:** common dev clash. Compose host-port mapping uses `"${KEYCLOAK_PORT:-8080}:8080"` so an operator can override without editing the file.
- **Keycloak image tag:** pin to `quay.io/keycloak/keycloak:26.5` (not `:latest`). Realm export JSON schema is unstable across majors; a silent bump can break the import.
- **Healthcheck endpoint:** dev-mode does NOT expose `/health/ready` (production mode does with `KC_HEALTH_ENABLED=true`). The Compose healthcheck above curls the realm root instead.
- **README contents:** must enumerate what's dev-only (bootstrap admin, dev secrets, signing key generation, hostname mode, DB backend, TLS, brute-force tuning, event log retention) and what gets replaced for production. Production hardening is ADR 0007's open item — S-019 does not deliver it.

## Security plan

### Threat model

| Risk | Severity | Mitigation in S-019 |
|---|---|---|
| Credential leakage via committed `realm-export.json` | High | Commit only client IDs, role catalog, protocol mappers, *placeholder* dev passwords; README flags dev-only + rotation-at-deploy. |
| RS256 private signing key committed in export | High | Strip `keys[].privateKey` / `keys[].privateKeyPem` before commit; Keycloak generates a fresh key on first `--import-realm`. **CI guard enforces** (see acceptance criteria). |
| Bootstrap admin reuse across environments | High | Gate `KC_BOOTSTRAP_ADMIN_*` behind `profiles: [dev]`; values explicitly dev (`admin`/`admin`); README forbids reuse in prod. |
| Dev-mode security relaxations leaking into production narrative | Medium | README explicitly: "dev-only Keycloak"; production hardening is ADR 0007 open item, not promised by S-019. |
| PKCE bypass on SPA client | High | Realm-export pins `attributes."pkce.code.challenge.method"="S256"`, `implicitFlowEnabled=false`, `standardFlowEnabled=true`, `directAccessGrantsEnabled=false` on `fls-web`. CI smoke verifies. |
| Forgeable / missing `clubId` claim | High | `clubId` lives as a **User Attribute** (not realm/group/client-scope-from-input); protocol mapper pins type `String`, claim `clubId`, both ID + access tokens; **not** in account-console-editable attribute list. S-022 enforces server-side binding; S-019 only guarantees the claim is present and well-formed. |
| Permissive redirect URIs / web origins | High | Pin `redirectUris` to explicit `localhost:{4200,3000}/*`; `webOrigins = ["+"]` (valid-redirects match) — **never** `*`. CI grep verifies. |
| Self-registration | Medium | `registrationAllowed=false`. |
| Brute force on seed accounts | Medium | `bruteForceProtected=true`. |
| Long-lived / non-rotating tokens | Medium | Realm pins ADR 0007 values (15min access, 30/90d refresh idle/max, `revokeRefreshToken=true`, `refreshTokenMaxReuse=0`). |
| Proffix machine-client over-scoped | Medium | Minimal role grant (`proffix-sync` only); secret rotation procedure in README; dev secret committed but explicitly dev. |
| System-administrator seed → shared backdoor | High | One seeded `SYSTEM_ADMINISTRATOR` in dev only; README states this credential must be reset before any non-localhost exposure. |
| Admin events disabled → no forensic trail | Medium | `eventsEnabled=true`, `adminEventsEnabled=true`, `adminEventsDetailsEnabled=true`, `jboss-logging` listener. |

### Authorization

- Keycloak admin console gated by built-in `admin` realm role on the `master` realm; not exposed by S-019. No `@PreAuthorize` here (no Spring endpoints).
- `fls` realm OIDC endpoints public per OIDC; client config (PKCE + redirect URIs + grant types) gates access, not Keycloak roles.
- Role catalog seeded for S-026 to map; S-019 does not annotate any controller.

### Input validation

- `realm-export.json` must parse as valid Keycloak 26.5.x realm JSON (validated implicitly by `--import-realm`; malformed file crashes startup → CI smoke catches).
- Redirect URIs in export: strict allow-list (`http://localhost:{4200,3000}/*`); reject `*`, bare hosts, non-`http(s)://` schemes. Enforced by review + CI grep.
- `clubId` user attribute: string, non-empty; seed values are dev slugs (`club-1`). S-019 does not validate referential integrity (no DB); S-022 owns that.
- Bootstrap admin env vars: compose uses `${VAR:?must be set}` form so misconfiguration fails fast.

### PII handling

- Seed user emails: must use RFC 2606 reserved test domains (`@example.com|org|net|test`). CI grep enforces.
- Seed user names: placeholder (`Pilot One`, etc.) — no real Swiss names of identifiable people. CI grep enforces.
- `clubId` claim: not PII alone; becomes PII when joined to user attributes. Audit-log policy downstream (S-027): include as tenant scope; never redact.
- Production user data: out of scope. Seed export is wiped + replaced by S-028; README must say so.
- `account` client (OIDC self-service): enabled so end-users exercise GDPR access / forgotten requests via `/realms/fls/account`.

### Audit-log events

S-019 emits no application audit events. It pins **Keycloak-side** event capture so S-031 has data to forward:

| Event | Trigger | Payload (Keycloak default) |
|---|---|---|
| `LOGIN` / `LOGIN_ERROR` | Authorization Code exchange | `realmId`, `clientId`, `userId`, `ipAddress`, `error` |
| `LOGOUT` | end-session endpoint | same |
| `REFRESH_TOKEN` / `REFRESH_TOKEN_ERROR` | token refresh | flags reuse (rejected by `revokeRefreshToken=true`) |
| `RESET_PASSWORD` / `UPDATE_PASSWORD` / `SEND_RESET_PASSWORD` | C14 cutover audit trail | per-user, per-realm |
| `CLIENT_LOGIN` / `CLIENT_LOGIN_ERROR` | client-credentials grant (Proffix) | `clientId=fls-proffix`, no `userId` |
| admin events (all op types) | every admin-UI / admin-API mutation | actor (`authDetails.userId`), resource type, op, representation diff |

Listener: `jboss-logging` in S-019; S-031 adds an observability-stack forwarder.

### Cross-tenant leakage

- S-019 introduces no application queries — `@TenantId` does not apply here.
- S-019's contribution to multi-tenancy is **emitting the `clubId` claim** trustworthily (User Attribute, not user-editable, protocol-mapper pinned to String, both ID + access tokens).
- Cross-tenant leakage becomes impossible **only when S-022 binds this claim to `@TenantId` server-side**. S-019 hands S-022 a trustworthy claim; S-019 alone does not prevent leakage.
- Unscoped legitimate consumer: `fls-proffix` (no `clubId` claim by design — machine client, realm-wide; downstream sync endpoint scopes by deliveries' embedded `clubId`).

### OWASP applicability

- **A01 Broken Access Control:** strict redirect URIs, no direct-access-grants, no implicit flow, role catalog seeded, `clubId` claim tamper-resistant.
- **A02 Cryptographic Failures:** realm-generated RS256 key per environment (no committed private key); `start-dev` is HTTP-dev-only.
- **A03 Injection:** N/A.
- **A04 Insecure Design:** PKCE-S256 mandatory, rotating refresh, brute-force, self-registration off, bootstrap-admin profile-gated.
- **A05 Security Misconfiguration:** primary risk surface of this story. Mitigations: dev-only compose profile, README enumerates dev↔prod deltas, CI guards.
- **A06 Vulnerable/Outdated Components:** image tag pinned; Renovate/Dependabot bump cadence post-merge.
- **A07 Identification & Authentication Failures:** brute-force on; password policy set; MFA available (not seeded on).
- **A08 Integrity Failures:** realm export under version control = tamper-evident via git history; CI guard prevents private-key commit.
- **A09 Logging & Monitoring:** events + admin events on.
- **A10 SSRF:** N/A.

## Test plan

### Pyramid
- Unit: none — artifact is JSON + Compose service.
- Integration: realm-shape (jq schema), Compose boot smoke, Testcontainers handshake (optional).
- E2E: one issuer-roundtrip smoke (`admin-cli` password grant).
- Parity: none — `parity_test: none`; legacy `IdentityUserManager` is wholly replaced.

### Specific test cases

**Integration:**

- `realm_export_has_expected_shape` — `jq` on `next/auth/realm-export.json`:
  - `realm == "fls"`
  - `fls-web`: `publicClient==true`, `standardFlowEnabled==true`, `directAccessGrantsEnabled==false`, `implicitFlowEnabled==false`, `attributes."pkce.code.challenge.method"=="S256"`
  - `fls-backend`: `bearerOnly==true`
  - `fls-proffix`: `serviceAccountsEnabled==true`, `standardFlowEnabled==false`, `directAccessGrantsEnabled==false`
  - Realm roles: `SYSTEM_ADMINISTRATOR`, `CLUB_ADMINISTRATOR`, `FLIGHT_OPERATOR`, `PILOT`, `OFFICE_USER`, `GUEST`, `proffix-sync`
  - Three seed users present with correct roles + `clubId` attribute
  - `keys[]?.privateKey` and `keys[]?.privateKeyPem` both absent (or `.keys` absent entirely)
  - Lives in `scripts/check-realm-shape.sh`, runs in CI + pre-commit.

- `keycloak_compose_boots_and_serves_discovery`:
  - `docker compose up keycloak --wait --timeout 90` → exit 0 within 30s cold / 15s warm
  - `GET /realms/fls` → 200
  - `GET /realms/fls/.well-known/openid-configuration` → 200; `issuer == "http://localhost:8080/realms/fls"`; `authorization_endpoint`, `token_endpoint`, `jwks_uri` present
  - `GET /realms/fls/protocol/openid-connect/certs` → 200; at least one `RS256` key
  - Bash + curl + jq in a CI job; always-teardown via trap.

- `realm_export_round_trip_is_stable`:
  - Boot Keycloak; export; copy out; normalize both source + round-trip; `diff` exits 0.
  - Pin client + user `id` UUIDs in source so diff is genuinely zero.

- `keycloak_testcontainer_serves_pinned_realm` (optional JUnit5):
  - `KeycloakContainer.withRealmImportFile("/realm-export.json")`
  - Assert discovery + JWKS endpoints respond
  - Lives at `next/server/src/test/java/.../auth/KeycloakRealmContainerIT.java`
  - Keep for dev ergonomics; skip in CI if duplicates Compose smoke for cost reasons.

**E2E (one):**

- `keycloak_password_grant_smoke_via_admin_cli` — issues a token end-to-end:
  - `POST /realms/fls/protocol/openid-connect/token` with `client_id=admin-cli`, `grant_type=password`, `username=pilot1@example.com`, `password=pilot1`
  - Assert 200; access token parses as JWT with header `alg=RS256`; payload `iss==http://localhost:8080/realms/fls`; payload includes `clubId=="club-1"` claim
  - Validates the **whole** issuer + JWKS + protocol-mapper chain without coupling to S-020/S-021.

**Security-specific CI guards (additions to the test suite):**

- No private key in export (above).
- No real-domain emails / real PII in seed users.
- PKCE-S256 enforced: hit `/realms/fls/protocol/openid-connect/auth?...` without `code_challenge` → assert HTTP 400 `invalid_request`.
- Direct-access-grants disabled on `fls-web`: password grant with `client_id=fls-web` → assert HTTP 400 `unauthorized_client`.
- `clubId` claim present on access token of each seed user (decoded check).
- `redirectUris` allow-list: `jq` assertion against `^https?://[^*]+$` shape.

### Fixtures
- `next/auth/realm-export.json` — shared, committed, source of truth (above).
- Compose service `keycloak` — shared per-CI-job; teardown via `docker compose down -v` in always() step.
- `scripts/normalize-realm-export.sh`, `scripts/check-realm-shape.sh` — shared utilities; both reused by integration job + pre-commit.

### Coverage gaps (deferred)
- Spring Security 7 resource-server JWT validation → S-020.
- Angular OIDC SPA flow (Authorization Code + PKCE, silent refresh, logout) → S-021.
- `clubId` claim → `@TenantId` binding → S-022.
- User cutover (legacy IdentityUserManager → Keycloak with forced-reset flag) → S-028.
- Production hardening (TLS, real issuer, brute-force tuning, SMTP, password policy) → ADR 0007 open item + manual UAT.

### Risks
- **Cold-start flake on slow CI runners** (Keycloak 26.5 boot can exceed 60s on cold image pull). Mitigation: cache image layer; `--wait --timeout 90`; retry only the wait, not the import.
- **Round-trip diff churn** from regenerated IDs / timestamps. Mitigation: pin client + user `id` in source; normalize script + pinned image tag.
- **Testcontainers `keycloak` module drift vs. Compose image.** Mitigation: pin both to same minor; CI job verifies `kc.sh --version` matches a Gradle property.
- **`admin-cli` smoke fragility** if Keycloak hardens that client in a future release. Mitigation: keep smoke narrowly scoped; replace with a dedicated `fls-smoke` confidential client carrying direct-access-grants if `admin-cli` ever changes.
- **Private-key commit risk** if someone hand-edits the export. Mitigation: CI guard + pre-commit hook.
- **Port 8080 collision.** Mitigation: `${KEYCLOAK_PORT:-8080}:8080` host mapping.

## Performance plan

### Hot paths
- `POST /realms/fls/protocol/openid-connect/token` (refresh + password grants): every session start + every ~5min refresh. RS256 signing is constant cost; bcrypt only on first password grant.
- `GET /realms/fls/protocol/openid-connect/certs` (JWKS): once per resource-server startup + every 5min refresh; served from memory.
- `GET /realms/fls/.well-known/openid-configuration`: once per startup; served from memory.

### Required indexes
N/A — Keycloak owns its schema; dev uses embedded H2.

### N+1 risks
N/A in this story. **Flag for S-020/S-021:** tenant-scoped queries MUST read `clubId` from the JWT claim — never round-trip to Keycloak per request.

### Caching
- Resource-server side (S-020): Spring Security 7 default JWKS cache (5-min refresh) — **do NOT tighten without measurement.**
- SPA side (S-021): OIDC library caches discovery for the session; refresh-token rotation drives token refresh.
- Realm config: not cached by FLS — lives in Keycloak; `clubId` travels in the token.

### Latency budget
- `compose up keycloak` cold (no image cache, fresh H2): ≤ 30s to healthcheck green. Mitigation if breached: `JAVA_OPTS_APPEND=-Xms512m -Xmx512m`; disable unused feature flags.
- `compose up keycloak` warm: ≤ 15s. `--import-realm` re-runs every boot but linear in seed size (~5–15 KB).
- Token endpoint (refresh grant): p95 < 100ms local; < 200ms prod.
- Token endpoint (password grant, first login): p95 < 300ms local — dominated by bcrypt work factor.
- JWKS / discovery: p95 < 5ms each (memory serve).
- Realm import / export at seed shape: < 1s each. Threshold to revisit: > 1 MB → switch to `--dir` export. Out of scope here.

### Memory
- Keycloak dev container: ~512 MB heap + ~256 MB native = ~768 MB RSS. Acceptable on 8 GB laptops alongside Spring + Angular + Postgres + observability stack. Flag for operators on smaller hardware.

### Performance test plan
- **Smoke benchmark (manual, not gated CI):** 100 sequential token requests via `curl` + `time`; assert p95 < 100ms on dev box.
- **Cold-start measurement:** `time docker compose up -d keycloak && wait-for-healthcheck`; pass ≤ 30s cold / ≤ 15s warm. If repeatedly breached, escalate to optimized image (`kc.sh build` + `start` mode with static `KC_HOSTNAME`) — defer until pain materializes.
- **JWKS / discovery latency:** `curl -w '%{time_total}'`; sanity p95 < 5ms.
- No JMH / k6 / heap dump — this is infra, not app code.

<!-- modernize-refine: end -->

## Notes

The Keycloak admin DB (its own internal H2 in dev) is treated as ephemeral state; the realm-export.json is the source of truth.

The dev SPA client must allow `http://localhost:4200` and `http://localhost:3000` as redirect URIs.
