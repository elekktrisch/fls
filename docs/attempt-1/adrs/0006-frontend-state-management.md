# 0006 — Frontend state management / data fetching

- **Status:** Accepted
- **Date:** 2026-05-14
- **Decision criteria** (from [vision §6](../02-vision-and-constraints.md#6-decision-criteria-for-phase-3)): team-familiar stack · solo-operator operability · enables fast feature dev · mature ecosystem · preserves sacred cows

## Context

[ADR 0004](0004-frontend-framework-and-build-tool.md) chose Angular 21 with signal-based reactivity. State falls into two buckets:

1. **Server state** — data fetched from the REST API (flights, reservations, master data, deliveries). Needs caching, invalidation on mutation, refetch on focus, optimistic updates. Dominant by volume.
2. **Client-only state** — current authenticated user + roles, UI preferences (theme, sidebar collapse), transient flow state (multi-step wizards). Small but cross-cutting.

Angular 21 offers in-framework primitives (`HttpClient` + `signal()` + `resource()`/`rxResource()`) that cover server-state with no external dependency. The ecosystem also offers TanStack Query for Angular (mature, third-party) and NgRx in two forms: the original Redux-shaped NgRx Store and the newer NgRx Signal Store (a signal-native lighter alternative).

The operator chose **NgRx Signal Store** to provide a consistent architectural shape across the application — each domain owns a store, components inject and read signals, mutations and async fetching are colocated in store methods. This trades a small concept-count increase for predictability at the application's scale.

## Options considered

### Option A — Built-in HttpClient + signals + `resource()`/`rxResource()`
- **Capabilities:** native signal-based reactive resources, declarative loading/error/value states, automatic re-fetch when input signals change. Plain signals + injected services for client-only state.
- **Fit to criteria:** mature ecosystem ~ (resource API is younger; sharp edges possible). Solo-operator operability ✓ (zero extra deps). Fast feature dev ✓ for small apps, ~ for larger apps where consistency starts mattering.
- **Migration cost:** lowest — no library to learn.
- **Ecosystem risk:** low.
- **Escape hatch:** can promote any individual feature to a Signal Store or TanStack Query later.
- **Why not chosen:** ad-hoc per-component fetching has no consistent shape; cross-cutting state (auth user, UI prefs) ends up in scattered services without an enforced pattern. The operator preferred a uniform architecture.

### Option B — NgRx Signal Store
- **Capabilities:** `signalStore()` factory creates per-domain stores with state signals, `withComputed()` for derived state, `withMethods()` for synchronous mutations, `rxMethod()` for RxJS-driven async work (HTTP, streams), `withHooks()` for lifecycle, `withEntities()` for collection state. Stores are injectable like any Angular service — `providedIn: 'root'` for app-wide singletons, scoped to a route or component for local stores.
- **Fit to criteria:** team-familiar stack ✓ (operator's choice). Mature ecosystem ✓ (NgRx is the de facto Angular state-management line; Signal Store is the modern signal-aligned variant). Fast feature dev ✓ (per-domain stores give a copy-this-shape template for every new feature). Solo-operator operability ~ (more concepts than option A; offset by predictability gains at app scale).
- **Migration cost:** medium — learn the Signal Store API. Smaller learning curve than full NgRx because there are no separate action / reducer / effect files.
- **Ecosystem risk:** low — NgRx is Google-acknowledged community-maintained, in steady release alongside Angular itself.
- **Escape hatch:** stores are isolated by domain; replacing one with `resource()` or TanStack Query is a local refactor.

### Option C — TanStack Query for Angular
- **Capabilities:** mature signal-aware port. Battle-tested cache invalidation, optimistic updates, parallel queries, dependent queries.
- **Fit to criteria:** mature ecosystem ✓. Fast feature dev ✓. Operability ~ (adds a third-party dependency for server-state caching).
- **Migration cost:** small — API surface is well-documented.
- **Ecosystem risk:** low (TanStack is well-maintained), but introduces a dependency that the framework now partially substitutes.
- **Why not chosen:** doesn't solve client-only state on its own; pairing it with Signal Store or signals separately means two state systems in the app.

### Option D — Full NgRx (Store + Effects + Selectors) + NgRx Data
- **Capabilities:** Redux-shaped — actions, reducers, effects, selectors. NgRx Data adds CRUD-shaped helpers.
- **Fit to criteria:** team-familiar stack ✓ (Angular-canonical). Solo-operator operability ✗ (boilerplate is real). Fast feature dev ✗ for a small team.
- **Why not chosen:** Signal Store gives the architectural consistency benefit without the action/reducer/effect-file boilerplate. No reason to take the cost without the corresponding benefit.

## Decision

Chosen: **Option B — NgRx Signal Store**. Provides a consistent per-domain store shape that scales predictably with the application's surface (~50 features from [current-state §2](../01-current-state.md#2-feature-inventory)). Signal-native — integrates with the framework's reactivity model rather than fighting it. Avoids the verbosity of full NgRx while preserving the architectural-consistency benefit that motivates state-management libraries in the first place. Server-state fetching lives in `rxMethod()` calls inside each domain's store; client-only state (auth, UI prefs) lives in `providedIn: 'root'` stores following the same pattern.

## Consequences

- **Positive:**
  - Every domain follows the same shape: `xxxStore = signalStore(...)` with state, computed, methods. Predictable; easy to scaffold; AI-assistable.
  - `rxMethod()` integrates RxJS where it pays (debounced filters, dependent loads) without forcing reactive style everywhere.
  - `withEntities()` is a clean fit for list-heavy domains (flights, reservations, persons) — handles selection, normalization, CRUD updates idiomatically.
  - Auth, current-club context, UI prefs all sit in `providedIn: 'root'` stores — components and route guards inject the same store consistently.
  - Stores are testable in isolation — unit tests instantiate the store and assert signal values.

- **Negative:**
  - Additional concept count vs. the built-in `resource()` API — every developer (currently the solo operator) must internalize Signal Store's lifecycle and method idioms.
  - The convention "fetching belongs in a store, not in components" must be enforced by discipline (or lint rules) — the framework doesn't compel it.
  - Documentation for Signal Store is thinner than for Redux-NgRx; some patterns (paginated lists with server-side sort/filter — common here, e.g. `PagedFlights`) need codifying as in-repo recipes.
  - Cache-invalidation strategy is not built-in like TanStack Query's keyed cache — per-store invalidation logic must be written. Mitigation: a per-store `invalidate()` method that re-runs the relevant `rxMethod`.

- **Follow-ups (other ADRs / stories implied):**
  - **Story:** scaffold a reference Signal Store (e.g. `AircraftsStore` or `FlightsStore`) that demonstrates the project's conventions: pagination via `withEntities()`, mutation methods invalidating the right resource, error/loading signals, optimistic updates where appropriate. Becomes the template all other stores follow.
  - **Story:** establish the auth/current-club store (`SessionStore`?) and the route-guard pattern that reads from it. Replaces the current `AuthService` + `userAuth` resolve-guard pattern.
  - **Story:** decide on a server-state staleness/refetch policy — Signal Store doesn't impose one; codify per-domain rules (master data: cache long, flights: refetch on visibility, deliveries: refetch on mutation).
  - **Story:** write a lint rule or code-review checklist that components only inject Signal Stores, never call `HttpClient` directly (preserves the "fetching is in stores" invariant).
  - **Story:** integrate generated OpenAPI TS clients ([ADR 0005](0005-api-shape.md) follow-up) as the HTTP layer inside Signal Store `rxMethod`s — never call generated clients from components.
