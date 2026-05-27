---
id: S-172
title: infra compose group — Mailpit as shared dependency + script split
epic: E-05
status: in_progress
started_at: 2026-05-27
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

## Design notes

### Cross-story contracts (produces)
- **Shared network:** `alpenflight_shared` (driver `bridge`, `external: true`).
- **Project:** `alpenflight-infra`. Mailpit today; future infra services (log aggregator etc.) join the same `infra` profile + network.
- **Script chain (orchestrator order):** `dev-up-infra.sh` → `e2e/scripts/dev-up.sh` → `e2e/scripts/seed.sh` → `dev-up-alpenflight.sh`. Chain with `&&`, not `;`.
- **Mailpit endpoints:** host `127.0.0.1:1025` (SMTP) + `127.0.0.1:8025` (HTTP/UI); in-network DNS `mailpit:1025` / `mailpit:8025` from any service joined to `alpenflight_shared`.

### Pre-flight pattern (in every `dev-up-*.sh` AND `rebuild-keycloak.sh`)
```
docker network inspect alpenflight_shared >/dev/null 2>&1 \
  || docker network create alpenflight_shared --driver bridge
```
Inspect-first beats AC line 32's `... 2>/dev/null || true` — the latter swallows real daemon errors (rootless-permissions, daemon down) that surface hours later as silent SMTP DNS failures. `dev-up-alpenflight.sh` adds a driver-drift check: if the network exists with a non-`bridge` driver, refuse with `"remove with: docker network rm alpenflight_shared && retry"`.

### Idempotency + tear-down
- Re-running any `dev-up-*.sh` on a green stack is a no-op (`up -d --wait` is idempotent). Recovery from corrupt state: `down -v` on that project alone; never tear `alpenflight-infra` to fix a downstream issue.
- **No new `dev-down-*.sh` scripts.** `docker compose -p <proj> down [-v]` is sufficient. Order: `alpenflight-dev` → `fls-e2e` → `alpenflight-infra` (reverse leaves orphan containers attached to a removed network).
- **`alpenflight_shared` is never removed by any script.** Operators run `docker network rm alpenflight_shared` manually when retiring the dev stack entirely. Document the rule in `alpenflight/ops/README.md` § Tear-down.
- `e2e/scripts/dev-down.sh:27` banner comment needs update (no longer touches Mailpit).

### `dev-up-full.sh` orchestrator
~30 lines (not 10 — AC's "10 lines" is a target, not contract): preserve `set -euo pipefail` + `log()` helper + the `==> Dev stack ready` heredoc summary (`dev-up-full.sh:78-101`). The summary is the operator's success signal; losing it hurts the dev loop. Drop the inline Flyway block (it moves into `dev-up-alpenflight.sh`).

### Health-wait timeouts
- `dev-up-infra.sh`: `--wait --wait-timeout 30` (Mailpit healthy <5s; 30s is comfortable headroom).
- `dev-up-alpenflight.sh`: keep 240s (Keycloak `start_period: 30s` + first-build cost). Cross-project wait is **not** automatic — `--wait` only covers services in the current `-p` project. Chain with `&&` so a failed infra bring-up aborts target bring-up.
- `dev-up-alpenflight.sh` fails fast if `alpenflight_shared` is missing (operator ran it alone against a clean box).

### `lint-compose.sh` updates (`alpenflight/ops/lint-compose.sh`)
- Line 32-34: bump `--profile next` to `--profile next --profile infra` so Mailpit is visible.
- Line 24: add `mailpit` to `NEW_STACK_SERVICES`. Already pinned `:v1.21` + 127.0.0.1 binding satisfies rules 2+3; rule 1 (healthcheck) already in `docker-compose.yml:68-77`. Drop the "Legacy services exempt" note for mailpit.
- `.github/workflows/compose-lint.yml`: same profile flag.

### `e2e/scripts/dev-up.sh` modification scope
- In-scope. `e2e/` is the AlpenFlight test harness, not legacy upstream (CLAUDE.md's reference-only rule applies to `flsserver/` and `flsweb/`). Call this out in the PR description so reviewers don't flag it.

### Profile gate stays
Keep `profiles: ["infra"]` on Mailpit. Symmetry with `profiles: ["next"]`; prevents accidental default-up. **Profile-union footgun recurs:** `--profile infra` under `-p alpenflight-dev` would pull Mailpit in and double-bind 1025/8025. Encode the rule (`infra` profile only ever activated under `-p alpenflight-infra`) in the lint pre-flight comment and `alpenflight/ops/README.md` profile-matrix.

### Schema check (ADR 0022 directive 2)
- N/A — pure devops, no DB schema change.

## Edge cases & hidden requirements

- **Mid-session network removal:** if operator `docker network rm alpenflight_shared` while containers are attached, compose has no watchdog — containers lose DNS silently. Document "do not rm the shared network while any project is up"; recovery is a full stack down/up.
- **mssql on `alpenflight_shared`:** harmless today (legacy server runs out-of-compose, hits Mailpit via `localhost:1025`). Story Notes line 68 declares intent; document in `alpenflight/ops/README.md` as "shared network is the canonical cross-project DNS plane; new containerized services join it" so the future-contract isn't ambiguous.
- **Mailpit data continuity at cutover:** moving Mailpit from `fls-e2e` to `alpenflight-infra` drops the anonymous in-memory volume on first migration. Non-issue (Mailpit is a sink) but worth one line in the PR description for anyone with an in-flight verify link.
- **Script `-p` discipline:** each script declares its `-p` at the top and `up`s only services in its own project. Three projects, three project names, zero overlap. `dev-up-alpenflight.sh` keeps naming services explicitly (`up -d postgres pgadmin keycloak`) — never `--profile next,infra`.
- **Windows / MSYS quirks:** new scripts mirror the CRLF-strip + path-translation defenses already in `e2e/scripts/dev-up.sh:60-72` (`tr -d '\r'`, MSYS auto-translation). Copy, don't re-derive.
- **`rebuild-keycloak.sh` impact (`alpenflight/ops/rebuild-keycloak.sh:30`):** now relies on `alpenflight_shared` existing. Add the same pre-flight `inspect` pattern so a fresh-clone rebuild after `down -v` doesn't fail with a confusing "network not found".
- **Doc drift footprint (single PR):**
  - `alpenflight/ops/README.md` — profile-matrix (`:54-67`), bring-up (`:22-32`), footgun note (`:60-67`), project-naming (`:46-50`), Tear-down section (`:97-105`).
  - `alpenflight/auth/README.md` — Bring-up + Topology sections.
  - `docker-compose.yml:27-33` + `dev-up-full.sh:97-100` + `e2e/scripts/dev-up.sh:1-39` banner comments.

## Security plan

(N/A — pure devops; no auth surface, no PII, no new external surface. Mailpit's compose project change preserves the existing `127.0.0.1` bind hygiene; `lint-compose.sh` rule 3 continues to enforce. Cross-project DNS via `alpenflight_shared` is bridge-driver-local to the host and not LAN-reachable.)

## Test plan

- **Pyramid:** static lint (`alpenflight/ops/lint-compose.sh` + `.github/workflows/compose-lint.yml`) + a new CI shared-network smoke job + operator-manual smokes per the story's "Smoke" subsection + the downstream S-174 e2e which exercises the contract end-to-end. No unit / Spring / Playwright specs in this story.
- **Parity:** N/A — no legacy oracle.
- **CI automates** (new job in `compose-lint.yml`, wall-clock <30s):
  1. `docker network inspect alpenflight_shared >/dev/null 2>&1 || docker network create alpenflight_shared --driver bridge`
  2. `docker compose -p alpenflight-infra --profile infra up -d --wait --wait-timeout 30 mailpit`
  3. `curl -fsS http://localhost:8025/api/v1/info`
  4. Re-run step 2 → assert no port collision, exit 0 (idempotency proof).
  5. `docker compose -p alpenflight-infra down -v` → assert `docker network inspect alpenflight_shared` still succeeds (network survives).
  6. Cleanup via `if: always()` step: `docker network rm alpenflight_shared || true`.
- **`lint-compose.sh` delta:** add `--profile infra` to the existing `compose config parses` step at `.github/workflows/compose-lint.yml`. AC: lint exits 0 with both profiles enabled.
- **Operator-manual smokes** (story AC line 47-52, kept manual — CI has no Mono / no signup flow): full `dev-up-full.sh` on clean box; `dev-up-alpenflight.sh` against already-running infra; Mailpit shows verify-email from `/signup` round-trip; tear-down asymmetry (`down -v` on `alpenflight-infra` doesn't dump `alpenflight-dev` containers' network attachment).
- **Doc-drift DoD check:** README sweep + banner-comment sweep is part of definition-of-done; reviewer panel catches the rest.

## Performance plan

(N/A — pure devops; no hot path, no query budget. Wall-clock budgets are operational (Mailpit 30s `--wait`, target 240s `--wait`) and codified in the design notes, not separately measured.)

<!-- modernize-refine: end -->
