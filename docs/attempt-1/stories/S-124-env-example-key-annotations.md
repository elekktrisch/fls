---
id: S-124
title: Annotate alpenflight/server/.env.example keys with usage notes
epic: E-01
status: todo
estimate: S
parity_test: none
depends_on: []
adr_refs: []
refined: false
origin: rework
origin_story: S-001
origin_finding: .env.example has two commented-out keys (SPRING_PROFILES_ACTIVE, SERVER_PORT) with no explanation of allowed values, defaults, or rationale; needs per-key one-liner annotations.
---

## Context

Follow-up from review of S-001 (originating story). The originating story's review found:

> `.env.example` is effectively empty — two commented-out keys (`SPRING_PROFILES_ACTIVE`, `SERVER_PORT`) with no explanation of allowed values or defaults.
> **Suggested fix:** one-line comment per key (e.g. `# Profile: dev|test|prod. Defaults to dev.`).
> **Path:** `alpenflight/server/.env.example`.

See [`S-001-scaffold-server-skeleton.md`](S-001-scaffold-server-skeleton.md#review) for full review context.

Deferred from S-001 because the file is expected to grow significantly when S-009 (Flyway) and S-019 (Keycloak) land — at which point the annotation convention needs to be set deliberately, not just for the two current keys. Bundle the annotation pattern with the first real secrets/config wiring.

## Acceptance criteria

- [ ] Every uncommented or commented-out key in `alpenflight/server/.env.example` carries a one-line comment immediately above it explaining: purpose, allowed values (or value pattern), and default behavior if unset.
- [ ] The file's preamble (top-of-file comment) describes the file's role and how it interacts with `.env` (which is gitignored) — clear enough that a contributor's first edit is informed.
- [ ] The convention is documented either in `alpenflight/server/README.md` or inside `.env.example` itself so future story authors know to follow it.
