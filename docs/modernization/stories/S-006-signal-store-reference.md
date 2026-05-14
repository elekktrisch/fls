---
id: S-006
title: Reference NgRx Signal Store + session store skeleton
epic: E-01
status: todo
depends_on: [S-002, S-004]
acceptance:
  - A reference `SignalStore` (e.g. `HelloStore`) is committed under `next/web/src/app/hello/` demonstrating: `withState`, `withComputed`, `withMethods`, `rxMethod`, `withEntities` (if list-shaped), pagination, error/loading signals.
  - A `SessionStore` (or `AuthStore`) is scaffolded with `providedIn: 'root'`; exposes signals for `authenticatedUser`, `currentClubId`, `isClubAdmin`, `isSystemAdmin`.
  - A route-guard pattern (replacing legacy `userAuth` resolve) reads from the SessionStore; documented in `next/web/src/app/auth/README.md`.
  - A lint rule or code-review checklist asserts: components only inject stores, never call `HttpClient` directly.
estimate: M
adr_refs: [0006, 0007]
parity_test: none
---

## Context
ADR 0006 chose NgRx Signal Store. Every domain follows the per-domain-store pattern. This story is the template all later domain stores copy.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Install `@ngrx/signals`.
- [ ] Build `HelloStore` against the hello endpoint from S-003 — list view, single-record view, mutation, invalidation, optimistic update.
- [ ] Build `SessionStore` returning placeholder values (real auth wiring in S-021).
- [ ] Document the per-domain refetch policy convention: master data cache-long; flights refetch-on-visibility; deliveries refetch-on-mutation.
- [ ] Write a lint rule (custom ESLint or eslint-plugin pattern) that flags `HttpClient` injection in components.

## Notes
S-021 replaces the placeholder `SessionStore` body with real OIDC. The store shape stays.
