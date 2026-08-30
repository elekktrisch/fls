---
id: S-172
title: infra compose group — Mailpit as shared dependency + script split
epic: E-05
status: done
started_at: 2026-05-27
done_at: 2026-05-27
merged: true
merged_at: 2026-05-28
estimate: S
depends_on: []
integration_base: integration/users-suite
refined: true
refined_at: 2026-05-27
refined_specialists: [requirements, solution, qa]
github_issue: 151
github_pr: 152
origin: punch-list
---

## Context

Today Mailpit lives in `docker-compose.yml`'s default profile under the `fls-e2e` compose project (started by `e2e/scripts/dev-up.sh`). The AlpenFlight Keycloak (under the `alpenflight-dev` project) reaches it via the **service DNS name** `mailpit`, which only resolves when both services share a compose network — currently they don't, and the SMTP delivery path silently relies on the operator bringing the legacy stack up *first*. Tear down `fls-e2e` and Keycloak's `verifyEmail` flow breaks with no obvious error.

`alpenflight/ops/dev-up-full.sh` is a single 100-line script that wraps legacy bring-up, seed, target bring-up, and Flyway migrate. As the rewrite grows it makes more sense to run just one slice (e.g. only the new stack against an already-running infra).

This story does two things:

1. Promotes Mailpit (and any future shared infra — log aggregator, etc.) into its own compose project `alpenflight-infra` reachable from both legacy and AlpenFlight via a declared external network.
2. Splits `dev-up-full.sh` into three single-purpose scripts plus a thin orchestrator.

## Acceptance criteria

### Compose topology

- [ ] `docker-compose.yml` declares an external network `alpenflight_shared` at the top level.
- [ ] Mailpit's compose entry moves to a new `infra` profile, joins `alpenflight_shared`, and stops being co-located with mssql in the `fls-e2e` default profile.
- [ ] `mssql` (legacy) joins `alpenflight_shared` so the legacy server (when containerized later) could reach Mailpit by service DNS.
- [ ] `keycloak` joins `alpenflight_shared` so the existing `KEYCLOAK_SMTP_HOST=mailpit` default resolves across projects.
- [ ] A pre-flight check at the top of each `dev-up-*.sh` script does `docker network create alpenflight_shared --driver bridge 2>/dev/null || true` so first-run on a clean box just works.

### Scripts

- [ ] New `alpenflight/ops/dev-up-infra.sh` brings up Mailpit (and creates the shared network if missing). Idempotent. Tears down via `docker compose -p alpenflight-infra down [-v]`.
- [ ] New `alpenflight/ops/dev-up-alpenflight.sh` brings up Postgres + pgAdmin + Keycloak under the `alpenflight-dev` project AND runs the Flyway migrate step. Idempotent.
- [ ] `e2e/scripts/dev-up.sh` (legacy) trimmed: no longer brings Mailpit up itself — it asserts the shared network exists and that Mailpit is reachable (`curl -fsS http://localhost:8025/api/v1/info`), printing a one-line "run dev-up-infra.sh first" if not.
- [ ] `alpenflight/ops/dev-up-full.sh` becomes a 10-line orchestrator: `bash dev-up-infra.sh && bash e2e/scripts/dev-up.sh && bash e2e/scripts/seed.sh && bash dev-up-alpenflight.sh`.

### Docs

- [ ] `alpenflight/auth/README.md` "Bring up" section + Topology table updated to reference the three-script split and the shared network.
- [ ] `alpenflight/ops/README.md` documents the new layering + tear-down sequence (target → legacy → infra is safe; reverse order leaves orphan containers attached to a removed network).
- [ ] `e2e/scripts/dev-up.sh` script-banner comment trimmed: drop Mailpit from "what this brings up".

### Smoke

- [ ] From a clean box: `bash alpenflight/ops/dev-up-full.sh` succeeds end-to-end.
- [ ] `bash alpenflight/ops/dev-up-alpenflight.sh` works against an already-running infra (no need to re-up legacy).
- [ ] Mailpit at `http://localhost:8025` shows verify-email deliveries from Keycloak (`/signup` flow round-trip).
- [ ] `docker compose -p alpenflight-infra down -v` after the smoke removes Mailpit + its volume but leaves the shared network in place (so `alpenflight-dev` containers don't lose their network mid-shutdown).

## Tasks

- [ ] Add `networks: alpenflight_shared: external: true` to `docker-compose.yml` + attach to mssql / mailpit / keycloak / postgres.
- [ ] Move Mailpit to `profiles: ["infra"]`.
- [ ] Write `dev-up-infra.sh`.
- [ ] Write `dev-up-alpenflight.sh`.
- [ ] Trim `dev-up-full.sh` to an orchestrator.
- [ ] Update `e2e/scripts/dev-up.sh` (drop Mailpit step + add the assertion).
- [ ] Doc updates as above.

## Notes

- The network is *external* so neither compose project owns its lifecycle — tearing down either project must not drop the network. Operators clean it up manually when retiring the dev stack entirely.
- Keep the `KEYCLOAK_SMTP_HOST=mailpit` default; production env vars still override it. The shared network makes the dev default actually true.
- pgAdmin and the legacy MSSQL also benefit from being on `alpenflight_shared` if a future story containerizes the legacy or new server — they need to reach Mailpit by service DNS too. Adding all four to the network now is cheap.

<!-- modernize-refine: start -->

## Cross-story contracts (produced for S-174 and future infra consumers)

- **Network:** external bridge `alpenflight_shared`. No compose project owns its lifecycle; `alpenflight/ops/lib/shared-network.sh` carries the canonical `ensure_shared_network` / `require_shared_network` helpers.
- **Project + profile:** `alpenflight-infra` (`profiles: [infra]`). Mailpit today; future infra services join the same project + profile + network.
- **Script chain:** `dev-up-infra.sh` → `e2e/scripts/dev-up.sh` → `e2e/scripts/seed.sh` → `dev-up-alpenflight.sh` (chained with `&&` in the orchestrator). `dev-up-alpenflight.sh` and `rebuild-keycloak.sh` fail fast on missing network; `e2e/scripts/dev-up.sh` also pre-flights Mailpit on host:8025 before mutating MSSQL state.
- **Mailpit:** host `127.0.0.1:1025` (SMTP) + `127.0.0.1:8025` (HTTP); in-network DNS `mailpit:1025` / `mailpit:8025`.
- **Tear-down ordering:** target → legacy → infra. `docker network rm alpenflight_shared` is manual, only when retiring the stack entirely.

Performance / Security: N/A — pure devops, no schema, no auth surface. ADR 0022 directive 2 N/A. Downstream contract validation is S-174's real-IdP e2e harness; CI here covers static lint + the shared-network round-trip smoke.

<!-- modernize-refine: end -->
