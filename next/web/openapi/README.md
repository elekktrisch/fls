# OpenAPI snapshot + TS client codegen

- `openapi.json` is the committed OpenAPI 3.1 snapshot of the live server contract. Source of truth for the SPA-generated TS client. Refresh: `cd ../../server && ./gradlew generateOpenApiSnapshot`.
- The TS client (Angular `HttpClient` services + signal-first `httpResource` hooks) lives under `../src/app/api/generated/`. Refresh: `cd .. && pnpm run generate-api`.
- Tool: **orval** (`client: 'angular'`, `mode: 'tags-split'`, `retrievalClient: 'both'`). Config: `../orval.config.ts`.
- Both the snapshot and the generated client are committed. CI fails if either is stale — server side via `OpenApiSnapshotIT` + `./gradlew compareOpenApiSnapshot`; client side via `pnpm run generate-api && git diff --exit-code` in `.github/workflows/ci.yml`.
- Developer flow: change controller / DTO → `./gradlew generateOpenApiSnapshot` → `pnpm run generate-api` → commit all three together (controller, snapshot, client).
