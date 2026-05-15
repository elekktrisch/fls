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

<!-- amendment-2026-05-15b: start -->

## Amendment 2026-05-15b — Mobile-first / dense-desktop directive

Vision-doc amendment 2026-05-15b (§F11, §F15, §F12) introduces two store-level patterns this story is the right place to demonstrate:

- **AC-DIR-1 (aggressive-prefetch pattern).** The reference store doc covers the "prefetch on app start" convention: a `bootstrapPrefetch()` method on the `SessionStore` (or a sibling `AppBootstrapStore`) that, after successful auth, fires off the masterdata GETs (aircraft / persons / locations / flight-types / routes for the user's tenant) in parallel and loads them into per-domain Signal Stores. Subsequent flight-edit form opens find data in-hand and feel instant. Doc covers: (a) cancellation on logout; (b) re-fetch on tenant switch; (c) how to skip the prefetch on a public-flow route. Used by S-062c to satisfy its time-to-log NFR.
- **AC-DIR-2 (offline-aware refetch convention).** Refetch policy doc adds: when a refetch fails due to offline state, the store falls back to its IndexedDB-cached payload (if any) and surfaces `offline: true` in the store's signals. Components subscribe to `offline` to render the "offline — last refreshed at HH:mm" banner. PWA service worker (C17 / ADR 0014) owns the actual network detection.
- **AC-DIR-3 (Signal-Store-driven conditional render).** The reference form / store pair demonstrates how to drive conditional template rendering from store-derived signals (not template-side `*ngIf` cascades). Pattern: a `computed()` signal in the store exposes "is X required / visible"; template uses `@if (store.showX())`. Avoids the AngularJS-era pattern where template directives recalculate visibility per digest cycle. Consumed by S-062c for its many conditional sections (winch operator, observer, passenger, engine counters, invoice recipient, route fields).
- **AC-DIR-4 (lint rule for `MutationBus` consumption).** Existing lint rule that components only inject stores (not `HttpClient` directly) extends to: domain stores must consume cross-domain mutation events via a single `MutationBus` signal, not direct subscriptions to sibling stores. Avoids store-to-store coupling. Already implied by the refetch-on-mutation pattern; formalize in the lint.

**Refinement status flag:** Story is currently unrefined. When `/modernize-refine S-006` runs, fold these directive ACs into the reference-store + convention-doc ACs natively.

**Why here, not S-062c:** the prefetch + offline + signal-driven conditional-render patterns are conventions every domain store inherits, not flight-specific. Pinning them in S-006 keeps the per-feature stories cheap.

<!-- amendment-2026-05-15b: end -->
