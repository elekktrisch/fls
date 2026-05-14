---
id: S-026
title: Authorization model — roles → @PreAuthorize mapping
epic: E-03
status: todo
depends_on: [S-020]
acceptance:
  - Three roles are mapped end-to-end: `system_administrator`, `club_administrator`, `flight_operator` (matching `RoleApplicationKeyStrings.cs`).
  - `@PreAuthorize` patterns are documented: `@PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")`, `@PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR') or hasRole('CLUB_ADMINISTRATOR')")`, etc.
  - A reference controller has `@PreAuthorize` on each method; tests assert each role is required.
  - The mapping from Keycloak `realm_access.roles` claims to Spring authorities is correct.
estimate: M
adr_refs: [0007]
parity_test: none
---

## Context
ADR 0007 follow-up. Every endpoint in E-06..E-09 needs to opt into the right authority level; this story establishes the convention.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Define the three roles in the Keycloak realm export (S-019 — confirm presence).
- [ ] Configure Spring Security's `JwtAuthenticationConverter` to map `realm_access.roles` to `ROLE_*` authorities.
- [ ] Apply `@PreAuthorize` to a reference controller; document the pattern.
- [ ] Write tests asserting each role's reach.

## Notes
"Permissions" (finer-grained than roles, e.g. "can edit deliveries in own club") may emerge as the rewrite progresses — for now, the three roles map cleanly to today's behavior. Don't over-engineer to permissions until a story requires them.
