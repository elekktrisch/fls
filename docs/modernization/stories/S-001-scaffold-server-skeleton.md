---
id: S-001
title: Scaffold next/server/ Spring Boot skeleton
epic: E-01
status: todo
depends_on: []
acceptance:
  - A new contributor can clone the repo, install JDK 25, and run the server with one command, hitting `GET /actuator/health` returning 200.
  - Build tool (Gradle Kotlin DSL vs. Maven) is committed; the README explains why.
  - Null-safety convention is enforced at build time (JSpecify annotations + NullAway plugin); a deliberately null-passing test fails the build.
  - Project follows Spring Boot 4.x conventions: `application.yml` for config, `@SpringBootApplication` entry point, package layout by domain not by layer.
estimate: M
adr_refs: [0001]
parity_test: none
---

## Context
First foundational story. Establishes the server-side project skeleton that every subsequent backend story builds on.

## Acceptance criteria
- See frontmatter. Plus: Actuator `/actuator/health` and `/actuator/info` are exposed; `springdoc-openapi-starter-webmvc-ui` is in the dependency graph (wiring is S-003).

## Tasks
- [ ] Pick build tool (Gradle Kotlin DSL recommended for type-safety + Spring Boot's modern docs alignment) — document decision in `next/server/README.md`.
- [ ] Generate skeleton via `spring initializr` (or hand-roll) with: Web, Actuator, Validation, Configuration Processor.
- [ ] Add JSpecify + NullAway; configure as a build-failing check.
- [ ] Establish package layout: `ch.fls.<domain>` (e.g. `ch.fls.flight`, `ch.fls.aircraft`); not `controller/service/repository` layered.
- [ ] Wire `application.yml` for dev (port, logging level) — leave secrets to `.env`.
- [ ] Add a "hello" endpoint (`GET /api/v1/hello`) to confirm the routing works; this comes out once a real endpoint is added.
- [ ] Write a smoke test that hits the hello endpoint via `MockMvc`.

## Notes
Java 25 LTS, Spring Boot 4.x (ADR 0001). The build-tool decision is a story-internal task, not a separate ADR — both Gradle and Maven work identically with Spring Boot at this scale.
