---
id: S-003
title: Wire springdoc-openapi + publish OpenAPI spec
epic: E-01
status: todo
depends_on: [S-001]
acceptance:
  - `GET /v3/api-docs` returns a valid OpenAPI 3.1 spec covering the hello endpoint from S-001.
  - `GET /swagger-ui` renders the spec and is reachable in dev.
  - The spec includes `@Operation`/`@Schema` annotations on the hello endpoint as a worked example for future controllers.
  - The spec includes the security scheme placeholder (`bearerAuth`) so codegen output handles auth correctly (S-022 fills it in).
estimate: S
adr_refs: [0005]
parity_test: none
---

## Context
Springdoc is the source of truth for the API contract that the SPA's generated TS client (S-004) consumes. Closes R5 structurally — typed enums and DTOs flow from the server.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Add `springdoc-openapi-starter-webmvc-ui` dependency.
- [ ] Document the convention: every controller method gets `@Operation(summary = ..., description = ...)`; every DTO gets `@Schema(description = ...)`.
- [ ] Add a small `OpenApiConfig` `@Configuration` defining the `bearerAuth` security scheme placeholder.
- [ ] Write a smoke test that asserts `/v3/api-docs` returns 200 and includes the hello operation.

## Notes
The spec-publication mechanism (live `/v3/api-docs` for dev vs. committed snapshot for CI reproducibility) is decided here: **both** — live for dev, snapshot committed under `next/web/openapi/` for codegen reproducibility, refreshed by a script.
