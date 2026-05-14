---
id: S-067
title: Optimistic-concurrency strategy on Flight (ETag / version column)
epic: E-07
status: todo
depends_on: [S-058]
acceptance:
  - `flight.version` `@Version` column added (or `etag` derived).
  - PUT endpoints accept `If-Match: <version>` header; 412 Precondition Failed on mismatch.
  - SPA forms include the version in mutations.
  - A test simulates two clients editing the same flight; second commit gets 412.
estimate: M
adr_refs: [0005]
parity_test: none
---

## Context
R14 callout: concurrent-edit behavior is untested in legacy. New system should handle it properly from the start — Flight is the highest-frequency editable entity.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Add `@Version` to Flight; Flyway migration for the column.
- [ ] Controller PUT methods accept and honor `If-Match`.
- [ ] OpenAPI documents the header + 412 response.
- [ ] SPA forms pass the version through.

## Notes
Apply this to other high-edit entities in their respective stories (Aircraft, Reservation, PlanningDay) if the operator wants — for now scoped to Flight.
