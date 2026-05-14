---
id: S-022
title: ClubTenantIdentifierResolver + @TenantId plumbing on first entity
epic: E-03
status: todo
depends_on: [S-012, S-015, S-020]
acceptance:
  - `ClubTenantIdentifierResolver` (Hibernate `CurrentTenantIdentifierResolver`) reads the authenticated principal's `clubId` claim.
  - Hibernate is configured with `multi_tenancy=DISCRIMINATOR` and the resolver bean.
  - `@TenantId` is applied to a worked example entity (recommend: `Club`-scoped entity like `Location` once S-049 is in flight; for this story, a placeholder entity is fine).
  - A test executing a `findAll()` against the example entity under different tenant contexts returns different result sets.
  - Test fixtures (S-015) successfully default to a known tenant before running queries; without a context, queries throw a clear error (or return empty per chosen policy).
estimate: M
adr_refs: [0008]
parity_test: none
---

## Context
ADR 0008's core plumbing. Once this lands, *every* tenant-scoped entity added afterwards just needs `@TenantId` on its `club_id` column.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Configure Hibernate properties for discriminator multi-tenancy.
- [ ] Implement `ClubTenantIdentifierResolver` reading from Spring Security context.
- [ ] Apply `@TenantId` to one entity end-to-end as a worked example.
- [ ] Wire a `@WithTenant(clubId)` test helper that sets the security context with a JWT-shaped principal carrying the `clubId` claim.
- [ ] Document the convention in `next/server/docs/multi-tenancy.md`.

## Notes
The first entity to wear `@TenantId` is the worked example — likely `Location` or `Club` itself. The pattern then propagates to E-06+ stories.
