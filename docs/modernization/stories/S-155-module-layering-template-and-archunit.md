---
id: S-155
title: Module layering template — Spring Modulith + ArchUnit + Clubs reshape
epic: E-01
status: in_progress
started_at: 2026-05-18
refined: true
github_issue: 68
depends_on: [S-001, S-022]
acceptance:
  - Spring Modulith on the classpath in `next/server/build.gradle.kts`, pinned to a Spring Boot 4.x-compatible version; `./gradlew build` green.
  - An `ApplicationModulesTest` under `next/server/src/test/java/ch/alpenflight/arch/` calls `ApplicationModules.of(AlpenFlightApplication.class).verify()` and passes; CI fails when a cross-module import bypasses a published API.
  - `next/server/src/main/java/ch/alpenflight/clubs/` reshaped into `clubs/domain/`, `clubs/application/`, `clubs/web/`, `clubs/infra/` per ADR 0023. The repository split is structural — `ClubRepository` (interface) ships in `domain/`, `JpaClubRepository` in `infra/` extending both `JpaRepository<Club,UUID>` and `ClubRepository`. Existing S-048 unit + integration tests still pass without test edits beyond package imports.
  - `package-info.java` in each new sub-package states the allowed inbound + outbound packages and re-applies `@NullMarked`.
  - ArchUnit on the classpath; a `LayeringRulesTest.java` under `next/server/src/test/java/ch/alpenflight/arch/` enforces direction-of-dependency from ADR 0023 — `domain/` must not depend on `org.springframework.web.*`, `com.fasterxml.jackson.*`, `jakarta.servlet.*`, `org.springframework.stereotype.{Component,Service,Repository,Controller}` (allowed exception: `org.springframework.modulith.events.*`); `web/` must not depend on `..infra..` or another module's `..domain..`; `application/` must not depend on `..web..`; cross-module imports never reach into `<other-module>.{domain,infra,web}` directly.
  - An `archDemo` source set (mirroring the existing `nullawayDemo` pattern) holds three deliberate-violation classes; a `verifyArchUnitFailsOnViolation` Gradle task invokes the ArchUnit suite against that source set and asserts non-zero exit. Not wired into `check` / `build`; CI runs it as a separate workflow step.
  - `next/server/CONVENTIONS.md` (new file or new section) documents the four-package template as the canonical module shape — one paragraph, ASCII tree, "How violations surface" subsection, link to ADR 0023.
estimate: L
adr_refs: [0018, 0022, 0023]
parity_test: none
---

## Context
ADR 0023 locks the modular-monolith-with-hexagonal-lite layering: every bounded-context module ships `domain/`, `application/`, `web/`, `infra/` with enforced direction-of-dependency. ADR 0018 had already committed to Spring Modulith for inter-module boundaries; this story is the first to put it on the classpath, layer ArchUnit on top for inner-direction enforcement, and reshape `clubs/` as the worked example every future module copies. Lands before any new bounded-context module (S-047 reference data, S-049 Locations, …) so the convention is concrete code, not paper.

Spring Modulith and ArchUnit ship complementary checks — Modulith catches "module A reaches into module B's internals"; ArchUnit catches "web reaches past application into infra inside one module," "domain imports Spring web," etc. Bundled into one story because (a) they share the build-script touch + test-package home, (b) implementing only Modulith leaves the inner direction unenforced, (c) implementing only ArchUnit without the reshape leaves the rules with no real production code to check.

## Acceptance criteria
See frontmatter.

## Tasks
### Work-package 1 — Spring Modulith + module verification
- [ ] Add `org.springframework.modulith:spring-modulith-starter-core` + `spring-modulith-starter-test` to `next/server/build.gradle.kts`; pin to the Spring Boot 4.x BOM-aligned version.
- [ ] Create `next/server/src/test/java/ch/alpenflight/arch/ApplicationModulesTest.java` invoking `ApplicationModules.of(AlpenFlightApplication.class).verify()`. Test passes green against today's `clubs/` flat layout (Modulith only sees module-level boundaries; the flat package is a single module).

### Work-package 2 — Clubs reshape into four-package template
- [ ] Introduce `ch.alpenflight.clubs.domain.ClubRepository` as an interface mirroring the calls `ClubsService` makes today.
- [ ] Move `Club`, `MemberState`, `MemberStateRepository` into `clubs/domain/`.
- [ ] Move `ClubsService` into `clubs/application/`.
- [ ] Move `ClubsController`, `ClubDtos`, `ClubMapper`, `ClubNotFoundException`, `SlugAlreadyExistsException` into `clubs/web/`.
- [ ] Rename `ClubsRepository` to `JpaClubRepository`, move into `clubs/infra/`, make it extend both `JpaRepository<Club,UUID>` and the new `ClubRepository` interface so all `ClubsService` calls flow through the domain interface.
- [ ] Write `package-info.java` for each of `clubs/domain`, `clubs/application`, `clubs/web`, `clubs/infra`. Each one names the allowed inbound + outbound packages and re-applies `@NullMarked`.
- [ ] Mirror the new package layout in `next/server/src/test/java/ch/alpenflight/clubs/` so test packages line up with production.
- [ ] Verify the existing S-048 test classes still compile + pass; update only the import statements forced by the package moves.

### Work-package 3 — ArchUnit rules
- [ ] Add `com.tngtech.archunit:archunit-junit5` to testImplementation.
- [ ] Create `LayeringRulesTest.java` under `next/server/src/test/java/ch/alpenflight/arch/` with four `@ArchTest` rules:
  - **Rule 1 — `domain` infra-free:** classes in `..<module>.domain..` must not depend on the banned-package list (web stack, Jackson, Spring stereotypes, servlet API). Allowed exception list explicit and small.
  - **Rule 2 — `web` repo-free:** classes in `..<module>.web..` must not depend on `..<any-module>.infra..` or another module's `..domain..` directly.
  - **Rule 3 — `application` web-free:** classes in `..<module>.application..` must not depend on `..<any-module>.web..`.
  - **Rule 4 — cross-module isolation:** any class in `..<module-a>..` must not import a type from `..<module-b>.domain..`, `..<module-b>.infra..`, or `..<module-b>.web..`. Cross-module communication goes through Spring Modulith's published API or via events.
- [ ] Sanity-check on the reshaped tree: all four rules pass with zero violations.

### Work-package 4 — verification source set (mirrors NullAway demo)
- [ ] Add an `archDemo` source set in `build.gradle.kts`, mirroring the existing `nullawayDemo` block.
- [ ] Three deliberate-violation classes under `src/archDemo/java/`:
  - `clubs/domain/JacksonLeak.java` importing `com.fasterxml.jackson.annotation.JsonProperty` on a field.
  - `clubs/web/InfraLeak.java` importing `clubs.infra.JpaClubRepository`.
  - `clubs/application/WebLeak.java` importing `clubs.web.ClubDtos`.
- [ ] Add a Gradle task `verifyArchUnitFailsOnViolation` that runs the ArchUnit test class against the `archDemo` source set and asserts non-zero exit. Not in `check`; CI workflow invokes it separately.

### Work-package 5 — CONVENTIONS.md entry
- [ ] Create or extend `next/server/CONVENTIONS.md` with a "Module layout" section: short paragraph + ASCII tree of `clubs/{domain,application,web,infra}/` + link to ADR 0023.
- [ ] Add a "How violations surface" subsection pointing at `ApplicationModulesTest`, `LayeringRulesTest`, and `verifyArchUnitFailsOnViolation`.

## Notes
- **Why bundled into one L story (vs the prior split):** operator decision 2026-05-18 — Modulith + reshape + ArchUnit ship the same convention; landing them as three separate stories adds review overhead without separating concerns the reader actually cares about. Tasks split keeps the work-package boundaries visible.
- **Test package mirroring:** S-048's integration tests live in `next/server/src/test/java/ch/alpenflight/clubs/`. After the move, the test packages mirror production: `clubs/web/` for controller tests, `clubs/application/` for service tests, `clubs/infra/` for JPA tests.
- **`@TenantId` placement:** S-022's `@TenantId`-annotated entities ship in `domain/` from this point onward. S-022 itself is implemented; future modules carrying tenant-scoped aggregates land in `domain/` by default.
- **Allowed exceptions in Rule 1 (`domain` infra-free):**
  - `org.springframework.modulith.events.*` — `@DomainEvents`, `@ApplicationModuleListener` are the canonical Modulith vehicle for cross-aggregate coordination per ADR 0018.
  - `jakarta.persistence.*` — JPA annotations live on aggregates by ADR 0023's deliberate concession.
  - `org.jspecify.annotations.*` — `@Nullable`, `@NullMarked` are part of the build's null-safety story.
  - Anything else: reject.
- **Spring Modulith vs ArchUnit responsibilities:**
  - Modulith — verifies module-level boundaries (no module imports another module's internals).
  - ArchUnit — verifies inner-layer direction within a module (no `web → infra`, no `domain → org.springframework.web`).
  - Together: a layering violation in either dimension fails the build.
- **Why a separate verification source set?** Mirrors the established pattern from S-001's NullAway demo (see `build.gradle.kts` `nullawayDemo` block). Catches the regression where someone weakens a rule and the suite silently passes everything — the demo classes force the rules to keep working.
- **Future module bring-up:** once these rules are live, any new module (S-047 reference, S-049 locations, etc.) gets the layering enforced automatically — no per-story rule edits needed. The rules are pattern-based (`..<module>.domain..`), not enumerated.
- **Estimate calibration:** 8–10 files relocated; one repository interface added; one rename; two new test classes (ArchUnit suite ~80–120 lines, Modulith verify ~10 lines); `archDemo` source set wiring ~20 lines of Gradle (mirrors nullawayDemo); three demo violation classes ~30 lines; CONVENTIONS doc ~30 lines; two new build deps. L by aggregate volume, but each work-package is independently small and verifiable — implement in order, commit per work-package.
