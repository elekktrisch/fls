---
id: S-039
title: docker-compose.yml skeleton (backend + Postgres + Keycloak + mailpit)
epic: E-05
status: in_progress
started_at: 2026-05-17
depends_on: []
acceptance:
  - `docker compose -f next/ops/docker-compose.yml --profile dev up -d --wait` brings Postgres 17, Keycloak (per S-019 contract), and mailpit healthy on a dev laptop in ≤ 30s warm / ≤ 90s cold.
  - `docker compose -f next/ops/docker-compose.yml --profile dev --profile full up -d --wait` additionally brings the backend container (placeholder until S-040 produces the real image) healthy.
  - Compose file location is `next/ops/docker-compose.yml` (NOT repo root — repo-root `docker-compose.yml` is the legacy e2e stack and stays untouched).
  - Prod overlay `next/ops/docker-compose.prod.yml` exists; `docker compose -f next/ops/docker-compose.yml -f next/ops/docker-compose.prod.yml config -q` parses cleanly.
  - `next/ops/.env.example` committed with every `${VAR}` referenced in either compose file documented; `next/ops/.env` is gitignored.
  - Every service declares `healthcheck` with bounded `interval`/`retries`/`start_period`; downstream `depends_on` uses `condition: service_healthy`.
  - All published host ports for data services (Postgres, mailpit SMTP + UI) bind to `127.0.0.1:` in the dev compose (not `0.0.0.0`). Keycloak's `8080` follows the S-019 contract.
  - Postgres uses a named volume `fls-pgdata`; `compose down` preserves; `compose down -v` wipes.
  - Prod overlay pins every image by digest (ADR 0010 rule 9); strips dev-only env (e.g. `KC_BOOTSTRAP_ADMIN_*`), strips mailpit, removes host-port mappings on Postgres + backend (reverse-proxy only — S-041 follow-up).
  - `next/ops/README.md` documents: first-time bring-up, profile matrix (`dev` / `full`), `compose down` vs. `down -v` semantics, port-collision recovery via `.env`, dev SMTP inspection at `http://localhost:8025`, and disambiguation from the legacy root compose.
  - CI guards: `compose config` exits 0 on both files; no `:latest` tags; no literal secrets in prod overlay env; every service has a healthcheck.
estimate: M
adr_refs: [0010]
parity_test: none
refined: true
refined_at: 2026-05-15
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
refined_speculative: true
refined_speculative_at: 2026-05-15
github_issue: 46
---

## Context

First deployment artifact. Required by basically every other story that touches integration testing. Implements ADR 0010's 10 K8s-ready hygiene rules from day one (env config, stateless, stdout logs, healthchecks, graceful shutdown, idempotent migrations, no host paths, one process per container, digest pins for prod, secrets injected).

**Critical contextual note:** the repo-root `/docker-compose.yml` is the **legacy e2e** stack (mssql + mailpit for the AngularJS Playwright suite); it must not be clobbered. The new-stack compose lives at `next/ops/docker-compose.yml`. Operator ergonomics handled via documented invocation + optional shell aliases (`fls-up`, etc.) in `next/ops/README.md`.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Create `next/ops/` directory; seed `next/ops/README.md`, `next/ops/.gitignore` (ignores `.env`, `.env.local`, `.env.*.local`).
- [ ] Author `next/ops/docker-compose.yml` (dev base) per Design notes §"Service shape".
- [ ] Author `next/ops/docker-compose.prod.yml` (prod overlay) per Design notes §"Prod overlay".
- [ ] Author `next/ops/.env.example` with every `${VAR}` documented; ship with `POSTGRES_PASSWORD=` empty (no default) so misconfig fails fast.
- [ ] Hand S-019's Compose contract verbatim into the `keycloak` service block (image, command, env, volumes, ports, healthcheck) — but adjust the realm-export bind-mount path from S-019's `./next/auth/realm-export.json` to `../auth/realm-export.json` (relative to `next/ops/`). **Update S-019 in lockstep** (small amendment to its Compose contract block).
- [ ] Wire CI guards in `.github/workflows/`: `compose-lint.yml` (no `:latest`, no literal secrets in prod, every service has healthcheck) and `compose-smoke.yml` (parse + `compose up --wait` per profile + functional probes).
- [ ] Document the profile matrix + the `dev`+`full` two-profile-union footgun in `next/ops/README.md`.

<!-- modernize-refine: start -->

## Design notes

### File layout

| Path | Purpose |
|---|---|
| `next/ops/docker-compose.yml` | Dev base. 4 services: `postgres` (always-on), `keycloak` (profile `dev`), `mailpit` (profile `dev`), `backend` (profile `full`). |
| `next/ops/docker-compose.prod.yml` | Prod overlay. Image digest pins, `start` not `start-dev`, resource limits, restart=always, no mailpit, no host port mappings on Postgres + backend. |
| `next/ops/.env.example` | Committed; documents every `${VAR}` from either compose file. |
| `next/ops/.env` | Gitignored. |
| `next/ops/.gitignore` | Local ignore for `.env*` (except `.env.example`). |
| `next/ops/README.md` | Operator manual: bring-up, profiles, port-collision recovery, volume semantics, legacy-compose disambiguation. |

**No code in `next/server/` or `next/web/` this story.** No Postgres `init/` SQL — Flyway (S-009) is the single schema source-of-truth (ADR 0010 rule 6).

### Compose `name` and network

```yaml
name: fls
networks:
  default:
    name: fls-next
volumes:
  pgdata:
    name: fls-pgdata
```

Explicit project name (`fls`) so all containers / volumes / networks prefix `fls_*` regardless of directory rename (vision §8 `next/` → final slug). Explicit volume name (`fls-pgdata`) so `docker volume ls` is operator-readable.

### Service shape

#### `postgres` (no profile — always on)

```yaml
postgres:
  image: postgres:17-alpine
  environment:
    POSTGRES_DB: fls
    POSTGRES_USER: fls
    POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?must be set in .env}
  volumes:
    - pgdata:/var/lib/postgresql/data
  ports:
    - "127.0.0.1:${POSTGRES_PORT:-5433}:5432"   # loopback-only; host-Postgres-collision-safe
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U fls -d fls"]
    interval: 5s
    timeout: 3s
    retries: 5
    start_period: 10s
  restart: unless-stopped
```

- `postgres:17-alpine` over plain `postgres:17` — smaller surface, faster pulls. Fall back to `postgres:17` if Alpine/libc issues surface.
- No init scripts in `/docker-entrypoint-initdb.d/` — Flyway owns schema.
- `${POSTGRES_PASSWORD:?must be set in .env}` form fails `compose up` fast on missing `.env`.
- Host port `5433` not `5432` — avoids the common collision with a host-installed Postgres.

#### `keycloak` (profile: `dev`) — verbatim from S-019

```yaml
keycloak:
  image: quay.io/keycloak/keycloak:26.5
  command: ["start-dev", "--import-realm"]
  environment:
    KC_BOOTSTRAP_ADMIN_USERNAME: admin
    KC_BOOTSTRAP_ADMIN_PASSWORD: admin          # dev-only — see next/auth/README.md
    KC_HOSTNAME: localhost
    KC_HTTP_ENABLED: "true"
    KC_HEALTH_ENABLED: "false"
    KC_LOG_LEVEL: INFO
  volumes:
    - ../auth/realm-export.json:/opt/keycloak/data/import/realm-export.json:ro
  ports:
    - "${KEYCLOAK_PORT:-8080}:8080"
  healthcheck:
    test: ["CMD-SHELL", "curl -fsS http://localhost:8080/realms/fls || exit 1"]
    interval: 10s
    timeout: 3s
    retries: 6
    start_period: 30s
  restart: unless-stopped
  profiles: [dev]
```

- Bind-mount path is **`../auth/realm-export.json`** (relative to `next/ops/`) because compose lives at `next/ops/docker-compose.yml`. **S-019's contract amends accordingly** — this story carries the lockstep update.
- `profiles: [dev]` is essential — prod uses a fundamentally different Keycloak shape (`start` mode, Postgres-backed, real hostname, TLS, no bootstrap admin).

#### `mailpit` (profile: `dev`)

```yaml
mailpit:
  image: axllent/mailpit:v1.21         # pin a concrete v1.x at write time
  environment:
    MP_MAX_MESSAGES: "500"
    MP_SMTP_AUTH_ACCEPT_ANY: "1"
    MP_SMTP_AUTH_ALLOW_INSECURE: "1"
  ports:
    - "127.0.0.1:${MAILPIT_SMTP_PORT:-1025}:1025"
    - "127.0.0.1:${MAILPIT_WEB_PORT:-8025}:8025"
  healthcheck:
    test: ["CMD-SHELL", "wget -qO- http://localhost:8025/api/v1/info > /dev/null || exit 1"]
    interval: 5s
    timeout: 3s
    retries: 10
    start_period: 5s
  restart: unless-stopped
  profiles: [dev]
```

- Auth-accept-any matches the legacy stack so Spring's `JavaMailSender` connects with any creds.
- Loopback-bound — no LAN exposure for the in-memory inbox.

#### `backend` (profile: `full`)

```yaml
backend:
  image: alpenflight-server:dev                # placeholder until S-040 produces a real image
  build:                               # dual shape: image-if-exists, build-if-needed
    context: ../server
    dockerfile: Dockerfile             # added by S-040
  environment:
    SPRING_PROFILES_ACTIVE: dev
    SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/fls
    SPRING_DATASOURCE_USERNAME: fls
    SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD:?must be set in .env}
    SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI: http://keycloak:8080/realms/fls
    SPRING_MAIL_HOST: mailpit
    SPRING_MAIL_PORT: "1025"
    MANAGEMENT_SERVER_PORT: "8081"
  depends_on:
    postgres: { condition: service_healthy }
    keycloak: { condition: service_healthy }
    mailpit:  { condition: service_started }
  ports:
    - "${BACKEND_HTTP_PORT:-8082}:8080"
    - "${BACKEND_MGMT_PORT:-8081}:8081"
  healthcheck:
    test: ["CMD-SHELL", "curl -fsS http://localhost:8081/actuator/health/readiness || exit 1"]
    interval: 10s
    timeout: 3s
    retries: 6
    start_period: 60s
  restart: unless-stopped
  profiles: [full]
```

- `profiles: [full]` keeps `compose up` infra-only by default — the dominant dev loop is "backend runs from IDE against compose infra".
- `MANAGEMENT_SERVER_PORT=8081` separates actuator from app traffic.
- **Issuer-URL caveat:** backend resolves Keycloak via `http://keycloak:8080/realms/fls` (container DNS), but tokens minted from the browser carry `iss=http://localhost:8080/realms/fls`. S-020 owns the resolution (set `KC_HOSTNAME_URL=http://localhost:8080` on Keycloak so it always emits localhost issuer, or accept-both-issuers in the resource server). README documents the gotcha; S-020 resolves.

### Profile matrix

| Invocation | Services started | Use case |
|---|---|---|
| `docker compose up` | `postgres` only | Postgres-only iteration. Rare. |
| `docker compose --profile dev up` | `postgres` + `keycloak` + `mailpit` | **Default dev loop.** Backend runs from IDE; SPA from `ng serve`. |
| `docker compose --profile dev --profile full up` | All four | Full container stack. CI / smoke. |
| `docker compose --profile full up` *(no `dev`)* | `postgres` + `backend` only | Edge case — backend without IdP. Used for narrow integration tests that mock auth. |

**Compose profile-union footgun** (must be in README): profiles compose by union, not by inclusion. To run everything in containers, pass **both** `--profile dev --profile full`.

**Operator aliases** documented in README:
```bash
alias fls-up='docker compose -f next/ops/docker-compose.yml --profile dev up -d --wait'
alias fls-all='docker compose -f next/ops/docker-compose.yml --profile dev --profile full up -d --wait'
alias fls-down='docker compose -f next/ops/docker-compose.yml down'
alias fls-nuke='docker compose -f next/ops/docker-compose.yml down -v --remove-orphans'
```

### Prod overlay (`docker-compose.prod.yml`)

Overlay-only fields; `docker compose -f docker-compose.yml -f docker-compose.prod.yml config -q` must pass.

```yaml
services:
  postgres:
    image: postgres:17-alpine@sha256:<resolved-at-write-time>
    restart: always
    ports: !reset []                              # proxy-only; no host port
    deploy:
      resources:
        limits:
          memory: 1G
          cpus: "1.0"

  keycloak:
    image: quay.io/keycloak/keycloak:26.5@sha256:<resolved-at-write-time>
    command: ["start"]                            # production mode
    environment:
      KC_BOOTSTRAP_ADMIN_USERNAME: !reset null
      KC_BOOTSTRAP_ADMIN_PASSWORD: !reset null
      KC_HOSTNAME: ${KC_HOSTNAME:?must be set}    # e.g. auth.fls.example
      KC_HTTP_ENABLED: "false"
      KC_HEALTH_ENABLED: "true"
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://kc-postgres:5432/keycloak
      KC_DB_USERNAME: keycloak
      KC_DB_PASSWORD: ${KC_DB_PASSWORD:?must be set}
      KC_PROXY_HEADERS: xforwarded
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:9000/health/ready || exit 1"]
    restart: always
    profiles: !override []                        # always-on in prod
    deploy:
      resources:
        limits: { memory: 1G, cpus: "1.0" }

  mailpit:
    profiles: !override [never]                   # disabled in prod; replace with external SMTP

  backend:
    image: ghcr.io/<org>/alpenflight-server:${BACKEND_TAG:?must be set}@sha256:<resolved-at-deploy>
    ports: !reset []                              # proxy-only
    restart: always
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI: https://${KC_HOSTNAME}/realms/fls
      SPRING_MAIL_HOST: ${SMTP_HOST:?must be set}
      SPRING_MAIL_PORT: ${SMTP_PORT:-587}
    profiles: !override []
    deploy:
      resources:
        limits: { memory: 768M, cpus: "1.0" }
```

**Compose `!reset` / `!override`** tags require Compose v2.24+. Pin in CI's Docker Compose version.

**Prod-overlay open items flagged (not solved here):**
- Whether Keycloak shares Postgres with the app or runs its own (overlay sketches separate `kc-postgres`) — ADR 0007 open item.
- mailpit replacement (Postfix container vs. external SMTP service) — ADR 0010 follow-up story.
- Reverse proxy + TLS — S-041.

### `.env.example` shape

```bash
# next/ops/.env.example
# -----------------------------------------------------------------------------
# Copy to .env (gitignored). MUST set POSTGRES_PASSWORD — no default ships;
# compose will fail fast otherwise. Generate via: openssl rand -base64 24
# -----------------------------------------------------------------------------

# --- Postgres (always-on) ---
POSTGRES_PASSWORD=
POSTGRES_PORT=5433

# --- Keycloak (profile: dev) ---
KEYCLOAK_PORT=8080

# --- mailpit (profile: dev) ---
MAILPIT_SMTP_PORT=1025
MAILPIT_WEB_PORT=8025

# --- backend (profile: full) ---
BACKEND_HTTP_PORT=8082
BACKEND_MGMT_PORT=8081
```

CI guard: every `${VAR}` referenced in either compose file must appear in `.env.example`.

### Healthcheck table

| Service | Test | Interval | Retries | Start period |
|---|---|---|---|---|
| `postgres` | `pg_isready -U fls -d fls` | 5s | 5 | 10s |
| `keycloak` | `curl -fsS http://localhost:8080/realms/fls \|\| exit 1` | 10s | 6 | 30s |
| `mailpit` | `wget -qO- http://localhost:8025/api/v1/info > /dev/null \|\| exit 1` | 5s | 10 | 5s |
| `backend` (placeholder) | `curl -fsS http://localhost:8081/actuator/health/readiness \|\| exit 1` | 10s | 6 | 60s |

**Caveat:** Compose only *observes* health; it does NOT restart unhealthy containers. `restart: unless-stopped` fires on container exit, not on healthcheck failure. K8s liveness probes solve this when S-046 lands; for compose-only prod, consider an `autoheal` sidecar — document, don't fix.

### Compose validation

CI commands (gated on changes to `next/ops/**`):

```bash
# Base file (dev profile + full profile, merged)
docker compose -f next/ops/docker-compose.yml --profile dev --profile full config -q

# Overlay merged
docker compose \
  -f next/ops/docker-compose.yml \
  -f next/ops/docker-compose.prod.yml \
  --profile dev --profile full \
  config -q
```

Pass criterion: exit 0 + stderr empty.

### Integration with other stories

| Downstream | What it consumes from S-039 |
|---|---|
| S-001 (Spring Boot scaffold) | Backend service slot in `--profile full`. |
| S-009 (Flyway) | JDBC URL `jdbc:postgresql://localhost:5433/fls` (host) or `postgres:5432/fls` (container). |
| S-015 (Testcontainers) | Does NOT consume — spins its own ephemeral Postgres per test. README documents this isolation. |
| S-019 (Keycloak realm) | Compose contract source; this story carries the lockstep amendment on the realm-export bind-mount path. |
| S-020 (Spring Security 7) | Issuer URI contract; resolves the dev `localhost` vs. container `keycloak` issuer mismatch. |
| S-030 (Actuator widening) | Must expose `/actuator/health/{liveness,readiness}` for the backend healthcheck. |
| S-031 (structured JSON logging) | All services log to stdout/stderr (ADR 0010 rule 3). |
| S-040 (Spring Dockerfile) | Replaces backend placeholder image. |
| S-041 (reverse proxy) | Adds Caddy/Traefik service to the prod overlay. |
| S-046 (Helm/Kustomize) | Mirrors this compose topology to K8s manifests. |

### Alternatives considered

- **Option A (chosen):** `next/ops/docker-compose.yml` + `docker-compose.prod.yml` overlay; profile-gated services. Matches ADR 0010 pattern. Doesn't clobber the legacy root compose.
- **Option B (rejected):** Single compose file with env-switched config. Compose can't conditionally swap `command:` based on env — overlay is the idiom.
- **Option C (rejected):** Overwrite repo-root `docker-compose.yml`. Breaks the legacy Playwright suite still load-bearing for parity. Alias in README gives equivalent ergonomics.
- **Option D (rejected, deferred):** Include reverse proxy → S-041. Include observability stack → S-032/033/034.
- **Option E (rejected):** Postgres init scripts in `/docker-entrypoint-initdb.d/`. Flyway owns schema (ADR 0010 rule 6 + S-009).
- **Option F (rejected):** Always-on `backend` service (no profile). Forces image rebuilds on every code change; profile-gating `[full]` keeps the IDE inner loop fast.
- **Option G (rejected):** Strict digest pins on dev compose. Pragmatic: tag-pin dev (Renovate-friendly), digest-pin prod (ADR 0010 rule 9 strict).

## Edge cases & hidden requirements

- **Repo-root `docker-compose.yml` is the legacy e2e stack** — must not be clobbered; new compose lives at `next/ops/docker-compose.yml`. README disambiguates.
- **Compose-file location update propagates to S-019:** the realm-export bind-mount path becomes `../auth/realm-export.json` (relative to `next/ops/`). S-019's design notes carry the `./next/auth/...` form; this story carries the lockstep fix.
- **Backend image build context** is `../server` (relative to `next/ops/`) — and S-040 hasn't merged the Dockerfile yet. Profile-gating `[full]` makes this OK: default `compose up` doesn't try to build the backend.
- **`pg_isready` succeeds before app DB exists:** healthcheck uses `pg_isready -U fls -d fls` (not bare `pg_isready`) so "healthy" means "FLS DB exists and accepts FLS-user connections" — Flyway in S-009 then connects without race.
- **`POSTGRES_PASSWORD` empty in `.env.example`:** `${VAR:?must be set in .env}` form fails `compose config` at parse time — misconfig is loud, not silent.
- **`compose down` vs. `down -v`:** without `-v`, the named volume `fls-pgdata` persists across teardown; with `-v`, it wipes. README explicitly enumerates both. In prod, `-v` is forbidden in runbooks (backups are the recovery path — S-042).
- **`KC_BOOTSTRAP_ADMIN_*` change after first boot is silently ignored** — re-running with a different password requires `compose down -v` (wipes H2 volume). README documents.
- **Port collisions on dev hosts** (5432, 8080, 1025, 8025): all env-overridable via `.env`.
- **Network alias for issuer URL stability:** backend reaches Keycloak via container DNS `keycloak:8080`; SPA reaches via `localhost:8080`. The `iss` claim is baked into the token. S-020 resolves (set `KC_HOSTNAME_URL=http://localhost:8080` so Keycloak always emits localhost issuer, or backend accepts both). Documented for S-020.
- **Compose `!reset` / `!override` tags** require Compose v2.24+. CI pins.
- **`compose up --wait` returns exit 1 with no clear log on failure.** Mitigation: CI captures `docker compose ps --format json` + `docker compose logs --tail 200` as artifacts in always() steps.
- **Profile-union footgun:** `compose --profile full up` alone DOES NOT include the `[dev]`-profiled services. To get everything in containers, pass `--profile dev --profile full`. README pins this.
- **WSL2 / Docker Desktop file-system performance:** named volumes avoid the bind-mount slowness; no host-path mounts beyond S-019's realm-export read-only path.
- **`COMPOSE_PROJECT_NAME` collision:** explicit `name: fls` at compose-top level pins regardless of directory rename (vision §8 `next/` → final slug).
- **Database superuser vs. app user:** `POSTGRES_USER=fls` is the superuser of the FLS DB; S-009 may split into a separate `fls_app` user. S-039 leaves the seam open — no init scripts ship.
- **Image tag policy:** dev uses minor tags (`postgres:17-alpine`, `quay.io/keycloak/keycloak:26.5`, `axllent/mailpit:v1.21`); prod overlay pins by digest. Renovate/Dependabot bumps dev via PR.
- **README must cover:** first-time bring-up, profile matrix + footgun, port collision recovery, `down` vs. `down -v`, mailpit inbox at `http://localhost:8025`, where pgdata lives, legacy-compose disambiguation, the issuer-URL gotcha flagged for S-020.

## Security plan

### Threat model

| Risk | Severity | Mitigation in S-039 |
|---|---|---|
| `.env` with real secrets committed to git | High | `.gitignore` lists `.env`/`.env.local`/`.env.*.local`; commit only `.env.example` with placeholders; pre-commit/gitleaks scan; CI grep rejects PRs that add `.env`. |
| Default/weak Postgres password in `.env.example` | High | `.env.example` ships `POSTGRES_PASSWORD=` empty → compose fails fast; README documents `openssl rand -base64 24`. |
| Postgres host-port on `0.0.0.0` in dev (LAN exposure) | High | `ports: ["127.0.0.1:5433:5432"]`; prod overlay removes host port (reverse-proxy only). |
| mailpit web UI on `0.0.0.0:8025` no auth | Medium | `127.0.0.1:8025:8025` + `127.0.0.1:1025:1025`; prod overlay omits mailpit. |
| Keycloak bootstrap-admin leak into prod | High | S-019 gates behind `profiles: [dev]`; prod overlay uses `!reset null` to strip the env vars; CI guard `grep -i bootstrap` on merged config returns empty. |
| Floating image tags — supply-chain drift | Medium | Prod overlay pins by digest (`@sha256:…`); dev uses minor tags; Renovate/Dependabot bumps via PR. |
| `:latest` / untagged images | Medium | CI lint rejects on both files. |
| mailpit container running as root | Low | `user: "1000:1000"` if upstream supports; verify at write time. |
| No network segmentation (default bridge) | Low at scale | Flag for K8s migration (S-046 + NetworkPolicy). |
| App writes logs to in-container path (violates rule 3) | Medium | Compose lint rejects log-volume mounts. |
| `pgdata` PII in prod | High once data lands (S-013+) | Named volume `fls-pgdata`; `down -v` forbidden in prod runbooks; backups (S-042) inherit FADP/GDPR duty. |
| `.env` readable by every user on shared dev host | Low–Medium | README: `chmod 600 .env` (Linux/macOS) / `icacls` (Windows); optional `make env` helper. |
| Compose run from wrong working dir uses wrong `.env` | Low | README pins repo-root invocation; consider explicit `env_file:`. |
| Healthcheck masking — backend never starts if Keycloak misconfigured | Low | Bounded `retries` + `start_period`; CI smoke (S-110 territory) catches "stuck forever". |
| Cross-tenant leakage | N/A | No app queries this story. |

### Authorization

N/A — no app endpoints. Keycloak admin console is dev-only profile. mailpit UI is dev-only + loopback.

### Input validation

- Required env vars (`POSTGRES_PASSWORD`, `KC_DB_PASSWORD`, `KC_HOSTNAME`, `SMTP_HOST`, `BACKEND_TAG`) use `${VAR:?must be set}` — compose fails fast.
- Host-port bindings: lint rule rejects bare `"5432:5432"` etc. — must prefix `127.0.0.1:` in dev.
- Image references: no `:latest`; prod requires `@sha256:`; CI enforces.
- Volume references: named only; no host bind mounts (ADR 0010 rule 7) — except S-019's read-only realm-export.
- Healthcheck shape: every service declares one; downstream `depends_on` uses `condition: service_healthy`.

### PII handling

- `pgdata` volume: once S-013+ lands, contains member names, emails, licence numbers, medical certs → PII / sensitive (FADP Art. 5 / GDPR Art. 9). Backups (S-042) handle at-rest encryption + Swiss/EU residency.
- mailpit captured emails: dev-only synthetic PII; in-memory; loopback-bound; CI does not export.
- Keycloak realm export: dev-only client secrets; production rotates.
- `.env`: secret material; never logged, never tarballed into CI artifacts, never copied into images (`.dockerignore` rejects).

### Audit-log events

N/A — Compose itself emits no audit. Keycloak audit per S-019; app audit per S-027.

### Cross-tenant leakage

N/A — no app queries.

### OWASP applicability

- **A01:** loopback-binding for data services + mailpit prevents unauthenticated LAN access.
- **A02:** TLS terminated by reverse proxy in prod (S-041); dev HTTP on loopback OK.
- **A04:** dev/prod overlay separation IS the design control.
- **A05:** primary risk surface. CI guards: no `:latest`, no `0.0.0.0` bindings on data services, prod overlay strips dev relaxations.
- **A06:** image pins + Renovate/Dependabot bumps; weekly Trivy/Grype scan on resolved images.
- **A07:** `KC_BOOTSTRAP_ADMIN_*` only under `profiles: [dev]`; prod uses `!reset null`.
- **A08:** prod digest pins; backups recovery.
- **A09:** stdout/stderr logs only — no host log files.

### CI / pre-commit guards

- `.gitignore` audit: `.env`, `.env.local`, `.env.*.local` present.
- gitleaks pre-commit + CI scan.
- `docker compose config -q` exits 0 on both files (dev base, overlay merged).
- Compose lint: no `:latest`, no untagged images, no bare `0.0.0.0` on data services in dev, no `KC_BOOTSTRAP_ADMIN_*` in prod overlay, no mailpit in prod overlay, no host port on Postgres in prod, all images digest-pinned in prod, no host bind mounts.
- `.dockerignore` audit: `.env`, `.env.*` listed (so they can't be COPYed into images).
- Trivy/Grype weekly scheduled scan.

## Test plan

### Pyramid
- Unit: 0 (infra; nothing pure).
- Integration: 0 (no Spring slices; backend is a placeholder).
- E2E: 0 (new-stack e2e starts in S-109).
- Smoke (CI `compose-smoke`): 12 jobs.
- Hygiene guards (CI `compose-lint`): 4 grep-based checks.
- Parity: 0 — `parity_test: none`; no legacy oracle (legacy is IIS).

### Smoke tests (CI job: `compose-smoke`)

Gated on changes to `next/ops/**` and `.env.example`:

- `compose_config_parses`: `docker compose -f next/ops/docker-compose.yml --profile dev --profile full config -q` exits 0.
- `compose_prod_config_parses`: same with `-f next/ops/docker-compose.prod.yml` overlay.
- `compose_env_example_parses`: `cp .env.example .env` (with a generated `POSTGRES_PASSWORD`) and `compose config -q` exits 0.
- `compose_dev_boots`: `compose --profile dev up -d --wait --timeout 90` → exits 0; Postgres + Keycloak + mailpit healthy.
- `compose_dev_boots_full`: `compose --profile dev --profile full up -d --wait --timeout 180` → backend (placeholder) also healthy; proves the `depends_on: service_healthy` chain.
- `postgres_select_one`: `compose exec -T postgres psql -U fls -d fls -c 'SELECT 1'` → `1`.
- `postgres_pgdata_persists`: insert sentinel; `compose stop && compose start`; row present.
- `postgres_pgdata_wipes`: insert; `compose down -v && compose up`; row absent (verifies named volume, not bind mount).
- `keycloak_reachable`: `curl -fsS --retry 10 --retry-delay 3 http://localhost:8080/realms/master` → 200 (the `fls` realm assertion lives in S-019's smoke).
- `mailpit_api_reachable`: `curl -fsS http://localhost:8025/api/v1/info` → JSON with `Version` field.
- `mailpit_smtp_accepts`: `swaks --to qa@example.com --from ci@example.com --server localhost:1025`; then `curl http://localhost:8025/api/v1/messages | jq '.total >= 1'`.
- `compose_logs_to_stdout`: `docker compose logs postgres keycloak mailpit --tail 5` produces non-empty output (guards ADR 0010 rule 3).

Teardown: `compose down -v --remove-orphans` in always() step. On failure: upload `compose ps --format json` + `compose logs --tail 200` as CI artifacts.

### Hygiene-rule guards (CI job: `compose-lint`)

Pure static checks; no daemon:

- `rule_9_prod_uses_digests`: every `image:` in `docker-compose.prod.yml` contains `@sha256:`.
- `rule_10_no_literal_secrets`: env keys matching `(?i)(PASSWORD|SECRET|TOKEN|KEY)` must reference `${VAR}`, never a literal value.
- `rule_4_every_service_has_healthcheck`: `compose config --format json` shows `healthcheck.test` on every service.
- `rule_3_no_host_log_bind_mounts`: no `target: /var/log` or `source: ./logs` in either file.

### Fixtures

- `.env.example` IS the fixture; copy to `.env` (with a generated password) for smoke runs.
- Sentinel row for pgdata persistence: ad-hoc `CREATE TABLE IF NOT EXISTS _smoke(id int); INSERT INTO _smoke VALUES (1);`.
- No DB schema fixtures — S-009 / S-010 / S-012 own.
- No Keycloak realm fixture beyond what S-019 commits.

### Coverage gaps (deferred)

- Production deploy validation (registry pull, host networking, real domain) → S-046 / S-117.
- Reverse proxy + TLS → S-041.
- Backup / restore round-trip → S-042 / S-043.
- Real backend health (JDBC, JWKS, Flyway migrate) → S-001 + S-009 + S-020.
- Cross-platform Docker Desktop matrix (macOS/Windows volume semantics) → manual UAT; CI runs on Linux only.
- Multi-arch (arm64 for Apple Silicon) → out of scope; revisit in S-046 if dev complaints surface.

### Risks

- **Cold image pulls** blow `--wait --timeout 90` on cache-miss runners. Mitigation: `docker pull` warmup step; `actions/cache` keyed on image tags; bump to 240s for `--profile full` job.
- **Postgres "ready but rejecting auth" race.** Mitigation: `pg_isready -U fls -d fls` (not bare); `start_period: 10s`; smoke retries `SELECT 1` up to 5 times.
- **Keycloak 26.5 cold-start 30–60s on CI.** Mitigation: `start_period: 30s`; `curl --retry 10 --retry-delay 3`; assert `/health/ready` not realm endpoint until S-019.
- **Port collisions on dev hosts.** Mitigation: env-overridable ports; CI uses non-default ports if needed.
- **`compose up --wait` fails silently.** Mitigation: artifact upload on failure (above).
- **Prod overlay drift from dev base.** Mitigation: CI parses both; CI assertion that service-name set in prod-merged config equals service-name set in dev base.

## Performance plan

### Hot paths
N/A — S-039 stands up infrastructure only.

### Dev-loop time-to-ready (the actual gates)
- `compose --profile dev up -d --wait` warm: ≤ 30s. Dominant cost: Keycloak realm import + JVM warmup ~15s; Postgres ~5s; mailpit ~1s.
- `compose --profile dev up -d --wait` cold (image pull): ≤ 90s — network-bound on the ~600 MB Keycloak image.
- `compose --profile dev --profile full up -d --wait`: warm ≤ 50s, cold ≤ 110s (+ ~20s backend Spring Boot warmup post-S-001).

### Per-service healthcheck convergence
- postgres: < 10s
- keycloak: < 30s cold / < 15s warm
- mailpit: < 5s
- backend (placeholder): < 60s

### Required indexes
N/A — Postgres schema owned by Flyway (S-009+).

### N+1 risks
N/A — no ORM in this story.

### Caching
- **Image layer cache:** critical for CI cold-start. Pre-pull step keyed on image tags via `actions/cache`. Warm path ~30s vs. cold ~90s.
- **Compose build cache:** N/A (no local builds in S-039).
- **Server/client caches:** N/A.

### Memory budget
- Default profile (Postgres + Keycloak + mailpit): ~1 GB resident.
- `--profile full` (+ backend post-S-001): ~1.5 GB.
- Forward warning: observability stack (ADR 0011, S-032/033/034) adds ~1.5 GB → ~3 GB total. README: 8 GB minimum, 16 GB recommended; observability profile is opt-in.
- Resource limits: NO `deploy.resources.limits.memory` in dev compose (kills DX with headroom). Prod overlay only: postgres 1 GB, keycloak 1 GB, mailpit/postfix 64 MB, backend 768 MB.

### Performance test plan
- **Cold-start gate (CI):** `time compose up -d --wait`. Pass: ≤ 90s cold, ≤ 30s warm. Two-consecutive-breach → introduce image pre-pull caching before escalating.
- **Cold-start gate (dev laptop):** reported once in story evidence on reference hardware (8 GB / 4 vCPU); ≤ 30s warm.
- **Footprint sanity (informal):** `docker stats --no-stream` 30s after `up --wait`; record idle RSS per service; flag > 1.5 GB default or > 2 GB full.
- **Healthcheck convergence:** poll `compose ps --format json` until `health=healthy` per service. Long pole diagnosis.
- **Backend reach-time** (forward, S-001 enforces): `curl /actuator/health/readiness` from `compose --profile full up`; ≤ 75s cold, ≤ 45s warm. Captured in S-001's evidence.

### Tuning hints (defer unless needed)
- Keycloak: `JAVA_OPTS_APPEND=-Xms256m -Xmx512m`.
- Postgres: `shared_buffers=256MB`, `work_mem=16MB`.

## Open design questions

1. **Backend `image:` + `build:` dual shape vs. image-only?**
   - Dual (`image:` + `build:`) lets `compose --profile full up` build-if-missing in dev, while prod overlay drops `build:` to enforce pull-only. Operator preference — flag for sign-off.
2. **Single Postgres shared between app and Keycloak in prod, or separate?**
   - Overlay sketches separate `kc-postgres` (safer default). Operator can collapse if preferred. ADR 0007 open item.
3. **Compose-only auto-heal on healthcheck failure?**
   - Compose doesn't restart on health regression; K8s does when S-046 lands. If SLO bites before, add `willfarrell/autoheal` sidecar. Flag, don't fix.

<!-- modernize-refine: end -->

## Notes
This story may precede S-001 in calendar time if a contributor wants to bring up the DB + IdP before writing app code — the dependency arrow in the graph is "no hard dep." But in _ORDER.md it sequences after foundational stories.
