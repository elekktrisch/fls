---
id: S-048
title: Clubs CRUD
epic: E-06
status: todo
depends_on: [S-047, S-026]
acceptance:
  - `Club` CRUD: list (system admin only), create (system admin), update (club admin can update own), read, delete (system admin).
  - `Club.slug` column populated and unique-validated (required for S-025 public flows).
  - `Club.public_registration_enabled` flag added (S-025 consumes it).
  - Audit-log entries fire on every mutation (S-027).
  - Spec `28-club-crud.spec.ts` passes.
estimate: M
adr_refs: [0005, 0008]
parity_test: tests/masterdata/28-club-crud.spec.ts
---

## Context
Clubs are the tenant entity itself — not tenant-scoped (a club can't filter for its own existence with itself as scope). System-admin only for the list view; tenant-aware permissions for self-edit.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Entity + repository + service + controller + DTO.
- [ ] `@PreAuthorize` per method per S-026.
- [ ] Add the `slug` + `public_registration_enabled` columns.
- [ ] SPA Signal Store + edit form + list page.
- [ ] Audit-log integration.
- [ ] Spec parity verification.

## Notes
Club is *not* `@TenantId`'d itself — it's the tenant. Authorization is by role, not by tenant filter.
