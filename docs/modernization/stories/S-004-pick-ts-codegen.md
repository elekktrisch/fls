---
id: S-004
title: Pick + wire TypeScript API client codegen
epic: E-01
status: todo
depends_on: [S-002, S-003]
acceptance:
  - Codegen tool is committed: orval, hey-api/openapi-ts, or openapi-typescript-codegen.
  - A `pnpm run generate-api` (or equivalent) regenerates TS types + an Angular HttpClient service from the snapshot OpenAPI spec under `next/web/openapi/`.
  - The hello endpoint from S-001 is reachable via the generated client from a sample Angular component.
  - Generated output is committed (not gitignored) so the SPA builds without server access.
estimate: M
adr_refs: [0005]
parity_test: none
---

## Context
ADR 0005 chose REST + OpenAPI + generated TS client. The library choice was deferred to a phase-4 story — this is it. Closes R5 (FlightStateMapper enum drift) at the build-system level.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Generate sample output from each candidate (orval, hey-api, openapi-typescript) against the snapshot spec.
- [ ] Compare: readability of generated code, discriminated-union handling for `FlightAircraftType`, enum output for `FlightProcessState`, Angular HttpClient idioms.
- [ ] Pick one; document decision in `next/web/openapi/README.md` (~5 lines).
- [ ] Wire `generate-api` script into `package.json`; commit generated output.
- [ ] Smoke test: a component calls the hello endpoint via the generated client.

## Notes
Soft recommendation: **orval** (best Angular HttpClient idioms + per-endpoint hooks); fallback **hey-api/openapi-ts** (cleanest typescript output, manually integrated with Angular `HttpClient`).
