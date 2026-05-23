---
name: feedback-use-dev-up-full-not-compose
description: "For local dev bring-up always use next/ops/dev-up-full.sh (project fls-e2e) — not ad-hoc `docker compose ... up`. Avoids port collisions with the legacy stack."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: e9c21872-536a-4ded-862d-7d30fd1f1531
---

For local dev bring-up of the AlpenFlight stack, use `bash next/ops/dev-up-full.sh` — NEVER an ad-hoc `docker compose --profile next up` from the repo root.

**Why:** The wrapper brings up BOTH the legacy stack (MSSQL + Mailpit, seeded with FLSTest fixture) AND the target stack (Postgres + pgAdmin + Keycloak) under one compose project (`-p fls-e2e`), then applies all Flyway migrations. Ad-hoc `docker compose -p <other-name> --profile next up` creates a parallel container that collides with the wrapper's Postgres on `127.0.0.1:5432` — silently breaking the next attempt to run the wrapper.

**How to apply:**
- When the operator needs Postgres / Keycloak / pgAdmin up locally → suggest `bash next/ops/dev-up-full.sh`.
- For one-off `./gradlew generateOpenApiSnapshot` or migration tests during implement: still use the wrapper; don't create a second compose project just to get Postgres. If the wrapper has been torn down, re-run it rather than starting a parallel compose project.
- Tear-down: `bash e2e/scripts/dev-down.sh` (legacy) + `docker compose -p fls-e2e --profile next down` (target). Add `-v` to wipe Postgres data.
