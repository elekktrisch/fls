---
id: S-020
title: Spring Security 7 OAuth2 resource server wired
epic: E-03
status: todo
depends_on: [S-001, S-019]
acceptance:
  - `spring-boot-starter-oauth2-resource-server` is in the dependency graph.
  - `application.yml` references the Keycloak issuer URI (env-variable for production override).
  - The hello endpoint from S-001 requires `Authorization: Bearer <token>`; unauthenticated requests return 401.
  - A test using a valid token from the seed realm reaches the endpoint; an expired/invalid token is rejected.
  - The OpenAPI spec from S-003 includes the `bearerAuth` security scheme in its operations.
estimate: M
adr_refs: [0007]
parity_test: none
---

## Context
ADR 0007 picks the protocol; this story wires it up on the server.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Add dependency.
- [ ] Configure `spring.security.oauth2.resourceserver.jwt.issuer-uri`.
- [ ] Build `SecurityConfig` with `http.oauth2ResourceServer(jwt -> jwt.jwkSetUri(...))`.
- [ ] Default route security: `authenticated()` on `/api/v1/**`, `permitAll()` on `/actuator/health/**` and public paths.
- [ ] Map JWT claims to `Authentication.authorities` — start with `realm_access.roles` from Keycloak.
- [ ] Update springdoc-openapi config so each operation declares `bearerAuth`.

## Notes
S-026 layers role-based `@PreAuthorize` on top. This story is just "the gate is closed and JWT is required."
