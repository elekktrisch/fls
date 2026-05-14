---
id: S-025
title: Tenant-from-URL mechanism for public flows
epic: E-03
status: todo
depends_on: [S-022, S-023]
acceptance:
  - Public-flow controllers (trial-flight, passenger-flight) accept a `clubSlug` (or `clubId`) path/form parameter, validated against an allowlist of clubs that opted into public registration.
  - The tenant context is established from this parameter before the controller body runs.
  - Reject paths: invalid slug → 404; valid slug but tenant has public registration disabled → 403.
  - Audit-log entry: "public submission for club X by anonymous actor, IP Y."
estimate: M
adr_refs: [0008]
parity_test: tests/public/trial-flight-tenant-validation.spec.ts
---

## Context
ADR 0008 listed this as a follow-up. Public flows can't use the principal's `clubId` claim — there is no principal. But they target a specific club. So the tenant has to come from somewhere on the request and be validated.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Define the `Club.public_registration_enabled` boolean (S-048 owns the column; this story consumes it).
- [ ] Implement a `PublicTenantInterceptor` or similar that reads `clubSlug` and sets tenant.
- [ ] Reject invalid / disabled clubs.
- [ ] Audit-log the submission.

## Notes
Slug vs. ID: slug (e.g. `lszr`) is friendlier in URLs but requires a column. ID (uuid) is leakier. Recommend a `slug` column unique per `Club`.
