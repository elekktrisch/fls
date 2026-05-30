---
id: S-141a
title: Re-enable OpenApiSnapshotIT after regenerating openapi.json for bundle endpoints
epic: E-15
status: todo
depends_on: [S-141]
integration_base: integration/migration
origin: implementation-followup
kind: snapshot-regen
acceptance:
  - `alpenflight/web/openapi/openapi.json` is regenerated from the live springdoc spec (run `./gradlew generateOpenApiSnapshot` or `ALPENFLIGHT_OPENAPI_REFRESH=true ./gradlew test --tests OpenApiSnapshotRegenerationIT`) and includes the new `/api/v1/migrations/{uploadId}/bundle` + `/status` endpoints plus the `MIGRATION_INGEST_*` AuditAction enum values.
  - `OpenApiSnapshotIT.snapshotMatchesLiveSpec` is re-enabled (drop the `@Disabled` annotation added at S-141 PR time).
  - CI green on the `ci` workflow.
estimate: XS
refined: true
refined_at: 2026-05-30
---

## Context

S-141 added two new REST endpoints (POST /api/v1/migrations/{uploadId}/bundle + GET /api/v1/migrations/{uploadId}/status) and three new `AuditAction` enum values. The committed OpenAPI snapshot at `alpenflight/web/openapi/openapi.json` was hand-patched by a node script in S-141's PR because the sandbox gradle daemon could not run the regen task (daemon launch hung). The hand-patch passes JSON validation but diverges subtly from springdoc's exact emit, so `OpenApiSnapshotIT.snapshotMatchesLiveSpec` is left disabled at S-141 PR time.

This story regenerates the snapshot from a clean springdoc run on an environment where gradle works (the operator's Windows machine), and re-enables the IT.
