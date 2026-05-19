# 0023 — Hexagonal layering inside Spring Modulith modules

- **Status:** Accepted
- **Date:** 2026-05-18
- **Decision criteria** (from [vision §6](../02-vision-and-constraints.md#6-decision-criteria-for-phase-3)):
  2. Team-familiar stack
  5. Structural multi-tenancy supported
  6. Preserves sacred cows cleanly
  7. Solo-operator operability
  8. Enables fast feature dev post-cutover

## Context

[ADR 0018](0018-domain-model-ddd-aggregates.md) commits to DDD aggregates per bounded context with Spring Modulith and per-aggregate repositories, but does not lock the *layering inside a module*. The S-048 walking skeleton demonstrated the gap: with a flat per-feature package, nothing structurally prevents a future controller from reaching past the service into the repository, a DTO from depending on the aggregate, Jackson annotations from creeping onto an aggregate, or a domain rule from quietly acquiring a Spring dependency.

The operator's ask is to make the DDD domain the most stable part of the system — meaning the aggregates and their business rules (flight state machine, time gates, rules engine, glider↔tow self-FK invariants, audit-on-every-mutation) must survive any future swap of web framework, message bus, IdP, or external integration without rewriting domain code. [ADR 0022](0022-modernization-primary-directives.md) directive 1 (working software over comprehensive documentation) constrains the answer: textbook-pure hexagonal that doubles every entity for purity is the comprehensive-documentation trap; pragmatic hexagonal that protects 95% of the stability win at 30% of the cost is the directive-1 answer.

## Options considered

### Option A — Modular monolith with hexagonal-lite per module (JPA-on-aggregate)

- **Capabilities:**
  - Each bounded context is a top-level package (`ch.alpenflight.<context>`), already its own Spring Modulith module per ADR 0018. Inside each module, four sub-packages with enforced dependency direction:
    - `domain/` — aggregate roots, value objects, domain services, domain events, repository **interfaces**, domain exceptions (free of `@ResponseStatus` and Spring web imports — a `@RestControllerAdvice` in `web/` translates them to HTTP). **JPA annotations allowed on aggregates** (Vernon-style pragmatic Hibernate-DDD); banned: Spring (except `@DomainEvents`), web stack, Jackson, JDBC, file IO, external clients, `@Component`-family.
    - `application/` — use-case services (`@Service`, `@Transactional`), orchestration of domain calls, **request/response DTOs (the service's wire contract), domain-to-DTO mappers**. Depends on own `domain/` + published interfaces of other modules.
    - `web/` — controllers, the `@RestControllerAdvice` exception handler. May depend on own `application/` (consumes DTOs + the service) and own `domain/` (catches the domain exception types — same-module dependency is allowed). May NOT depend on own `infra/` or any other module's internals.
    - `infra/` — Spring Data JPA repository interfaces that extend the domain `*Repository` interface, plus external-system adapters (mail clients, IdP gateways, OGN ingestors). Depends on own `domain/` + Spring + libraries.
  - Cross-module access goes through Spring Modulith's published API or domain events; direct cross-module entity / repository access is an ArchUnit violation. Shared cross-cutting tech (typed IDs, security plumbing, tenancy resolver, OpenAPI config) lives under `platform/` and is the only package every module may depend on.
  - Enforcement in CI: Spring Modulith's `ApplicationModules.verify()` for inter-module boundaries (catches cross-module reaches into another module's internals) + ArchUnit rules for inner-layer direction (`domain/` Spring-web-free; `application/` does not depend on `web/` or `infra/`; `web/` does not depend on `infra/`).
- **Fit to criteria:** Criterion 6 ✓✓ (the sacred-cow invariants from ADR 0018 sit in `domain/` and are insulated from infrastructure churn — exactly the "most stable" property the operator asked for). Criterion 8 ✓ (the 4-package template is reusable per module; a story-level walking skeleton à la S-048 stays compact). Criterion 7 ✓ (one extra layer of discipline, automated by ArchUnit + Modulith — no human policing). Criterion 5 ✓ (`@TenantId` from ADR 0008 lives on the domain aggregate; the discriminator survives the layering because Hibernate annotations are permitted in `domain/`). Criterion 2 ✓ (plain Java + Spring + Hibernate idioms; no new framework).
- **Migration cost:** small. Reshaped S-048's `clubs/` into `clubs/{domain,application,web,infra}/` in S-155 with no business-rule changes. Subsequent stories (S-058 flight validator port, S-062a flight CRUD, etc.) inherit the template — per-module overhead is three extra `package-info.java` files and a tiny `JpaXxxRepository` extending the domain interface.
- **Ecosystem risk:** low. Spring Modulith, ArchUnit, and Hibernate-DDD are the canonical JVM stack for this shape ([Spring Modulith reference](https://docs.spring.io/spring-modulith/reference/), Vernon's *Implementing Domain-Driven Design*).
- **Escape hatch:** an aggregate that needs strict domain/persistence decoupling later (e.g. the accounting `Rule` pipeline if value-object polymorphism fights JPA mapping) can promote to "two-entity hexagonal" — pure-POJO `Rule` in `domain/` + `RuleRow` in `infra/` + a mapper — *locally* in that one module, without disturbing every other module.

### Option B — Classical hexagonal (purist; domain JPA-free)

- **Capabilities:** Top-level `domain/` / `application/` / `adapters/{in/web,out/persistence,out/messaging}` layout. Every aggregate exists as a pure-POJO domain entity + a JPA `XxxRow` mapped to the table + a hand-written mapper. Domain depends on JDK only; repository interfaces in `domain/`; Spring Data implementations in `adapters/out/persistence/`. Strongest "stability" guarantee — the domain cannot acquire any infrastructure dependency by accident.
- **Fit to criteria:** Criterion 6 ✓✓ (maximum decoupling). Criterion 7 ✗ (per-aggregate tax across ~30 aggregates = real solo-operator hours; mappers drift from entities and need refreshing every schema change). Criterion 8 ~ (every story carries a doubled-entity + mapper cost).
- **Why not chosen:** violates ADR 0022 directive 1. The marginal stability win over Option A is paid by every story, forever — Hibernate is committed in ADR 0001 and is not getting swapped in this rewrite. Option A's escape hatch covers the rare aggregates that genuinely need this; the project-wide tax doesn't earn its keep.

### Option C — Onion (concentric layers)

- **Capabilities:** Concentric layout — `core/domain` → `application` → `infrastructure` → `presentation`. Innermost depends on nothing outside.
- **Fit to criteria:** in a Spring project, structurally identical to Option B; the difference is framing rhetoric (ports-and-adapters vs concentric circles) rather than code.
- **Why not chosen:** no incremental information vs B. Bundling it into this ADR would turn the decision into a dialect discussion.

### Option D — Status quo: package-by-feature with no enforced inner layering

- **Capabilities:** keep S-048's flat per-feature package shape; add Spring Modulith for module boundaries only. Direction-of-dependency inside a module enforced only by code review.
- **Fit to criteria:** Criterion 6 ✗ — every leak the user asked us to prevent stays structurally possible. A future controller can hit the repository; a DTO can take a `Club` field; the aggregate can grow a Jackson annotation.
- **Why not chosen:** doesn't fulfil the operator's "most stable" ask.

## Decision

Chosen: **Option A — Modular monolith with hexagonal-lite per module, JPA-on-aggregate allowed**. Decision driven by criterion 6 (the sacred-cow aggregates from ADR 0018 land in `domain/` and become structurally insulated from every web / messaging / IdP / observability swap the rest of the rewrite may make) and ADR 0022 directive 1 (pragmatic JPA-on-aggregate buys ~95% of the stability win without the per-aggregate doubled-entity tax). ArchUnit + Spring Modulith automate enforcement so the discipline is not human-policed.

The four-package template (`domain/`, `application/`, `web/`, `infra/`) is the canonical module shape going forward; every new module ships with all four directories and a `package-info.java` per package documenting the dependency rule. The S-048 walking skeleton is reshaped retroactively as part of the first follow-up story so the convention is concrete code from day one.

## Consequences

- **Positive:**
  - The DDD domain becomes the most stable part of the system, as asked. Web framework, ORM choice, message bus, IdP provider can each be swapped without touching `domain/` — every dependency on those technologies is forced to live in `web/`, `infra/`, or `platform/`.
  - The S-048 walking skeleton becomes the worked example for every subsequent vertical slice; future stories don't re-litigate "where does the controller go" or "where does the JPA repo live."
  - ArchUnit rules + Spring Modulith verification are checked in CI; a layering violation fails the build, not a code review. Solo-operator discipline lifted into the type / package system.
  - Optimistic-concurrency, audit, domain-event subscribers, `@TenantId` discriminator all compose cleanly — they're already domain-shaped concepts; the layering puts them on the right side of the boundary.
  - The escape hatch (promote a single aggregate to two-entity hexagonal) is local. A future "the rules engine actually needs the purist split" decision affects one module, not the whole codebase.

- **Negative:**
  - JPA annotations on the aggregate are a deliberate purity concession. Pure-DDD readers will object. Mitigation: ADR text documents the trade; ADR 0022 directive 1 is the citation.
  - Three extra package-info files + a `JpaXxxRepository` extending the domain interface is per-module overhead vs Option D. Real but small.
  - LazyInitializationException-class bugs (a `web/` mapper touching a lazy aggregate-internal collection outside the transaction) are now harder to spot because the mapper lives in a different package from the entity. Mitigation: mappers run on `@Transactional` boundaries by convention; integration tests against real Postgres (per [ADR 0021](0021-integration-test-data-isolation.md)) catch the rest.
  - ArchUnit + Spring Modulith add ~2 test deps to `alpenflight/server/build.gradle.kts` and ~10s to the test phase. Acceptable.

- **Follow-ups:**
  - **(done in S-155)** Clubs reshape, Spring Modulith dep + `ApplicationModules.verify()`, ArchUnit dep + 4-rule inner-layer suite, `archDemo` regression source set + `verifyArchUnitFailsOnViolation` task, `CONVENTIONS.md` "Module layout" section.
  - **(moot)** Re-refine S-022 — already shipped before this ADR; the `@TenantId`-carrying `MemberState` aggregate was retroactively moved into `clubs/domain/` by S-155.
  - **Refinement template:** `/modernize-refine` solution-architect output for any backend story should list target packages per concern (`domain/`, `application/`, `web/`, `infra/`) rather than a flat file list. Skill-prompt nudge, not a new story.
