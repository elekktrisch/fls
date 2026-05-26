---
id: S-169
title: Users — JIT projection on first authenticated login
epic: E-06
status: todo
estimate: S
parity_test: none
depends_on: [S-052]
adr_refs: [0007, 0022]
refined: false
origin: scope-split
origin_story: S-052
---

## Context

Split off from [S-052](implemented/S-052-users-crud.md). S-052 shipped a
`UsersService.materializeFromJwt(...)` method that creates a local `user`
row from JWT claims; this story wires the call into the
JWT-to-tenant-resolution flow so the projection actually fires.

The two flows that depend on this:
- **S-028 bulk-provision tenant users** writes Keycloak users with `clubId`
  attributes; the local `user` row is created on each user's first login.
- **Federated IdP** (Google etc., S-134) carries roles+clubId in the JWT
  but never round-trips through the invite flow.

## Acceptance criteria

- On any authenticated request, if the JWT carries `sub` (UUID) +
  non-empty `realm_access.roles[]` + a parseable `clubId` claim, and no
  active `user` row matches `keycloak_sub`, a row is materialised before
  the controller dispatches.
- The materialise path uses `UsersService.materializeFromJwt` (already in
  place from S-052) so the aggregate invariants + audit trail fire.
- The transaction model avoids recursing through Hibernate's session-open
  hook (`UserPrincipalLookup` is currently called from
  `ClubTenantIdentifierResolver.resolveCurrentTenantIdentifier`). Cleanest
  is a Spring Security filter or `OncePerRequest` filter that runs after
  JWT decode but before controller dispatch, wrapping the materialise in
  `@Transactional(propagation = REQUIRES_NEW)`.
- Concurrent first-login requests for the same `sub` resolve to a single
  row (DB partial-unique on `keycloak_sub` is the structural net; the
  service-layer `findActiveByKeycloakSub` + `save` race-loses gracefully).
- IT: `UsersJitFirstLoginIT` — first request with a fresh sub creates one
  row; second request reuses it; two concurrent first-login requests with
  the same sub produce exactly one row.

## Notes

- The legacy seed `V8__dev_user_seed.sql` stays in place until this story
  has shaken out one full dev bring-up cycle without it (carried over
  from S-052's rip-out plan).
- This is a backend-only story. SPA UI (S-168) does not depend on it.
