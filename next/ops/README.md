# next/ops — operator manual

Dev-stack bring-up + housekeeping for the AlpenFlight (next/) rewrite.
Mirrors ADR 0010's deployment-stack decisions in their dev-laptop form.

## What lives where

| Path | Purpose |
|---|---|
| `/docker-compose.yml` (repo root) | Single compose file. Default profile holds the **legacy** e2e stack (`mssql`, `mailpit`). `--profile next` adds the **new-stack** services (`postgres`, `pgadmin`, `keycloak`). Mailpit is shared by both. |
| `next/ops/dev-up-full.sh` | One-shot wrapper: brings up legacy + new + seeds the legacy DB + applies Flyway migrations against the new Postgres. |
| `next/ops/pgadmin/` | Custom pgAdmin image (server connection pre-wired). |
| `next/ops/lint-compose.sh` | Static checks (healthcheck, floating tags, loopback binds). Run in CI via `.github/workflows/compose-lint.yml`. |
| `next/ops/.env.example` | Dev-only env overrides; copy to `.env` if you need them. Most contributors won't. |
| `next/ops/.env` | **Gitignored.** Local overrides only. |

The prod overlay (`docker-compose.prod.yml`) is deliberately **not** here yet —
deferred until the first deploy story (S-041). Dev + CI is the only target
for now.

## First-time bring-up

```bash
# New-stack infra only (Postgres + pgAdmin + Keycloak) — runs alongside the
# legacy mssql/mailpit since they share the default profile.
docker compose -p fls-e2e --profile next up -d --wait

# Or: everything (legacy + new + migrations + seed) in one shot.
bash next/ops/dev-up-full.sh
```

Tear down:

```bash
docker compose -p fls-e2e --profile next down              # keep volumes
docker compose -p fls-e2e --profile next down -v --remove-orphans   # nuke
```

## Profile matrix

| Invocation | What starts | Use case |
|---|---|---|
| `docker compose -p fls-e2e up -d` | `mssql` + `mailpit` | Legacy e2e Playwright suite (`e2e/`). |
| `docker compose -p fls-e2e --profile next up -d` | `mssql` + `mailpit` + `postgres` + `pgadmin` + `keycloak` | **Default new-stack dev loop.** Backend + SPA run from the IDE / dev server. |
| `bash next/ops/dev-up-full.sh` | All of the above + Flyway migrate + legacy DB seed | Comparing legacy vs new side-by-side. |

**Profile-union note:** Compose treats `--profile X` as a *union*, never an
*exclusion*. Default-profile services (mssql, mailpit) always run when you
add `--profile next` — there is no `--profile next-only` shape.

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

- **Keycloak on 8090, not 8080.** The AlpenFlight backend (`next/server`)
  defaults to 8080 itself, so Keycloak's HTTP listener is published on
  host port 8090 to avoid a collision. The `KC_HOSTNAME_URL` env on the
  service pins issuer URLs to `http://localhost:8090` so the `iss` claim
  in tokens matches what the SPA + backend both see from the host.

- **No realm imported yet.** S-019 lands `next/auth/realm-export.json`
  and amends `docker-compose.yml`'s `keycloak` block to add
  `--import-realm` + the bind-mount. Until then, create the
  `alpenflight` realm by hand via the admin console.

- **Postgres data is ephemeral.** The new-stack `postgres` service has no
  named volume. `docker compose down` (or `down -v`) wipes the DB; rerun
  `dev-up-full.sh` to re-apply Flyway migrations. Add a named volume here
  if you want survival across `down`.

- **Mailpit is shared.** The legacy stack (Playwright suite) and the new
  stack (Spring Boot `JavaMailSender`) both target `localhost:1025`. There
  is one inbox; don't be surprised to see legacy + new mails interleaved
  during a side-by-side bring-up.

- **Port collisions.** Default ports are `5432` (Postgres), `5050`
  (pgAdmin), `8090` (Keycloak HTTP), `9090` (Keycloak mgmt), `8025`
  (Mailpit UI), `1025` (Mailpit SMTP). Override by editing
  `docker-compose.yml` directly for now — the env-overridable form lands
  with `.env` defaults later if/when the need shows up.

- **Don't `down -v` in production.** When this stack reaches a hosted
  environment (S-041 / S-046), volume removal is forbidden in runbooks —
  the recovery path is backups (S-042).

## CI guards

- `.github/workflows/compose-lint.yml` — runs `next/ops/lint-compose.sh`:
  every service has a healthcheck; no `:latest` on new-stack services;
  new-stack data ports bind to `127.0.0.1`.
- `.github/workflows/compose-smoke.yml` — runs `compose --profile next up
  -d --wait` and the same functional probes that pass locally
  (`psql SELECT 1`, `keycloak /realms/master`, `mailpit /api/v1/info`,
  `pgadmin /misc/ping`).

Both workflows are gated to `docker-compose.yml` + `next/ops/**` +
`.github/workflows/compose-*.yml` to keep PRs that don't touch the stack
quick.

## Disambiguation from `/docker-compose.yml` legacy stack

There is exactly **one** `docker-compose.yml` in the repo — at the root.
It hosts both the legacy services (default profile) and the new-stack
services (`--profile next`). Earlier drafts of S-039 proposed a second
file at `next/ops/docker-compose.yml`; the operator picked the
single-file approach (2026-05-17) to avoid relocating the already-
working Postgres + pgAdmin services.
