# 0018 — Domain model architecture (DDD with Aggregates)

- **Status:** Accepted
- **Date:** 2026-05-16
- **Decision criteria** (from [vision §6](../02-vision-and-constraints.md#6-decision-criteria-for-phase-3)):
  2. Team-familiar stack
  5. Structural multi-tenancy supported
  6. Preserves sacred cows cleanly
  8. Enables fast feature dev post-cutover
  9. Credible migration story

## Context

The legacy system is anemic-domain: data classes are EF6 entities with public getters/setters, and all business logic lives in service classes that mutate entities through the unit of work. [Current-state §3](../01-current-state.md#3-architecture-digest) describes the pattern; the R-class risks ([R1](../01-current-state.md#r1--multi-tenancy-enforced-by-convention), R3 rules engine, R12 state machine spread) all share a root cause — invariants are enforced by discipline at the service layer, with no structural barrier to mutation paths that bypass them. The seed's sacred cows (two-dimensional flight state, time gates ≥ 2-day-lock and ≥ 3-day-bill, rules-engine semantics, glider↔tow self-FK invariants, audit-on-every-mutation) are exactly the kind of invariants Domain-Driven Design's aggregate-root pattern is built to protect.

With [ADR 0001](0001-backend-language-and-framework.md) (Spring Boot 4 + Hibernate 7) + [ADR 0008](0008-multi-tenancy-mechanism.md) (`@TenantId` discriminator) already pinned, this ADR picks the *domain-model shape* on top of that infrastructure. Hibernate 7's first-class support for aggregate roots (composite IDs, `@Embeddable` value objects, `@DomainEvents`, immutable collections via `@org.hibernate.annotations.Immutable`) makes a faithful DDD implementation realistic on the JVM. The decision also informs [ADR 0019](0019-entity-id-strategy.md) — aggregate roots are the identity surface that crosses bounded-context boundaries and benefits most from UUID identity.

## Options considered

### Option A — Full DDD: aggregate roots + value objects + domain events + per-aggregate repositories everywhere

- **Capabilities:**
  - Every bounded context has explicit aggregate roots. Initial mapping (refined per bounded context at decomposition):
    - **Flight Operations:** `Flight` (root) with `FlightCrew` as internal entities; `Aircraft` (root) with `AircraftAircraftState` + `AircraftOperatingCounter` as internals.
    - **Reservations:** `AircraftReservation` (root).
    - **Planning:** `PlanningDay` (root) with `PlanningDayAssignment` as internal entities.
    - **Accounting:** `Delivery` (root) with `DeliveryItem` as internals; `AccountingRuleFilter` (root); `DeliveryCreationTest` (root) with its items as internals.
    - **Reference & Identity:** `Person` (root, cross-tenant — sacred cow); `Club` (root); `User` (root); `Location` (root, cross-tenant — sacred cow); `FlightType` / `Article` (roots within their club's tenancy).
  - Strong-typed identifiers as value objects (`FlightId`, `AircraftId`, `PersonId`, …) — eliminates the "passed the wrong ID type" class of bug at compile time.
  - Aggregate-root methods enforce invariants atomically: `flight.assignCrew(person, FlightCrewType.Instructor)` validates state, applies the change, emits a domain event. Direct field mutation is impossible from outside the package.
  - Domain events for cross-aggregate coordination: `FlightLocked` → `DeliveryCreationJob` trigger; `DeliveryBooked` → `AuditLogAppender`; `AircraftStateChanged` → `MaintenanceNotificationJob`. Spring's `@DomainEvents` + `@ApplicationModuleListener` ([Spring Modulith](https://spring.io/projects/spring-modulith)) provide first-class plumbing.
  - Per-aggregate repositories: `FlightRepository` exposes only `findById(FlightId)` / `save(Flight)` / `delete(Flight)` — query-side projections live in read-model repositories or controller-facing query services.
- **Fit to criteria:** Criterion 6 ✓✓ (best fit for preserving sacred cows — invariants live inside the aggregate, not in service-layer discipline). Criterion 8 ✓ (new features land as new aggregate methods + domain events; existing aggregates expose narrow public surfaces that don't break on internal change). Criterion 5 ✓ (`@TenantId` discriminator from ADR 0008 lives on the aggregate root + every internal entity; `personRepository.findById(crossClubId)` still works for the sacred-cow cross-tenant case). Criterion 9 ✓ (S-016 cutover migrates row-by-row; aggregate boundaries don't constrain the data shape — only the runtime mutation paths).
- **Migration cost:** medium-high. The schema reshape (S-012/S-013/S-014) is unaffected — same tables, same columns, same FKs. What changes: every JPA entity (S-022) is now written to enforce invariants in its constructors + factory methods + mutation methods; query-side code uses read-model projections instead of full entity loads. Initial learning curve is real (operator C2: Java is the named language; DDD-on-JPA has good books). Mid-rewrite training cost ≈ 2 weeks reading + reference projects.
- **Ecosystem risk:** low. Spring + Hibernate + Spring Modulith is the canonical JVM DDD stack. Vladimir Khorikov's *Domain Modeling Made Functional* + Vaughn Vernon's *Implementing Domain-Driven Design* + Spring Modulith reference docs are the playbook.
- **Escape hatch:** aggregates are a runtime mutation pattern; the schema underneath stays orthogonal. If a specific aggregate proves too rigid, it can degrade locally to a richer anemic shape without disturbing neighbors (e.g., `AccountingRuleFilter` mutation can be exposed as a more procedural service if invariants are weak there).

### Option B — Hybrid: DDD aggregates for the 6 write-heavy domains, anemic CRUD for reference/lookup data

- **Capabilities:** Aggregate roots + invariants only where complexity lives (Flight + delivery state machine + accounting rules + reservations + planning + person/membership). Reference data (`country`, `language`, `flight_type`, `location`, `aircraft_type`) stays as plain JPA entities with public getters/setters + thin CRUD services.
- **Fit to criteria:** Criterion 6 ✓ (covers the sacred cows). Criterion 8 ✓ (less ceremony for reference-data stories S-047/S-049/etc.). Criterion 2 ✓ (smaller surface to learn at once).
- **Why not chosen:** the hybrid line is hard to defend over time. New reference-data tables that grow invariants (e.g., `FlightType.isForGlider` flag combinations) drift into "anemic with hidden rules in the service" — exactly the legacy pattern. Full DDD is more discipline up front but doesn't re-litigate per entity.

### Option C — Pragmatic DDD-lite: aggregate boundaries + rich domain methods, no domain events, no value-object explosion

- **Capabilities:** Aggregates + per-aggregate repositories + invariant-enforcing methods, but skips domain events and value-object IDs.
- **Fit to criteria:** Criterion 6 ✓ (most invariants covered). Criterion 8 ~ (cross-aggregate coordination falls back to service-layer orchestration — the legacy anti-pattern reasserts itself for cross-cutting flows).
- **Why not chosen:** Spring Modulith's domain-events plumbing is small and well-supported; the saving from dropping it is marginal. Strong-typed IDs are the highest-payoff DDD construct for the ID-mistake class of bug ([ADR 0019](0019-entity-id-strategy.md) leans on them) — skipping them defeats one of the largest wins.

### Option D — Anemic domain (legacy pattern continued)

- **Capabilities:** Data classes + service-layer logic.
- **Fit to criteria:** Criterion 6 ✗ — exactly what the R-class risks call out.
- **Why not chosen:** operator's explicit ask in this ADR's args is "DDD with Aggregates; different than legacy."

## Decision

Chosen: **Option A — Full DDD: aggregate roots + value objects + domain events + per-aggregate repositories**. Decision driven by criterion 6 (the sacred cows from the seed are textbook aggregate-root invariants; DDD is the cheapest way to make them structurally true rather than discipline-dependent), criterion 8 (per-aggregate repositories give every story a narrow integration surface — new features land as new methods on a known aggregate, not as new branches in a sprawling service), and the operator's explicit ADR-args direction. Spring Modulith + Hibernate 7 + Spring Data JPA provide the canonical JVM stack for this; the migration cost is real but front-loaded and reusable across every subsequent story.

The bounded-context mapping above is **initial**, not final. Phase-4 decomposition already split the domain into roughly the right shape; this ADR confirms the boundaries align with aggregate boundaries and commits to the pattern. Specific aggregate composition (which internal entities belong to which root, which collections are loaded with the root vs lazily, where the consistency boundary sits) is per-story design work refined at S-022 + S-058 + S-064 + S-068 + S-072.

## Consequences

- **Positive:**
  - Sacred-cow invariants become structural, not disciplinary: time gates, two-dimensional flight state, glider↔tow self-FK pairing, delivery state machine, rules-engine sort-order — all enforced inside the aggregate root.
  - Strong-typed IDs (per [ADR 0019](0019-entity-id-strategy.md)) prevent the "passed the wrong ID type" class of bug at compile time.
  - Cross-aggregate coordination has a first-class channel: Spring Modulith domain events. Audit log, delivery-job trigger, maintenance notifications, OGN-derived flight updates all become event subscribers — small, testable, decoupled.
  - `@TenantId` discriminator (ADR 0008) composes cleanly: the tenant column lives on the aggregate root + every internal entity; sacred-cow cross-tenant FK-by-ID lookups (Person, Location) are unaffected because Hibernate `@TenantId` only filters table-level queries.
  - Per-aggregate repositories restrict the integration surface: a controller / service that wants to mutate a Flight gets exactly `flightRepository.findById + flight.someMethod + flightRepository.save`. No ambient "let me also touch FlightCrew via its own repository" path.
  - Hibernate's `@org.hibernate.annotations.Immutable` + private setters + factory-method constructors make field-level mutation outside the aggregate package compile-time-impossible — discipline lifted into the type system.

- **Negative:**
  - Learning curve. The operator's C2 named Java; DDD-on-JPA is one extra book on top. Mitigated by Spring Modulith's documentation + the per-aggregate boundaries being relatively small individually (Flight + crew is one story's worth of code, not a quarter's).
  - JPA-DDD friction: Hibernate's identity-load semantics, the `@Version` optimistic-concurrency requirement, the difference between `Aggregate.save()` and `entityManager.persist()`, the LazyInitializationException class of bugs — all surface during S-022 + S-058 implementation. Test discipline (integration tests against real Postgres per the user's durable preference) catches these early.
  - Query-side / command-side split: read-heavy admin views (per-club aircraft directory, year-end financial report) shouldn't load full aggregates — they should use projections or dedicated read-model repositories. This is good for performance but means contributors have to choose the right tool per query.
  - Domain events add a layer to reason about. Spring Modulith's `@ApplicationModuleListener` is synchronous-by-default with transactional semantics — close to "method call inside the same transaction" — but the indirection costs grep-ability. Mitigation: document the event catalog (which event fires from where, who listens) in `next/server/docs/events.md`.

- **Follow-ups (other ADRs / stories implied):**
  - **ADR 0019** ([Entity ID strategy](0019-entity-id-strategy.md)) — UUID v7 for aggregate roots (cross-bounded-context identity), BIGINT identity for internal entities. This ADR is the parent constraint.
  - **Story:** add Spring Modulith dependency to `next/server/build.gradle.kts`; pin the version compatible with Spring Boot 4 / Hibernate 7. Land before S-022.
  - **Story:** establish `next/server/src/main/java/ch/fls/domain/` module structure with per-bounded-context packages (`flight/`, `aircraft/`, `reservation/`, `planning/`, `accounting/`, `identity/`, `reference/`). Per-aggregate sub-package (e.g., `flight/Flight.java`, `flight/FlightCrew.java`, `flight/FlightRepository.java`) is the canonical layout. ArchUnit guards enforce: no cross-aggregate field access; no `entityManager.persist` outside repositories; aggregate-root fields private final or `@Setter(AccessLevel.PRIVATE)`.
  - **Story:** event catalog convention — `next/server/docs/events.md` lists every domain event, where it fires, who listens; refreshed per story that adds an event.
  - **Story (re-refine S-022):** existing speculative refinement of S-022 (`tenant-id-resolver`) pins the resolver but not the aggregate composition. Re-refine after this ADR lands to integrate aggregate boundaries into the entity-skeleton plan.
  - **Story (re-refine S-012/S-013/S-014):** the speculative refinements pin BIGINT PKs for every table; ADR 0019 changes that for aggregate roots. Re-refine all three to integrate the aggregate-root identification + ID-type split.
  - **Story (S-058 flight validator port):** the legacy `FlightValidator` is the canonical sacred-cow logic that becomes the `Flight` aggregate's invariant suite. Refinement at S-058 should produce a side-by-side parity-table: legacy `Validate()` step → new `Flight` invariant.
  - **Story (S-067 optimistic concurrency):** Hibernate `@Version` is the mechanism; aggregate roots get the column; per-aggregate-root version increments on save.
  - **Story:** ArchUnit / jMolecules tests asserting the DDD layout (no cross-aggregate field access, repositories return only their aggregate root, etc.) — runs in CI per S-024 leakage CI's neighborhood.
