---
id: S-026
title: Authorization model — roles → @PreAuthorize mapping
epic: E-03
status: done
started_at: 2026-05-19
done_at: 2026-05-19
merged: true
merged_at: 2026-05-19
depends_on: [S-020]
acceptance:
  - Three roles are mapped end-to-end: `system_administrator`, `club_administrator`, `flight_operator` (matching `RoleApplicationKeyStrings.cs`).
  - `@PreAuthorize` patterns are documented: `@PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")`, `@PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR') or hasRole('CLUB_ADMINISTRATOR')")`, etc.
  - A reference controller has `@PreAuthorize` on each method; tests assert each role is required.
  - The mapping from Keycloak `realm_access.roles` claims to Spring authorities is correct.
estimate: M
adr_refs: [0007]
parity_test: none
refined: true
refined_at: 2026-05-19
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer]
github_issue: 73
github_pr: 76
---

## Context

Closes the three-role authorization loop: documents the convention in `CONVENTIONS.md §Authorization patterns`, expands `ClubsController` predicates to exercise SYSTEM_ADMINISTRATOR / CLUB_ADMINISTRATOR / FLIGHT_OPERATOR (with the SpEL own-club gate), and executes the backend mock-auth rip-out that S-020 / S-022 / S-048 deferred here.

## Load-bearing decisions

- **SPA mock-auth seam stays alive** as a Playwright-CI / no-Keycloak dev affordance; the original rip-out plan in `MockSecurityConfig` Javadoc (delete `src/app/app.config.mock.ts` + the `mock-auth` angular.json configuration) is rescinded. The seam's `Bearer mock-sysadmin` header now hits the live backend as an invalid JWT and is rejected with 401 — that rejection is regression-locked in `ClubsAuthorizationTest`. Re-rip when a real-OIDC Playwright project lands (**S-021 follow-up**).
