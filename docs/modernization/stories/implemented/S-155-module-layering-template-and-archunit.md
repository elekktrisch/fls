---
id: S-155
title: Module layering template — Spring Modulith + ArchUnit + Clubs reshape
epic: E-01
status: done
started_at: 2026-05-18
done_at: 2026-05-18
refined: true
github_issue: 68
github_pr: 69
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

## Notes
- **Cross-story contract:** every subsequent bounded-context module (S-047, S-049, S-050, S-051, S-058, …) is scaffolded into the four-package template from commit one. ArchUnit + Modulith verify automatically; no per-story rule edits.
- **One-story-vs-split decision:** operator decision 2026-05-18 — Modulith + reshape + ArchUnit ship the same convention; landing them as three separate stories adds review overhead without separating concerns the reader actually cares about.
- **Skipped formal `/modernize-refine`:** operator chose to fold S-154 into S-155 and start implementing. Story body had refinement-level detail; the work-packages played the same role refinement would have.
- **`@TenantId` aggregates land in `domain/` from now on.** S-022's `MemberState` was retroactively moved by this story; future tenant-scoped aggregates ship straight into `domain/`.
