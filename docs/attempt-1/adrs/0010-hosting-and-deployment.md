# 0010 — Hosting + deployment shape

- **Status:** Accepted
- **Date:** 2026-05-14
- **Decision criteria** (from [vision §6](../02-vision-and-constraints.md#6-decision-criteria-for-phase-3)): off-EOL · Linux-first · Swiss/EU residency · solo-operator operability · lower TCO · enables fast feature dev

## Context

[C1](../02-vision-and-constraints.md#3-hard-constraints) pins Linux. [C4](../02-vision-and-constraints.md#3-hard-constraints) pins Swiss/EU data residency. [Vision §4 soft preferences](../02-vision-and-constraints.md#4-soft-preferences) prefer fewer moving parts and self-hosting on a VPS. The system to deploy consists of: a Spring Boot JAR (containerized), a Postgres 17 instance ([ADR 0002](0002-database-engine.md)), an OIDC IdP (Keycloak — [ADR 0007](0007-auth-scheme.md)), an SMTP relay (real in prod, mailpit-style in dev), and a static frontend bundle ([ADR 0004](0004-frontend-framework-and-build-tool.md)).

The operator's chosen path is **start on a VPS, migrate to Kubernetes mid- to long-term**. This is a two-phase decision: optimize for day-1 operability now, but don't bake in choices that would make the K8s migration expensive later. The right framing is "day-1 deploy on docker-compose, with 12-factor / K8s-ready hygiene from the start."

## Options considered

### Option A — Single Swiss/EU VPS + Docker Compose + Caddy/Traefik — **day-1 target**
- **Capabilities:** one VPS in Switzerland or the EU (Hetzner CH/DE, Exoscale CH, Infomaniak CH, Init7 CH, OVHcloud FR/DE). All services in a single `docker-compose.yml`: backend container, Postgres container (or socket to a same-host Postgres), Keycloak container, mailpit (dev) or Postfix relay (prod). Caddy or Traefik as the TLS-terminating reverse proxy with automatic Let's Encrypt. Daily DB backup via `pg_dump` to a Swiss object-storage bucket (Exoscale SOS, Infomaniak Swiss Backup).
- **Fit to criteria:** off-EOL ✓. Linux-first ✓. Swiss/EU residency ✓ (every named provider has CH or EU regions). Operability ✓ (single host, single compose file, single command to deploy). Lower TCO ✓ (~€20–40/mo for a 2–4 vCPU / 8 GB VPS + storage). Fast feature dev ✓ (no platform-tax between code and prod).
- **Migration cost:** low — docker-compose is the standard shape for a small-scale Linux deploy. Cutover involves provisioning the VPS, restoring the migrated DB, pointing DNS.
- **Ecosystem risk:** low — everything is portable OSS.
- **Escape hatch:** every component is already containerized, so the migration to K8s (option C) is incremental rather than a rewrite.

### Option B — Container-on-managed-platform (Fly.io / Scaleway Containers / Render)
- **Capabilities:** push a container, the platform handles scheduling, TLS, scaling.
- **Fit to criteria:** Swiss/EU residency ~ (Fly.io has Frankfurt; Scaleway is FR/EU; Render's CH/EU posture varies). Operability ✓ (less to run). TCO ~ (higher than VPS at our scale; cheaper than K8s).
- **Why not chosen for day 1:** higher cost than a single VPS for one-instance workloads; introduces platform-specific deploy idioms that would be re-learned again when moving to K8s. Doesn't add value at our scale.

### Option C — Kubernetes — **mid/long-term target**
- **Capabilities:** managed K8s (Exoscale SKS, Hetzner managed K8s in beta, GKE/EKS/AKS for EU regions), or self-managed. Declarative manifests, replicas, rolling deploys, horizontal scaling, ConfigMap/Secret management, native ingress.
- **Fit to criteria:** off-EOL ✓. Linux ✓. Residency ✓ (with EU control plane). Operability ✗ at day 1 (control plane to operate or pay for; manifest learning curve) but ✓ at scale (rolling deploys, multi-instance, observability tie-ins). TCO ✗ at day 1 (managed K8s ~€100+/mo just for the control plane on most clouds).
- **Why not chosen for day 1:** it's a 99.0%-SLO single-instance workload. K8s pays back when we want horizontal scale, multi-instance reliability, or richer deploy orchestration — none are day-1 needs.
- **Why chosen for later:** when growth or reliability needs justify it; the day-1 architecture is designed to make this migration smooth.

### Option D — Managed app + managed DB (Azure App Service / Google Cloud Run + managed Postgres)
- **Why not chosen:** re-imports vendor lock-in for compute and DB; cost is higher than VPS; residency must be verified per-service per-region.

## Decision

**Day-1 (cutover): Option A — Single Swiss/EU VPS + Docker Compose + Caddy/Traefik.**
**Mid/long-term: Option C — Kubernetes**, when scale or reliability needs justify the operational cost.

Day-1 architecture must be **K8s-ready** so the future migration is a redeploy rather than a rewrite. Concretely, every component must follow these constraints starting day 1:

1. **Twelve-factor configuration.** All config via environment variables; no host-mounted config files at runtime. Spring Boot's `application.yml` + env-override is the canonical shape.
2. **Stateless application containers.** No local file state. Anything persistent goes to Postgres, the IdP, or an object-storage backend (file uploads, exports). Spring Boot's `/tmp` is allowed for ephemeral processing only.
3. **Logs to stdout/stderr.** No log files in the container's filesystem. Caddy/Traefik collects + ships them; in K8s the same logs flow to the cluster's log aggregator.
4. **Health endpoints.** Spring Boot Actuator's `/actuator/health/liveness` and `/actuator/health/readiness` enabled and reachable; reverse proxy uses them.
5. **Graceful shutdown.** `server.shutdown=graceful` in Spring Boot + a reasonable timeout; cooperate with SIGTERM.
6. **Idempotent migrations.** Flyway ([ADR 0003](0003-schema-migration-tooling.md)) is. Jobs ([ADR 0009](0009-background-job-mechanism.md)) must be too.
7. **No assumptions about host paths.** Object-storage URLs / S3-compatible endpoints injected via env; no `/srv/files` hardcoded.
8. **One container = one process.** No init-system inside containers, no supervisord-style multi-process images.
9. **Image digests, not floating tags.** Compose file and (future) K8s manifests reference specific image digests so rollback is deterministic.
10. **Secrets injected, not baked.** Compose `.env` file out-of-tree + `chmod 600`; K8s Secrets later. Never in the image.

## Consequences

- **Positive:**
  - Cheapest deploy for the scale (€20–40/mo for the VPS + €5–10/mo for backups in Swiss object storage).
  - All-in-one host makes the cutover ([C6](../02-vision-and-constraints.md#3-hard-constraints) ≤6 hr window) simpler — one box to provision, one DB to restore, one DNS swap.
  - Day-1 hygiene rules above mean migrating to K8s later is a redeploy of the same images with different orchestration, not a re-architecture.
  - Caddy gives free automatic TLS via Let's Encrypt.
  - Compose-up is one command for local dev (matches the [`fls-e2e-setup.md` memory's](../../home/agent/.claude/projects/-c-Users-roman-IdeaProjects-fls/memory/fls-e2e-setup.md) lesson — minimize booby-traps).

- **Negative:**
  - Single point of failure: one VPS down = service down. Mitigated by the 99.0% SLO ([C-NFR](../02-vision-and-constraints.md#2-non-functional-requirements)) which budgets ~7 hrs of monthly downtime; daily off-site backups + a rehearsed restore runbook keep the catastrophic case bounded.
  - No rolling deploys at day 1 — restart of the backend container is a short downtime. Acceptable inside the SLO; mitigated by deploying during low-traffic windows.
  - Scheduled jobs ([ADR 0009](0009-background-job-mechanism.md)) tied to the single instance — no replicas to fire twice; ShedLock not strictly needed yet but stubbed for the migration.
  - K8s migration is a planned future cost — must not be forgotten as the project scales.

- **Follow-ups (other ADRs / stories implied):**
  - **Story:** scaffold `docker-compose.yml` (dev variant + prod variant) covering backend, Postgres, Keycloak, mailpit, Caddy. Commit a `.env.example`.
  - **Story:** Dockerfile for the Spring Boot service — distroless or eclipse-temurin Alpine base; non-root user; JVM flags tuned for container memory.
  - **Story:** Caddyfile / Traefik config — TLS via Let's Encrypt, routes `/`→frontend, `/api/*`→backend, `/auth/*`→Keycloak.
  - **Story:** off-site backup job — daily `pg_dump` to Swiss object storage with retention (30 day rolling + monthly archives).
  - **Story:** restore runbook + dry-run drill — rehearse end-to-end restoration into a parallel VPS before cutover.
  - **Story:** decide between Caddy and Traefik (both fine for our needs; Caddy is simpler config, Traefik integrates better with future K8s ingress).
  - **Story:** pick the VPS provider (Hetzner CH/DE, Exoscale CH, Infomaniak CH, Init7 CH, OVHcloud FR). Considerations: cost, snapshot/backup support, control panel, support quality. Phase-4 task.
  - **Story:** define the K8s-migration trigger criteria (e.g. "if we need horizontal scale OR if downtime exceeds the SLO twice in a quarter OR if we need multi-tenant isolation at network layer"). Don't migrate prematurely; have a written threshold.
  - **Story (mid-term):** Helm chart or Kustomize manifests mirroring the docker-compose topology — committed in the repo even before deployment, so the K8s migration is a tested artifact when triggered.
