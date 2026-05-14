# 0001 — Backend language + framework

- **Status:** Accepted
- **Date:** 2026-05-14
- **Decision criteria** (from [vision §6](../02-vision-and-constraints.md#6-decision-criteria-for-phase-3)):
  1. Off-EOL & long-supported
  2. Team-familiar stack
  3. Linux-first, Windows-free
  4. Swiss / EU data residency compatible
  5. Structural multi-tenancy supported
  6. Preserves sacred cows cleanly
  7. Solo-operator operability
  8. Enables fast feature dev post-cutover
  9. Credible migration story
  10. Lower TCO
  11. Mature ecosystem for our integration points

## Context

The current backend is ASP.NET Web API 2 on .NET Framework 4.5 with Unity DI, EF6 Code First, OWIN OAuth2, hosted on IIS — every runtime in that sentence is end-of-life ([seed](../00-seed.md), [current-state §1](../01-current-state.md#1-executive-snapshot)). The primary modernization outcome (O1) is to leave that toolchain behind. C2 in the vision narrows the language to a family the solo operator is productive in within weeks; Java and Kotlin are the named candidates. C1 forbids any Windows dependency. The choice of framework cascades into ORM, scheduling, security, migration tool, multi-tenancy mechanism, and observability ADRs, so it needs to be locked first.

Spring Boot is the JVM default for an HTTP+DB+scheduler+security service. Alternatives (Ktor, Micronaut, Quarkus) trade community size for startup speed or idiomatic-Kotlin ergonomics — appealing in isolation, risky for a solo dev who needs every cross-cutting concern to "just have a library."

## Options considered

### Option A — Java 25 + Spring Boot 4.x
- **Capabilities:** Spring Web, Spring Data JPA (Hibernate 7.x), Spring Security 7, Spring Scheduling, springdoc-openapi, Spring Boot Actuator. Java 25 is an LTS release (Sept 2025) with virtual threads + pattern matching + records + sealed types stable. Spring Boot 4.x (late-2025 GA) targets Java 17 baseline, Servlet 6, Jakarta EE 11.
- **Fit to criteria:** Criterion 1 ✓ (LTS until at least 2028 for Java 25; Spring Boot 4.x supported for 3+ years from GA). Criterion 2 ✓ (operator-named language). Criterion 3 ✓ (runs on any Linux JVM). Criterion 4 ✓ (no hosting lock-in). Criterion 5 ✓ (Hibernate has filter / multi-tenancy hooks; see ADR 0008). Criterion 7 ✓ (single JAR, embedded Tomcat, one process). Criterion 11 ✓ (largest JVM ecosystem; every integration we'll need has a Spring starter).
- **Migration cost:** medium. DI patterns transfer from Unity to Spring straightforwardly. EF6 → Hibernate/JPA is the steepest learning curve in the rewrite (different unit-of-work model, different lazy-loading semantics) but well-documented. Java 25 records / sealed types simplify DTOs vs. C# DTOs. Boilerplate (getters/setters) is more than Kotlin but manageable with Lombok or records.
- **Ecosystem risk:** low. Spring is the default; library coverage is comprehensive; Spring Boot 4 is on a known release cadence with overlapping support windows.
- **Escape hatch:** Spring abstractions leave Hibernate replaceable with jOOQ or JdbcClient. Adding Kotlin source files alongside Java is fully supported (Spring is bilingual) — the team can adopt Kotlin incrementally if the no-null-safety pain becomes real.

### Option B — Kotlin + Spring Boot 3.x or 4.x
- **Capabilities:** same framework surface; Kotlin adds null safety, data classes, coroutines, less boilerplate.
- **Fit to criteria:** same as A on every criterion *plus* a measurable edge on "preventing-leak" bugs (criteria 5 and 6) thanks to non-null types.
- **Migration cost:** slightly higher learning curve than Java — Kotlin's syntax + idioms are an extra book on top of Spring. For a C#-comfortable developer the gap is small; for someone returning to JVM from a long absence it's another concept to absorb alongside the framework.
- **Ecosystem risk:** low — first-class Spring support, Kotlin itself is JetBrains-backed and growing.
- **Escape hatch:** Kotlin → Java is one-way but mechanical (decompile or hand-port). Kotlin/Java interop is seamless within a single project.

### Option C — Kotlin + Ktor
- **Capabilities:** lightweight Kotlin-native server, JetBrains-backed, fine-grained pick-your-libraries model.
- **Fit to criteria:** criterion 1 ✓, 2 partial, 7 ✗ (every cross-cutting concern is a separate decision), 11 ✗ (community much smaller than Spring).
- **Migration cost:** higher — no "kitchen sink" framework means more architectural plumbing per feature.
- **Ecosystem risk:** medium — Ktor is mature but Spring has 10× the integration breadth.
- **Escape hatch:** code is plain Kotlin; porting to Spring is possible but non-trivial.

### Option D — Kotlin + Micronaut or Quarkus
- **Capabilities:** compile-time DI, fast startup, GraalVM-native option.
- **Fit to criteria:** criterion 1 ✓, 7 ~ (single binary is nice but the dev-loop differs from Spring), 11 partial (smaller library surface).
- **Migration cost:** patterns differ from Spring; smaller mindshare → slower problem-solving.
- **Escape hatch:** JPA / OpenAPI / etc. abstractions are the same APIs — but framework idioms differ enough that a full port is real work.

## Decision

Chosen: **Option A — Java 25 + Spring Boot 4.x**. Decision driven by criterion 2 (operator chose plain Java as the language they want to maintain), criterion 11 (Spring is the most-mature integration surface on the JVM), and criterion 7 (single-JAR Spring Boot deploy is the simplest operable thing for a solo operator). Java 25's LTS window comfortably extends past cutover. Kotlin's null-safety advantage was acknowledged but not selected — the operator chose to minimize cognitive load over maximizing type-system features. Spring Boot bilingual support means Kotlin can be adopted incrementally later without an architectural change.

## Consequences

- **Positive:**
  - LTS runtime until at least 2028; Spring Boot 4.x supported 3+ years from GA.
  - Biggest available pool of documentation, Stack Overflow answers, and starter libraries.
  - Spring Boot Actuator gives a free observability surface that feeds [ADR 0011](.).
  - Spring Data JPA + Hibernate gives a tenant-filter pattern that feeds [ADR 0008](.).
  - Spring Security gives an OAuth2 resource-server pattern that feeds [ADR 0007](.).
  - Build tool decision (Gradle vs. Maven) becomes a follow-up but is contained — both work identically with Spring Boot.

- **Negative:**
  - No null safety at the language level — code reviews and tests must catch what Kotlin would catch at compile time. Mitigate with strict NullAway / SpotBugs config and `@NonNull` annotations as a team convention.
  - More boilerplate than Kotlin, especially DTOs (records help). Lombok adds a build-time complication; prefer Java 25 records where structural.
  - Hibernate's lazy-loading semantics will surprise EF6-comfortable developers (LazyInitializationException, N+1, etc.). Test discipline matters.

- **Follow-ups (other ADRs / stories implied):**
  - **ADR 0002** (Database engine) — engine pick now happens in the context of "Hibernate dialects we want to deal with"; Postgres is the obvious default.
  - **ADR 0003** (Schema migration tooling) — Flyway and Liquibase are both first-class Spring Boot citizens; pick one.
  - **ADR 0007** (Auth scheme) — Spring Security 7 OAuth2 Resource Server is the implementation path.
  - **ADR 0008** (Multi-tenancy mechanism) — Hibernate's `@Filter` / `@TenantId` / discriminator approaches are the realistic options.
  - **ADR 0009** (Background-job mechanism) — Spring `@Scheduled` or Quartz-via-Spring-starter are the candidates.
  - **ADR 0010** (Hosting) — single Spring Boot JAR + Docker is the baseline deploy artifact.
  - **ADR 0011** (Observability) — Spring Boot Actuator + Micrometer + OpenTelemetry Java agent is the native combo.
  - **Story:** pick a build tool (Gradle Kotlin DSL vs. Maven). Phase-4 task, not an ADR.
  - **Story:** establish a null-safety convention (JSpecify annotations + NullAway in the Gradle/Maven config). Phase-4 task.
  - **Story:** scaffold the `next/server/` Spring Boot project skeleton with the chosen build tool, NullAway, Actuator, springdoc-openapi pre-wired.
