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
| `scripts/check-realm-shape.sh` | CI / pre-commit guard. Asserts the load-bearing security invariants (PKCE-S256, bearer-only, no private key, etc.) plus the theme-ref pins. |
| `scripts/check-theme-load.sh` | Operator smoke against a running Keycloak — asserts the alpenflight theme is loaded and locale fallback to parent works. Not wired to CI (no live Keycloak for the alpenflight realm). |
| `themes/alpenflight/` | Custom Keycloak theme (login, account, email). Per-type parents: `keycloak.v2` for login, `keycloak.v3` for account, `keycloak` for email (only email parent shipped). |

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

After editing `realm-export.json` or anything under `themes/`, rebuild the image:

```bash
bash alpenflight/ops/rebuild-keycloak.sh
```

(equivalent to `docker compose -p alpenflight-dev down -v keycloak && build keycloak && up -d --wait keycloak` — the `down -v` is load-bearing; without dropping the H2 volume Keycloak's default IGNORE_EXISTING import strategy silently preserves the old realm.)

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
bash alpenflight/ops/rebuild-keycloak.sh
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
| `KEYCLOAK_GOOGLE_CLIENT_ID` | compose default `set-via-env-for-google-signup` (CTA serves `invalid_client` on click) | from Google Cloud Console | OAuth 2.0 client ID, type "Web application". |
| `KEYCLOAK_GOOGLE_CLIENT_SECRET` | compose default `set-via-env-for-google-signup` | from Google Cloud Console | Rotate at deploy. |
| `KEYCLOAK_SMTP_HOST` | `mailpit` (compose service) | real SMTP host | Required for verify-mail delivery. |
| `KEYCLOAK_SMTP_PORT` | `1025` | provider port | |
| `KEYCLOAK_SMTP_FROM` | `noreply@alpenflight.local` | `noreply@alpenflight.ch` | |
| `KEYCLOAK_SMTP_USER` | `""` | provider user | |
| `KEYCLOAK_SMTP_PASSWORD` | `""` | provider password | Never commit. |
| `KEYCLOAK_SMTP_AUTH` | `false` | `true` | |
| `KEYCLOAK_SMTP_STARTTLS` | `false` | `true` | |
| `ALPENFLIGHT_WEB_BASE_URL` | `http://localhost:4200/` | real SPA origin (trailing slash) | **Build-time arg** (NOT runtime env). Feeds login footer + account-console "back to application" link. Set via shell env or repo-root `.env`; rotation requires image rebuild. |

Mailpit's web UI is at `http://localhost:8025` (compose). Outbound mail from
Keycloak lands there during local signup smokes — click the verify link to
complete the flow.

### Operator env workflow

Per-laptop Google OAuth client + SMTP overrides live in
`alpenflight/auth/.env` (gitignored). The committed `.env.example` carries
the working defaults (sentinel `set-via-env-for-google-signup` for the
Google secrets, `mailpit` for SMTP) and is loaded as the first env_file;
`.env` is loaded second and overrides line-by-line.

```bash
cp alpenflight/auth/.env.example alpenflight/auth/.env
$EDITOR alpenflight/auth/.env             # fill the values you want to override
docker compose -p alpenflight-dev up -d --force-recreate keycloak
```

`--force-recreate` re-evaluates `env_file` on the container without
dropping the H2 volume — preserves any federated-login user accounts
you've already created locally. **But:** realm-import only fires on a
fresh DB (H2 IGNORE_EXISTING), so the env values are only *read* on
first boot. Rotating an existing realm's `${env:KEYCLOAK_GOOGLE_*}` /
`${env:KEYCLOAK_SMTP_*}` value requires `rebuild-keycloak.sh` (drops H2,
wipes federated users, re-imports).

**Compose layering gotcha:** the per-var defaults live in `.env.example`,
NOT in the `environment:` block of `docker-compose.yml`. `environment:`
ALWAYS overrides `env_file`, so a `${VAR:-default}` in `environment:`
would silently shadow whatever the operator put in `.env`. See the
inline comment on the keycloak service in `docker-compose.yml`.

A fresh clone with NO `.env` file still boots — `.env.example` carries
all the working defaults. Clicking "Continue with Google" against the
`set-via-env-for-google-signup` sentinel renders Keycloak's stock
`invalid_client` page — that's the "feature is off" signal, not a setup
bug. The `ALPENFLIGHT_WEB_BASE_URL` build-arg has its own
`http://localhost:4200/` fallback at the `docker-compose.yml` build-args
layer, so the SPA root link also works out of the box.

### Google Cloud Console — one-time setup (prod / per-developer)

Each developer needs their own OAuth client — Google policy disallows
sharing OAuth secrets across developers. Use a throwaway test Gmail
account; the OAuth client counts against the account's free-tier quota
but is otherwise disposable.

1. Google Cloud Console → APIs & Services → Credentials → "Create credentials" → OAuth client ID.
2. Application type: **Web application**.
3. Authorized JavaScript origin: `http://localhost:8090` (verbatim — no trailing slash).
4. Authorized redirect URI: `http://localhost:8090/realms/alpenflight/broker/google/endpoint` (verbatim).
5. Copy the generated client ID + secret into `alpenflight/auth/.env` (NEVER paste real values into this README — the example below is a deliberately-fake format).
6. If the OAuth consent screen is in "testing" mode, add your own Google account under OAuth consent screen → Test users so Google permits the redirect.

Placeholder example (DO NOT use as real credentials — purely shape illustration):

```
KEYCLOAK_GOOGLE_CLIENT_ID=123456789012-fake-dev-only.apps.googleusercontent.com
KEYCLOAK_GOOGLE_CLIENT_SECRET=GOCSPX-fake-dev-only-secret
```

Per `check-realm-shape.sh`: the committed `realm-export.json` MUST keep the
config values as `${env:KEYCLOAK_GOOGLE_CLIENT_*}` placeholders. A real secret
slipping into the file via a sloppy `export-realm.sh` round-trip fails CI loudly.

### Substitution layers

Three substitution layers stack. Same marker syntax (`${VAR}`) for both
Keycloak realm-import and the Dockerfile sed — they run in series and use
distinct variable names so they never collide.

| Layer | Marker syntax | What it covers | Who substitutes |
|---|---|---|---|
| docker-compose | `${VAR:-default}` in `docker-compose.yml` | Variable interpolation; feeds the build-arg + the env_file values | docker compose at compose-up |
| Docker build-arg (S-173) | `${ALPENFLIGHT_WEB_BASE_URL}` in `realm-export.json` | `alpenflight-web.client.baseUrl` (Keycloak's realm-import substitution doesn't cover client.baseUrl — URL validator runs first) | `Dockerfile RUN sed` at image build, value passed via `build.args` |
| Keycloak realm-import | `${KEYCLOAK_GOOGLE_*}`, `${KEYCLOAK_SMTP_*}` in `realm-export.json` | SMTP server + Google IdP config | Keycloak's `AbstractFileBasedImportProvider` at startup (`start --import-realm`) |

**The Keycloak resolver gotcha that bit us on S-173:** Keycloak's
realm-import resolver is `System::getenv(propertyName)` — naïve, no
prefix handling. The `${env:VAR}` syntax you see in Quarkus config files
does NOT work here: the resolver calls `System.getenv("env:VAR")` which
is always null, then `StringPropertyReplacer`'s colon-fallback substitutes
the literal post-colon string (the variable name itself) into the realm.
Use the bare `${VAR}` form for realm-export substitutions and add a
shape-guard assertion against it.

Rotation requires an image rebuild + H2 wipe (`rebuild-keycloak.sh`)
for both the build-arg AND the realm-import layer — H2's
`IGNORE_EXISTING` strategy means values are only read on first import.

### CI integration probe

CI (`compose-smoke` workflow) brings the stack up under
`docker compose --profile next up --wait` and runs
`alpenflight/auth/scripts/check-keycloak-integration.sh` to cover the
end-to-end paths that `check-realm-shape.sh` can't reach from JSON alone:

- alpenflight-web `baseUrl` env-substitution resolves into the login HTML
  footer href (S-173 wiring).
- Verify-email round-trip via the admin API delivers a message to mailpit
  (catches the FreeMarker `Failed to template email` regression).

You can run the same script locally against any running stack:

```bash
bash alpenflight/auth/scripts/check-keycloak-integration.sh
```

It expects `localhost:8090` (Keycloak) + `localhost:8025` (mailpit) by
default; override via `KEYCLOAK_URL=` / `MAILPIT_URL=` / `EXPECTED_BASE_URL=`.

### Orphan KC users

Self-signup creates a Keycloak user (email + first/last name) BEFORE any tenant
exists in AlpenFlight. Cleanup of unverified-and-abandoned accounts is **deferred**:

- Per-IP rate-limit on `/login-actions/registration` → owned by **S-041** (reverse proxy).
- Nightly purge of `email_verified=false` users older than 14 d → owned by **S-038** (scheduled jobs) or new follow-up.

Right-to-deletion before first ingest: manual KC admin delete via
`http://localhost:8090/admin/master/console/#/alpenflight/users` (or the Admin REST
client S-052 already wired in). No app DB rows exist yet.

## Theme

The `alpenflight` Keycloak theme bridges the login / account / email
chrome to the SPA's visual stance (ADR 0024 — Swiss-precision + brand
blue + sharp corners + Roboto). Source under `themes/alpenflight/`:

| Path | Load-bearing fact |
|---|---|
| `login/theme.properties` | parent=keycloak.v2; styles= must list parent CSS first then `login.css` |
| `login/resources/css/login.css` | PF5 v5 global + per-component token overrides (light card, white inputs, splash background, wordmark contrast) |
| `login/resources/img/splash.jpg` | cockpit photo background (copy of `alpenflight/web/public/splash.jpg`); layered under a slate-900/55% wash via `--keycloak-bg-logo-url` |
| `login/resources/img/alpenflight-logo.svg` | reserved for a future template-override path; the stock keycloak.v2 `template.ftl` does NOT include `div.kc-logo-text`, so today this file is unused and the wordmark is rendered as text by `#kc-header-wrapper` |
| `login/resources/img/favicon.ico` | shared with `alpenflight/web/public/favicon.ico` (extraction to `alpenflight/branding/` deferred) |
| `login/footer.ftl` | overrides keycloak.v2's empty footer macro to render the "« Back to Start" link; href reads `${client.baseUrl}` (set per-environment on the alpenflight-web client — S-173 wires the env-substitution) |
| `login/messages/messages_{de,en,fr,it}.properties` | shorter locale labels (`locale_de=Deutsch` etc.), one-word page title (`loginAccountTitle=Sign in` / `Anmelden` / `Connexion` / `Accedi`), and the `backToLanding` string ("« Back to Start" + native translations) |
| `account/theme.properties` | parent=keycloak.v3 (K26.5 default React account console) |
| `account/resources/logo.svg` | v3 header logo (`${resourceUrl}/logo.svg`) |
| `account/resources/favicon.svg` | v3 favicon (`${properties.favIcon!'/favicon.svg'}`) |
| `email/theme.properties` | parent=keycloak; brand inheritance only — no FTL rewrites in scope |

### Dev round-trip

`start-dev` (the default compose command) disables theme caching, but
the theme files live inside the baked image — edits need a rebuild:

```bash
bash alpenflight/ops/rebuild-keycloak.sh
```

### Preview

| Page | URL |
|---|---|
| Login (default DE) | `http://localhost:8090/realms/alpenflight/protocol/openid-connect/auth?client_id=alpenflight-web&response_type=code&scope=openid&redirect_uri=http%3A%2F%2Flocalhost%3A4200%2F&state=preview` |
| Login (French)     | same URL + `&kc_locale=fr` |
| Account console    | `http://localhost:8090/realms/alpenflight/account/` (redirects to login when no session) |
| Verify-email       | trigger via `/signup` SPA route → mailpit at `http://localhost:8025` |
| Google IdP confirm | `/signup` SPA route → "Continue with Google" → KC's first-broker-login screen |

### Manual smoke matrix (per theme edit)

- Login form renders with the AlpenFlight wordmark + brand-blue primary
  button + sharp corners.
- Sign-up form (clicking "Sign up" on the login page) inherits the same
  chrome.
- Account console at `/realms/alpenflight/account/` shows the wordmark
  in the header and brand color on primary actions.
- Locale switch via `?kc_locale=fr` (or `?ui_locales=fr`) flips Keycloak
  labels to French — confirms our `messages_fr.properties` shortened
  strings (`loginAccountTitle=Connexion`, `backToLanding=« Retour à
  l'accueil`) render, and unshortened keys fall back to the parent
  bundle.
- IdP-broker confirmation page (Google first-login) inherits the
  alpenflight login theme.
- Mailpit-delivered verify-email body uses brand colors via parent
  email-template inheritance (wordmark-in-email intentionally not in
  scope — needs FTL).

### Live smoke (operator-driven)

```bash
bash alpenflight/auth/scripts/check-theme-load.sh
# Asserts: login HTML references /login/alpenflight/; account console
# returns HTTP 200; <html lang="xx"> matches for DE/FR/IT (parent-
# bundle fallback).
```

### S-151 (production cutover) flag

`start-dev` auto-disables theme cache; production `start` mode caches
theme assets. S-151 will need either `--spi-theme-cache-themes=false`
during cutover or a forced image rebuild on each theme change.

## Mock-auth status (post-S-026)

The backend `MockSecurityConfig` chain was deleted by S-026 — the production OAuth2 resource server is the only filter chain. The SPA's `app.config.mock.ts` seam (still alive under the `mock-auth` angular.json configuration) is now a Playwright-CI / no-Keycloak dev convenience: it stamps `Authorization: Bearer mock-sysadmin` on `/api/v1/*` requests, which the live backend rejects with 401. The Playwright SPA suite stubs the backend via `page.route(...)` so the rejection never surfaces in test runs; accidental hits against a running backend fail loudly. The SPA seam re-rips when a real-OIDC Playwright project lands (S-021 follow-up).
