---
id: S-162
# Descoped from J-1 at ship time (operator, 2026-06-03): no legacy parity exists for a
# sysadmin aircraft variant — stays todo for its own admin journey. See J-1 Parity decisions.
descoped_from: J-1
title: Sysadmin variant for Aircraft register (`/api/v1/admin/aircraft` with explicit managingClubId)
epic: E-03
status: todo
estimate: S
depends_on: [S-050, S-058]
origin: rework-meta
origin_story: S-058
kind: deferred-feature
adr_refs: [0008, 0022]
parity_test: none
refined: false
---

## Context

S-058's `AircraftAccess.canRegister` SpEL gate is deliberately
`CLUB_ADMINISTRATOR`-only: the SYSTEM_ADMINISTRATOR has no `clubId` claim in
their JWT, so the existing `POST /api/v1/aircraft` endpoint cannot infer
`managing_club_id` for sysadmin callers. This blocks the legitimate
cross-cutting use case — initial cutover-time bulk import, or a
sysadmin-driven aircraft register on behalf of a new club whose
CLUB_ADMINISTRATOR isn't onboarded yet.

The fix is an admin-flavoured register endpoint that takes `managingClubId`
as an explicit request field rather than reading it from the JWT.

## Acceptance criteria (placeholder until refined)

- `POST /api/v1/admin/aircraft` accepts the full `AircraftCreate` DTO plus a
  required `managingClubId: UUID` field. Authorized to
  `SYSTEM_ADMINISTRATOR` only.
- Same validation + audit emission as the regular endpoint.
- A sysadmin-issued JWT (no `clubId` claim) can register an aircraft for any
  target club via this endpoint.
- The regular `POST /api/v1/aircraft` continues to reject sysadmin callers
  (no claim → 403 from `canRegister`).

## Notes

- May coexist with S-028 bulk-import — that's a separate bulk-CSV / API
  contract; this story is the single-record register endpoint.
- Consider whether the admin endpoint should be JSON-only or also offer a
  pre-validated CSV variant for one-off bulk pulls during cutover.
