# alpenflight/ops — operator manual

Dev-stack bring-up + housekeeping for the AlpenFlight (alpenflight/) rewrite.
Mirrors ADR 0010's deployment-stack decisions in their dev-laptop form.

## What lives where

| Path | Purpose |
|---|---|
| `/docker-compose.yml` (repo root) | Single compose file, three compose projects. Default profile holds legacy `mssql`. `--profile infra` adds shared `mailpit`. `--profile next` adds new-stack services (`postgres`, `pgadmin`, `keycloak`). All services attach to the external network `alpenflight_shared`. |
| `alpenflight/ops/dev-up-infra.sh` | Shared network + Mailpit. |
| `alpenflight/ops/dev-up-alpenflight.sh` | Postgres + pgAdmin + Keycloak + Flyway migrations. |
| `alpenflight/ops/dev-up-full.sh` | Thin orchestrator: infra + legacy + seed + alpenflight. |
| `alpenflight/ops/dev-up-nocompose.sh` | Compose-free fallback: Keycloak + Mailpit via plain `docker run`, Flyway against an **external** Postgres (`DATASOURCE_*`). For boxes without the compose v2 plugin, where the scripts above hard-fail on `require_compose_v2`. |
| `alpenflight/ops/rebuild-keycloak.sh` | Rebuild + restart Keycloak with a fresh H2 volume. |
| `alpenflight/ops/pgadmin/` | Custom pgAdmin image (server connection pre-wired). |
| `alpenflight/ops/lib/fail-loud.sh` | Sourced helpers: compose-v2 preflight, `compose_up_or_die`, `run_step_or_die`. Every bring-up aborts naming the service that did not start. |
| `alpenflight/ops/lint-compose.sh` | Static checks (healthcheck, floating tags, loopback binds). Run in CI via `.github/workflows/compose-lint.yml`. |
| `alpenflight/ops/test-bring-up-guards.sh` | Proves the bring-up scripts fail loudly (stubbed `docker`, no daemon), that the fan-out starts Mailpit before the legacy e2e suite, and that every site which bakes or asserts the Keycloak client `baseUrl` names the port `playwright.config.ts` serves the real-OIDC SPA on. Run in CI via `compose-lint.yml`. |
| `alpenflight/ops/.env.example` | Dev-only env overrides; copy to `.env` if you need them. Most contributors won't. |
| `alpenflight/ops/.env` | **Gitignored.** Local overrides only. |

The prod overlay (`docker-compose.prod.yml`) is deliberately **not** here yet —
deferred until the first deploy story (S-041). Dev + CI is the only target
for now.

## First-time bring-up

```bash
# Everything (infra + legacy + seed + new + migrations) in one shot.
bash alpenflight/ops/dev-up-full.sh

# Or any slice individually:
bash alpenflight/ops/dev-up-infra.sh           # shared network + mailpit
bash alpenflight/ops/dev-up-alpenflight.sh     # postgres + pgadmin + keycloak + flyway
bash e2e/scripts/dev-up.sh                     # legacy mssql
```

Each `dev-up-*.sh` is idempotent and inspects-first for the
`alpenflight_shared` network — a fresh-box first run creates it; subsequent
runs reuse it. `dev-up-alpenflight.sh` and `e2e/scripts/dev-up.sh` fail
fast if the network is missing and direct you to `dev-up-infra.sh`.

### No compose plugin

`require_compose_v2` aborts every script above when only the docker CLI is
installed. That is a missing plugin, not a missing engine — the real-idp
stack still runs:

```bash
source ~/.bashrc                                # DATASOURCE_* → the LAN Postgres
bash alpenflight/ops/dev-up-nocompose.sh        # keycloak + mailpit + flyway
```

Postgres is external by design (a local Postgres container OOMs the 2-core
dev box); legacy MSSQL is out of scope. The script prints the backend +
Playwright follow-up commands — run them in order, never Gradle and
Playwright at once.

## Tear-down

**Order matters: target → legacy → infra.** Reverse order leaves orphan
containers attached to a removed-and-recreated network.

```bash
docker compose -p alpenflight-dev   down [-v]
bash e2e/scripts/dev-down.sh
docker compose -p alpenflight-infra down [-v]

# Only when retiring the dev stack entirely:
docker network rm alpenflight_shared
```

The `alpenflight_shared` network is `external: true` — no compose project
owns its lifecycle. **Never `docker network rm` it while any project's
containers are still attached** — compose has no watchdog, the containers
silently lose DNS, recovery is a full stack down/up.

## Project naming

Three compose projects share the single root `docker-compose.yml`:

- **`fls-e2e`** — legacy stack (`mssql`). Historical name matches the brand
  (`fls-`) of the system being modernized away from. Managed by
  `e2e/scripts/dev-up.sh` / `dev-down.sh`.
- **`alpenflight-infra`** — shared infrastructure (`mailpit`; future: log
  aggregator, etc.). Managed by `dev-up-infra.sh`.
- **`alpenflight-dev`** — new-stack services (`postgres`, `pgadmin`,
  `keycloak`). Managed by `dev-up-alpenflight.sh`.

All three attach their services to the external `alpenflight_shared`
network — that's the canonical cross-project DNS plane. Keycloak (in
`alpenflight-dev`) reaches Mailpit (in `alpenflight-infra`) via
`KEYCLOAK_SMTP_HOST=mailpit` because both are on `alpenflight_shared`.

## Profile matrix

| Invocation | What starts | Use case |
|---|---|---|
| `bash e2e/scripts/dev-up.sh` | legacy `mssql` under `-p fls-e2e` (asserts shared network + Mailpit reachable) | Legacy Playwright suite. |
| `bash alpenflight/ops/dev-up-infra.sh` | `mailpit` under `-p alpenflight-infra --profile infra` | Shared mail sink for Keycloak verify-email + legacy server SMTP. |
| `bash alpenflight/ops/dev-up-alpenflight.sh` | `postgres` + `pgadmin` + `keycloak` under `-p alpenflight-dev` + Flyway migrate | **Default new-stack dev loop.** Backend + SPA run from the IDE / dev server. |
| `bash alpenflight/ops/dev-up-full.sh` | All four projects in order + legacy DB seed | Comparing legacy vs new side-by-side. |

**Profile-union footgun:** `--profile X` is a *union* with the default
profile within the same compose project. The rule across all three
projects: **each profile is only ever activated under its matched
project name** — `--profile infra` only with `-p alpenflight-infra`,
`--profile next` only with `-p alpenflight-dev`. Mixing them
(e.g. `-p alpenflight-dev --profile infra`) would pull Mailpit into
`alpenflight-dev` and double-bind 1025 / 8025. `dev-up-alpenflight.sh`
also names services explicitly (`up -d postgres pgadmin keycloak`) so
it never pulls mssql in from the default profile.

## Service endpoints (dev)

| Service | URL / port | Credentials |
|---|---|---|
| Postgres (new stack) | `localhost:5432` / db `alpenflight` | `alpenflight` / `alpenflight` |
| pgAdmin | http://localhost:5050 | `dev@example.com` / `dev` |
| Keycloak admin console | http://localhost:8090 | `admin` / `admin` |
| Keycloak issuer (token `iss`) | `http://localhost:8090/realms/alpenflight` (post-S-019) | — |
| Keycloak management (health) | http://localhost:9090/health/ready | — |
| Mailpit Web UI | http://localhost:8025 | — |
| Mailpit SMTP (backend → here) | `localhost:1025` | accept-any |
| MSSQL (legacy) | `localhost:1433` | `sa` / `Demo#FLS#2026` |

All ports bind to `127.0.0.1` — nothing is reachable from the LAN.

## Footguns

- **Keycloak on 8090, not 8080.** The AlpenFlight backend (`alpenflight/server`)
  defaults to 8080 itself, so Keycloak's HTTP listener is published on
  host port 8090 to avoid a collision. The `KC_HOSTNAME_URL` env on the
  service pins issuer URLs to `http://localhost:8090` so the `iss` claim
  in tokens matches what the SPA + backend both see from the host.

- **No realm imported yet.** S-019 lands `alpenflight/auth/realm-export.json`
  and amends `docker-compose.yml`'s `keycloak` block to add
  `--import-realm` + the bind-mount. Until then, create the
  `alpenflight` realm by hand via the admin console.

- **Postgres data is ephemeral.** The new-stack `postgres` service has no
  named volume. `docker compose down` (or `down -v`) wipes the DB; rerun
  `dev-up-full.sh` to re-apply Flyway migrations. Add a named volume here
  if you want survival across `down`.

- **Mailpit is shared across both stacks.** Mailpit lives in
  `alpenflight-infra` and reaches Keycloak by service DNS over
  `alpenflight_shared`. The legacy stack (Playwright suite) and the new
  stack (Spring Boot `JavaMailSender`) both target `localhost:1025`. One
  inbox; expect legacy + new mails interleaved during a side-by-side
  bring-up.

- **Port collisions.** Default ports are `5432` (Postgres), `5050`
  (pgAdmin), `8090` (Keycloak HTTP), `9090` (Keycloak mgmt), `8025`
  (Mailpit UI), `1025` (Mailpit SMTP). Override by editing
  `docker-compose.yml` directly for now — the env-overridable form lands
  with `.env` defaults later if/when the need shows up.

- **Don't `down -v` in production.** When this stack reaches a hosted
  environment (S-041 / S-046), volume removal is forbidden in runbooks —
  the recovery path is backups (S-042).

## CI guards

- `.github/workflows/compose-lint.yml` — runs `alpenflight/ops/lint-compose.sh`
  with both `--profile next` and `--profile infra` enabled (every service
  has a healthcheck; no `:latest` on new-stack services including mailpit;
  new-stack data ports bind to `127.0.0.1`). A sibling `shared-network-smoke`
  job covers the network round-trip: inspect-first create → `up --profile
  infra` → host-port probe → re-up idempotency → `down -v` leaves the
  network intact.
- `.github/workflows/compose-smoke.yml` — brings up infra
  (`mailpit` under `-p alpenflight-infra --profile infra`) followed by
  the new-stack services (`postgres pgadmin keycloak` under
  `-p alpenflight-dev`), runs the same functional probes that pass
  locally (`psql SELECT 1`, `keycloak /realms/master`, `mailpit /api/v1/info`,
  `pgadmin /misc/ping`) plus `check-keycloak-integration.sh` for the
  end-to-end verify-email round-trip over the shared network.

Both workflows are gated to `docker-compose.yml` + `alpenflight/ops/**` +
`.github/workflows/compose-*.yml` to keep PRs that don't touch the stack
quick.

There is exactly **one** `docker-compose.yml` in the repo, at the root.
Three compose projects share it, gated by the default / `infra` / `next`
profiles.
