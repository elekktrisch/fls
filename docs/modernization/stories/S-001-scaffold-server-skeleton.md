---
id: S-001
title: Scaffold next/server/ Spring Boot skeleton
epic: E-01
status: done
started_at: 2026-05-15
done_at: 2026-05-15
github_issue: 1
github_pr: 2
depends_on: []
acceptance:
  - A new contributor can clone the repo, install JDK 25, and run the server with one command, hitting `GET /actuator/health` returning 200.
  - Build tool (Gradle Kotlin DSL vs. Maven) is committed; the README explains why.
  - Null-safety convention is enforced at build time (JSpecify annotations + NullAway plugin); a deliberately null-passing test fails the build.
  - Project follows Spring Boot 4.x conventions: `application.yml` for config, `@SpringBootApplication` entry point, package layout by domain not by layer.
estimate: M
adr_refs: [0001]
parity_test: none
refined: true
refined_at: 2026-05-14
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
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

<!-- modernize-refine: start -->

## Design notes

### Module layout

End-of-story directory tree under `next/server/`. Only what S-001 creates; domain packages get added by their respective stories.

```
next/server/
├── .editorconfig                                      # 2-space yaml, 4-space java, LF
├── .gitattributes                                     # * text=auto eol=lf — critical: ops are Linux-only (C1)
├── .gitignore                                         # build/, .gradle/, .idea/, *.log, out/, bin/, .env, .env.local
├── .env.example                                       # placeholder env vars; .env is gitignored
├── README.md                                          # build-tool rationale (AC requires it); one-command run
├── build.gradle.kts                                   # see Build-tool decision
├── settings.gradle.kts                                # rootProject.name = "fls-server"
├── gradle/
│   └── wrapper/{gradle-wrapper.jar, gradle-wrapper.properties}   # distributionSha256Sum pinned
├── gradlew                                            # checked in; chmod +x
├── gradlew.bat                                        # checked in for Windows clones
├── src/
│   ├── main/
│   │   ├── java/ch/fls/
│   │   │   ├── FlsApplication.java                    # @SpringBootApplication, public static void main
│   │   │   ├── package-info.java                      # @NullMarked (JSpecify)
│   │   │   ├── config/
│   │   │   │   └── package-info.java                  # @NullMarked; placeholder for WebConfig/ObservabilityConfig
│   │   │   └── platform/
│   │   │       ├── package-info.java                  # @NullMarked
│   │   │       └── hello/
│   │   │           └── HelloController.java           # GET /api/v1/hello — TODO(S-020): remove or auth-gate
│   │   └── resources/
│   │       ├── application.yml                        # base config — see "load-bearing config keys" below
│   │       ├── application-dev.yml                    # dev overrides (verbose logging, dev CORS if enabled)
│   │       ├── application-test.yml                   # test profile (random port)
│   │       ├── application-prod.yml                   # prod overrides (banner off, stacktraces off, empty CORS)
│   │       └── logback-spring.xml                     # STUB: console default + MDC keys reserved; S-031 swaps to JSON
│   └── test/
│       ├── java/ch/fls/
│       │   ├── SmokeUnitTest.java                     # JUnit-5 runner smoke
│       │   ├── ApplicationContextTest.java            # @SpringBootTest context-loads
│       │   ├── platform/hello/HelloControllerIT.java  # @WebMvcTest hello slice
│       │   ├── actuator/ActuatorHealthIT.java         # @SpringBootTest(RANDOM_PORT) + TestRestTemplate
│       │   └── build/ToolchainTest.java               # asserts java.specification.version == "25"
│       └── nullawayDemo/java/ch/fls/nullaway/
│           └── NullDereferenceDemo.java               # side source set; build task asserts compile fails
```

Decisions baked in:

- **Application class** is `ch.fls.FlsApplication`, not `ch.fls.server.ServerApplication`. The `server/` segment already exists as the directory name; doubling it in the package is noise. `ch.fls` is the root scan package — Spring component scan picks up every domain package added later (`ch.fls.flight`, `ch.fls.aircraft`, …) without re-configuration.
- **Java package = `ch.fls`** even though the working folder slug is `next/`. Per vision §8, the final product slug is deferred to a phase-4 naming story; the Java package needs *some* name now and `ch.fls` is operator-stable (Switzerland-based). Renaming Java packages is mechanical if the slug story decides otherwise.
- **Cross-cutting config package:** `ch.fls.config`. Empty `package-info.java` only at S-001 time; `WebConfig`, `ObservabilityConfig`, `JacksonConfig` land in their respective stories. Existing as a placeholder so the convention is set.
- **Hello endpoint location:** `ch.fls.platform.hello.HelloController`. `ch.fls.platform` becomes the home for cross-cutting non-domain endpoints (later: `/api/v1/_meta`, OpenAPI custom routes, anything tenant-agnostic). Keeps domain packages clean.
- **`package-info.java` with `@NullMarked` (JSpecify)** at every package root. This is the lever that makes NullAway treat the whole package as `@NonNull` by default, and how every later story inherits the policy for free by just creating a package.
- **`logback-spring.xml` stub** — exists with Spring's default console appender included plus reserved MDC keys (`request_id`, `tenant_id`, `actor_user_id`) in the layout pattern. S-031 (structured JSON logging) swaps the encoder; the file path and MDC contract are pinned now so the swap is non-invasive.
- **No `next/server/Dockerfile`** — owned by S-040. The Gradle `bootJar` task produces a single fat JAR; that's all S-040 needs.
- **No Flyway migration dir, no JPA entities, no Spring Security** — these are deliberately absent. Each is owned by its respective story (S-009 / S-012 / S-020) and must integrate with this skeleton without rework. Contracts are listed under "Integration with other stories" below.

### Build-tool decision

**Chosen: Gradle Kotlin DSL.** Four concrete reasons:

1. **Spring Boot 4.x docs + `start.spring.io` default emit Gradle Kotlin DSL.** Maven samples still exist but Kotlin DSL is the lead. Picking Maven means hand-translating every snippet for the next 6–12 months.
2. **NullAway integration is two lines in `build.gradle.kts`** via the `net.ltgt.errorprone` plugin. The Maven equivalent requires `error-prone-javac` workarounds, `<annotationProcessorPaths>`, and `-XDcompilePolicy=simple` shims. Boring-tech preference bites here: the Maven plumbing for NullAway is more arcane than the Gradle one in 2026.
3. **Type-safe accessors** in KTS catch typos at IDE-edit time; reads like Kotlin (operator is Java/Kotlin-comfortable per ADR 0001 dismissal of Kotlin-the-language).
4. **Single-module fat JAR via `bootJar`** is identical in both. Tipping factor is NullAway plumbing + docs alignment.

Dismissed: **Maven.** Fine tool, but more XML and worse NullAway plumbing on modern JDKs.

`build.gradle.kts` skeleton (verify versions at story-impl time):

```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.0.0"
    id("io.spring.dependency-management") version "1.1.6"
    id("net.ltgt.errorprone") version "4.1.0"
}
group = "ch.fls"
version = "0.0.1-SNAPSHOT"
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
repositories { mavenCentral() }
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.0")   // present for S-003 (disabled here)
    implementation("org.jspecify:jspecify:1.0.0")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    errorprone("com.google.errorprone:error_prone_core:2.36.0")
    errorprone("com.uber.nullaway:nullaway:0.12.3")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        check("NullAway", net.ltgt.gradle.errorprone.CheckSeverity.ERROR)
        option("NullAway:AnnotatedPackages", "ch.fls")
        option("NullAway:JSpecifyMode", "true")
    }
}
tasks.withType<Test>().configureEach { useJUnitPlatform() }
```

### Load-bearing `application.yml` keys

These are pinned **now** because every downstream story inherits them. Each is justified in §Security plan / §Performance plan:

```yaml
spring:
  application: { name: fls-server }
  profiles: { default: dev }
  jpa: { open-in-view: false }                                # no-op without JPA starter, locks invariant for S-012
  jackson:
    serialization: { write-dates-as-timestamps: false }
    deserialization: { fail-on-unknown-properties: true }
    default-property-inclusion: non_null
    time-zone: UTC
server:
  port: 8080
  http2: { enabled: true }
  compression: { enabled: true, mime-types: application/json,application/xml,text/html,text/plain,text/css,application/javascript, min-response-size: 1024 }
  error: { include-stacktrace: never, include-message: never, include-exception: false, include-binding-errors: never }
  shutdown: graceful
  tomcat: { max-http-form-post-size: 1MB }
  servlet: { multipart: { max-file-size: 10MB, max-request-size: 10MB } }
management:
  endpoints:
    web:
      exposure:
        include: health,info
        exclude: env,heapdump,threaddump,loggers,configprops,beans,shutdown
  endpoint:
    health: { probes: { enabled: true }, show-details: when_authorized }
springdoc: { api-docs: { enabled: false }, swagger-ui: { enabled: false } }   # dep on classpath; wiring is S-003
```

`application-dev.yml` relaxes `server.error.include-message: always` and `management.endpoint.health.show-details: always`; `application-prod.yml` adds `spring.main.banner-mode: off`.

### Domain model
**N/A.** No JPA entities are introduced by S-001. The entity vocabulary (`@TenantId`, `@Entity`, auditing fields) is established by S-022 and beyond. No Hibernate dialect is configured here.

### API surface

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/api/v1/hello` | none | Returns `{"message":"Hello FLS","timestamp":"<ISO-8601>"}`. **Decision: remove when the first real domain endpoint lands** (T3 smoke S-110 can pivot off that endpoint). Marked with `// TODO(S-020): remove or auth-gate before cutover`. |
| GET | `/actuator/health` | none | Spring Boot built-in. Exposed via `management.endpoints.web.exposure.include`. |
| GET | `/actuator/info` | none | Spring Boot built-in. Returns empty body until `info.*` keys land. |
| (all other `/actuator/**`) | — | — | Not exposed. S-030 widens to include `prometheus`, `metrics`; gating is S-020's problem when security lands. |

`@PreAuthorize`: **none on any endpoint in this story.** Spring Security is not on the classpath — S-020 owns adding `spring-boot-starter-security` and the first `SecurityFilterChain` bean. `HelloController` is a plain `@RestController` with `@GetMapping`.

### Integration with other stories

**Inputs:** none. `depends_on: []`.

**Outputs / contracts S-001 must hold stable:**

- **S-003 (springdoc-openapi):** `springdoc-openapi-starter-webmvc-ui` is already in `dependencies` but `springdoc.api-docs.enabled=false` keeps endpoints dormant until S-003 explicitly enables. `HelloController.hello()` is the first endpoint to receive `@Operation`/`@ApiResponse`.
- **S-009 (Flyway):** config home is `application.yml` (not `.properties`). Profiles `dev`/`test`/`prod` already exist for env-specific datasources. S-001 commits NO `spring.datasource.*` — S-009 owns it.
- **S-015 (Testcontainers):** `spring-boot-starter-test` is already in `testImplementation` so JUnit 5 + AssertJ + Spring Boot Test infra is wired. S-015 adds `org.testcontainers:postgresql` + `@Testcontainers` config.
- **S-018 / S-081 (scheduled jobs):** `@EnableScheduling` is **not** activated here. S-081 owns the on-switch + ShedLock policy. S-001's contract: no `@Scheduled` anywhere in the skeleton.
- **S-020 (Spring Security 7):** Spring Security is **deliberately absent**. Adding the starter at S-020 flips Spring's default to deny-all — that's S-020's design problem. S-001 contract: no `SecurityFilterChain` bean, no `@EnableWebSecurity`, no security starter dependency.
- **S-022 (`@TenantId`):** no JPA, no Hibernate config, no session/cookie behavior in `application.yml` that would later conflict with tenant context propagation. The skeleton has no servlet filters and no `WebMvcConfigurer` beans — clean slate.
- **S-027 (audit log):** `logback-spring.xml` reserves MDC keys `request_id`, `tenant_id`, `actor_user_id` in the layout pattern. S-027 owns the filter that populates them.
- **S-030 (Actuator + Micrometer):** starter already pulled. Exposure is `health,info` only; S-030 widens it and adds `micrometer-registry-prometheus`. Contract: don't pre-expose endpoints that S-030 expects to gate.
- **S-031 (structured JSON logging):** `logback-spring.xml` exists as a stub; S-031 swaps the encoder. Contract: file path is pinned; MDC keys are pinned.
- **S-040 (Dockerfile):** `./gradlew bootJar` produces `build/libs/fls-server-0.0.1-SNAPSHOT.jar`. S-040's Dockerfile `COPY`s this. Contract: keep the build single-module so the artifact path is stable.
- **S-110 (T3 smoke):** consumes `/actuator/health` (preferred) or `/api/v1/hello`. Once a real domain endpoint exists, T3 should pivot off `/hello` so S-001's placeholder can be deleted.

### Alternatives considered

- **Gradle Kotlin DSL (chosen) vs. Maven.** See Build-tool decision.
- **Spring Web MVC (chosen) vs. Spring WebFlux.** ADR 0001 doesn't specify, but every downstream design assumes a synchronous `EntityManager`-bound stack: Hibernate `@TenantId` (S-022) uses `ThreadLocal` tenant context; there's no production-grade R2DBC-Hibernate bridge. MVC + virtual threads gives async-ish throughput without giving up JPA. Dismissed WebFlux.
- **JSpecify + NullAway (chosen) vs. Checker Framework vs. SpotBugs `@NonNull`.** ADR 0001 names JSpecify + NullAway. Checker is too heavy; SpotBugs `@NonNull` is documentation, not enforcement.
- **Single-module Gradle (chosen) vs. multi-module.** Mid-size monolith for a solo operator: single fat JAR + simpler operability beats `api`/`app`/`domain` sub-module splits. Non-destructive to split later if a shared types module ever becomes useful.
- **Virtual threads on by default — open question.** Performance-engineer recommends enabling now (Java 25 JEP 491 eliminated `synchronized` pinning); solution-architect recommends deferring (some legacy JDBC drivers can still pin on native methods; no workload to measure against in S-001). See `## Open design questions`.
- **CORS bean stub now vs. defer — open question.** Security-engineer recommends profile-gated stub now (empty in prod) to force a visible CORS choice later; solution-architect/requirements-engineer recommend defer to the first frontend integration story. See `## Open design questions`.
- **Lombok no.** ADR 0001 prefers records. S-001 pins: no Lombok in S-001 (and likely never).
- **Spring Modulith / ArchUnit: defer.** Architectural-convention enforcement is a separate story.

## Edge cases & hidden requirements

### Edge cases (per acceptance criterion)

**AC1 — One-command clone + JDK 25 + `GET /actuator/health` returns 200**
- Contributor has JDK 17/21 default with JDK 25 alongside: Gradle toolchain (`languageVersion.set(25)`) handles selection; with Foojay resolver enabled, Gradle auto-provisions JDK 25. Document on/off choice in README — recommend **on** (one-command run wins over offline-friendliness for a small team).
- Contributor has only JDK 24 or 26 installed: build must fail with a readable toolchain message, not silently downgrade.
- Windows vs. Linux vs. macOS: ADR pins Linux runtime, but contributors may be on Windows. Both `gradlew` and `gradlew.bat` committed; `.gitattributes` enforces LF.
- Port 8080 already in use locally: server should fail with a clear bind error. Default is 8080; legacy Mono service runs on 25567, so no collision.
- Actuator base path not customized: `/actuator/health` literal — do not set `management.endpoints.web.base-path` to anything else.
- Health-indicator surprise once Flyway lands in S-009: `db` indicator auto-registers and flips status to DOWN if no DB configured. Mitigation: when S-009 lands JDBC, it owns disabling the `db` indicator OR providing a wired datasource. S-001 carries no JDBC on the classpath, so the indicator doesn't auto-register here.

**AC2 — Build tool decision committed, README explains why**
- README must match the actual checked-in tooling. Story decides Gradle Kotlin DSL — README documents that choice with the four reasons.
- Decision lives in README, not a separate ADR (operator preference per the story's existing Notes). State this explicitly so future contributors don't hunt for an ADR.

**AC3 — JSpecify + NullAway, deliberately-null test fails the build**
- NullAway false-positive policy: `@SuppressWarnings("NullAway")` allowed only with a comment explaining why. Document in README.
- Annotated-packages allowlist: `NullAway:AnnotatedPackages=ch.fls`. Without this, Spring framework internals drown the build in noise.
- Exactly one nullable annotation source: `org.jspecify.annotations.Nullable`. Ban `javax.annotation.Nullable` and `jakarta.annotation.Nullable` via Checkstyle or ArchUnit (deferred to a follow-up story; lock the convention in README now).
- "Fails the build" = compile-time NullAway error, not a runtime test assertion. Use a side source set (`src/nullawayDemo/java/`) + a Gradle task that asserts `compileNullawayDemoJava` fails.
- JSpecify `@NullMarked` at package level so the surface is non-null without per-method `@NonNull`.

**AC4 — Spring Boot 4.x conventions: `application.yml`, `@SpringBootApplication`, package-by-domain**
- Both `application.yml` and `application.properties` present is a footgun (load order surprises). Skeleton ships only `.yml`; delete any auto-generated `.properties`.
- Profile files: `application-dev.yml` and `application-test.yml` and `application-prod.yml` all committed day 1, even if mostly empty — forces explicit prod config in every later story.
- `@SpringBootApplication` placement at `ch.fls.FlsApplication` so component scan covers every `ch.fls.<domain>` package added later.
- Package-by-domain example on day 1: `ch.fls.platform.hello` for the demo endpoint. Demonstrates the convention; doesn't pre-commit to any business domain naming.

**AC (additional) — Actuator `/health` + `/info` exposed; springdoc on classpath**
- `management.endpoints.web.exposure.include: health,info` — pinned explicitly, not by omission. See Security plan.
- `/info` empty in S-001 (no `info.*` keys); S-003 may add build-info contributors.
- springdoc dependency on classpath but `springdoc.api-docs.enabled=false` keeps endpoints dormant. S-003 flips the switch.

### Hidden requirements

- **JDK toolchain pinning at build-tool layer** (not just README).
- **Gradle wrapper committed** with `distributionSha256Sum` in `gradle-wrapper.properties` for supply-chain hygiene.
- **`.gitattributes`** for LF normalization (Windows contributors → Linux deploy).
- **`.gitignore` baseline** covering `build/`, `.gradle/`, `.idea/`, `*.iml`, `out/`, `bin/`, `.env`, `.env.local`, `hs_err_pid*.log`.
- **`.editorconfig`** so IntelliJ / VSCode agree on formatting.
- **Servlet vs. Reactive pinned explicitly:** `spring-boot-starter-web`, NOT `webflux`.
- **Logging defaults:** plain console for dev; reserve `logback-spring.xml` MDC keys for `request_id`, `tenant_id`, `actor_user_id` (S-027/S-031 populate them).
- **Secrets strategy:** `${ENV_VAR:default}` placeholders; `.env.example` committed; `.env` gitignored. Spring Boot doesn't natively read `.env` — wire `spring-dotenv` OR pass env vars via the shell.
- **Graceful shutdown:** `server.shutdown=graceful` so deploys don't drop in-flight requests.
- **Health probe semantics:** `management.endpoint.health.probes.enabled=true`, `show-details=when_authorized` (not `always` — info disclosure).
- **CORS not wired in S-001** (the legacy `*` baseline is in `flsserver/src/FLS.Server.Web/App_Start/WebApiConfig.cs` — don't inherit it). See Open design questions for whether to stub a CORS bean now or defer.
- **License / copyright header policy:** flag for a follow-up; not S-001 unless operator decides.

### Scope clarifications

**In:** `next/server/` created; Gradle Kotlin DSL with wrapper + toolchain; Java 25 + Spring Boot 4.x; starters (web, actuator, validation, configuration-processor); springdoc on classpath (disabled); JSpecify + NullAway as compile-failing; `application.yml` + profiles with all the load-bearing keys above; `@SpringBootApplication` at `ch.fls`; demo `HelloController` at `/api/v1/hello`; MockMvc + context-load tests; README with build-tool rationale + run/build/test instructions.

**Out:** Database / Flyway (S-009); Spring Security (S-020); JPA entities / `@TenantId` (S-022); Dockerfile (S-040); springdoc wiring (S-003); production JSON logging encoder (S-031); audit-log infrastructure (S-027); request-id filter implementation (S-022/S-027 — MDC keys only reserved here); CI pipeline (follow-up).

**Ambiguous (resolved):**
- Hello endpoint: kept in S-001 final state with `// TODO(S-020): remove or auth-gate before cutover`. Smoke test asserts on it.
- springdoc on classpath: dependency present; `springdoc.api-docs.enabled=false` keeps endpoints dormant until S-003.
- Build-tool decision: in README, not a separate ADR.
- Java package: `ch.fls` despite folder slug `next/`. Renaming Java packages later is mechanical.
- Lombok: no.
- Virtual threads: see `## Open design questions`.
- CORS bean stub: see `## Open design questions`.

### NFR call-outs

- **Performance:** No SLA yet. JVM startup < 5s on dev, < 8s on 1 vCPU VPS; heap RSS < 256 MB at startup. See Performance plan.
- **Security:** Actuator narrow exposure pinned; stacktraces off; `open-in-view=false` pinned; `fail-on-unknown-properties=true`; payload caps in place. See Security plan.
- **Observability:** MDC keys reserved in `logback-spring.xml`; logback config committed (not autoconfig default) so S-031 has a single place to evolve.
- **Accessibility / i18n:** N/A (server-only).
- **Compliance (C4 Swiss/EU residency):** nothing in the skeleton constrains hosting region.

## Security plan

### Threat model

- **Actuator over-exposure (HIGH).** Spring Boot's default exposes only `health` + `info`, but `management.endpoints.web.exposure.include=*` is a one-liner that would instantly leak `/env`, `/heapdump`, `/threaddump`, `/loggers`, `/configprops`, `/beans`. **Mitigation:** in `application.yml` pin `include: health,info` AND `exclude: env,heapdump,threaddump,loggers,configprops,beans,shutdown` explicitly. Pin `management.endpoint.health.show-details: when_authorized` (not `always`).
- **Stack-trace info disclosure in error responses (MED).** Default `ErrorAttributes` can include stack trace. **Mitigation:** `server.error.include-stacktrace: never`, `include-message: never` (prod only — dev profile may set `always`), `include-binding-errors: never`, `include-exception: false`.
- **Open-in-view leaking lazy loads outside tx (MED, decided now).** Spring's `spring.jpa.open-in-view=true` default is the wrong choice. **Mitigation:** pin `spring.jpa.open-in-view: false` now (no-op without JPA starter; locks the invariant for S-012).
- **Banner / version disclosure (LOW).** Acceptable per industry practice. `spring.main.banner-mode: off` in prod profile.
- **Hello endpoint as permanent unauthenticated surface (LOW).** Trivial DoS amplification target. **Mitigation:** `// TODO(S-020): remove or auth-gate before cutover` on the controller; S-020 must address.
- **CORS (preempted).** Legacy is `*`. The skeleton must not inherit. See Open design questions for whether to stub the CORS bean now (security-engineer preferred) or defer (architect preferred).
- **CSRF (N/A).** No `spring-boot-starter-security` here; CSRF defaults don't apply. S-020 owns when CSRF turns on.
- **Default bind address (LOW).** `0.0.0.0:8080`; prod exposure is via reverse proxy (S-041). Document in README.
- **Jackson deserialization defaults (LOW, decided now).** **Mitigation:** `spring.jackson.deserialization.fail-on-unknown-properties: true` and `mapper.accept-case-insensitive-properties: false`. First DTO inherits strict parsing.
- **Unbounded request bodies (LOW).** **Mitigation:** `server.tomcat.max-http-form-post-size: 1MB`; `spring.servlet.multipart.max-file-size: 10MB`, `max-request-size: 10MB`.
- **Secrets committed to YAML (HIGH if violated).** **Mitigation:** every secret-shaped key uses `${ENV_VAR}` (no inline default for prod-relevant secrets; dev may use `${VAR:dev-default}`). `.env.example` committed; `.env` gitignored.
- **Supply-chain / unpinned toolchain (MED).** **Mitigation:** `gradle-wrapper.properties` pins `distributionUrl` + `distributionSha256Sum`; Gradle toolchain pins JDK 25; wrapper JAR committed; CI runs `gradle/wrapper-validation-action`.

### Authorization

N/A — Spring Security and `@PreAuthorize` land in S-020. The skeleton ships no auth surface; `/api/v1/hello` and `/actuator/health|info` are intentionally unauthenticated. README documents this and points to S-020.

### Input validation

- Hello endpoint takes no input.
- Skeleton includes `spring-boot-starter-validation` so downstream DTOs use `@Valid`, `@NotNull`, `@Size`, `@Email`, `@Pattern` without revisiting the build.
- Strict Jackson deserialization (see Threat model) is input validation at the DTO boundary.

### PII handling

- N/A in this story (no domain code).
- `logback-spring.xml` reserves MDC keys `request_id`, `tenant_id`, `actor_user_id` (empty here; populated by S-022/S-027 filter). Avoids a logback rewrite later.
- README convention: **future entities and DTOs must not include PII in `toString()`**; prefer records with explicit `toString` overrides. Enforcement is S-027.
- Hello endpoint logs no request data; smoke test asserts this.

### Audit-log events

- N/A — audit infrastructure is S-027.
- No `@Around` advice or logging interceptor committed in this story; S-027 owns the audit hook design.

### Cross-tenant leakage

- N/A — no entities, no `@TenantId`, no queries.
- `spring.jpa.open-in-view: false` pinned now so Hibernate session boundary is correct when S-022 lands (the classic open-in-view footgun for multi-tenancy).
- No `spring.datasource.*` committed — S-009 owns the DB story.

### OWASP applicability

- **A01 Broken Access Control:** N/A; hello deliberately public. S-020 owns auth gates.
- **A02 Cryptographic Failures:** N/A — no secrets, no PII, no DB.
- **A03 Injection:** N/A — no DB, no templating, no user input. `fail-on-unknown-properties=true` as defensive default.
- **A04 Insecure Design:** N/A at scaffold scope.
- **A05 Security Misconfiguration: DOMINANT RISK.** All Spring keys pinned above.
- **A06 Vulnerable Components:** Spring Boot 4.x, Java 25, Gradle SHA-256 pinned. Renovate / dep-check are a follow-up story.
- **A07 Authentication Failures:** N/A (S-020).
- **A08 Software & Data Integrity Failures:** wrapper SHA-256 + wrapper-validation; no untrusted-data deserialization.
- **A09 Logging & Monitoring Failures:** MDC keys reserved; logback config committed.
- **A10 SSRF:** N/A — no outbound HTTP clients.

### Skeleton-specific items

- **Profile layout:** `application.yml` (shared safe defaults) + `application-dev.yml` + `application-test.yml` + `application-prod.yml`. `SPRING_PROFILES_ACTIVE` documented in README.
- **Toolchain pinning:** `gradle-wrapper.properties` pins both `distributionUrl` and `distributionSha256Sum`; `java.toolchain.languageVersion = JavaLanguageVersion.of(25)`.
- **`.gitattributes`:** `* text=auto eol=lf` so Windows contributors don't pollute the repo.
- **CI baseline:** follow-up story to add `gradle/wrapper-validation-action`, Renovate, `dependency-check`. Skeleton must `./gradlew build` cleanly so CI is a wrapper around the existing tasks.

## Test plan

### Coverage contract

S-001 owns: build-time NullAway enforcement (deliberately-null code fails the build); `@SpringBootTest` context-loads smoke; `@WebMvcTest` hello slice; Actuator health smoke; toolchain pin regression; placeholder unit test proving the JUnit-5 runner is wired.

S-001 explicitly defers: DB integration tests (S-015); Spring Security smoke (S-020); tenant-leakage tests (S-024); audit-log emission (S-027); OpenAPI generation smoke (S-003); `spring.jpa.open-in-view=false` runtime assertion (S-009/S-012 — no JPA starter to bind against here); Playwright T3 smoke (S-109/S-110).

No parity oracle exists; nothing legacy-side to compare against.

### Test pyramid

- **Build-time checks:** 3 — NullAway negative compile (side source set fails); toolchain pin enforces Java 25; Gradle `failOnVersionConflict()` reports clean.
- **Unit:** 1 — `SmokeUnitTest` proving JUnit 5 runner is wired.
- **Integration:** 3 — `ApplicationContextTest`, `HelloControllerIT`, `ActuatorHealthIT`.
- **E2E:** 0 — owned by S-110.
- **Parity:** 0 — N/A.

### Unit tests

- `SmokeUnitTest.junit5RunnerIsWired` — `next/server/src/test/java/ch/fls/SmokeUnitTest.java`. Trivial assertion; proves the Gradle `test` task discovers JUnit 5 tests.

### Integration tests

- `ApplicationContextTest.contextLoads` — `@SpringBootTest`, empty body, no `@MockBean`. Catches DI/`@Configuration` misconfig on every PR. `next/server/src/test/java/ch/fls/ApplicationContextTest.java`.
- `HelloControllerIT.helloEndpointReturns200WithExpectedBody` — `@WebMvcTest(HelloController.class)`, `MockMvc.perform(get("/api/v1/hello"))` asserts 200 + body shape `{"message":"Hello FLS",…}`. `next/server/src/test/java/ch/fls/platform/hello/HelloControllerIT.java`.
- `ActuatorHealthIT.actuatorHealthReturns200` — `@SpringBootTest(webEnvironment=RANDOM_PORT)` + `TestRestTemplate`; `GET /actuator/health` → 200 + `{"status":"UP"}`. Full-context (verifies exposure-include property works end-to-end). `next/server/src/test/java/ch/fls/actuator/ActuatorHealthIT.java`.
- `ToolchainTest.javaSpecVersionIs25` — pure JUnit; asserts `System.getProperty("java.specification.version").equals("25")`. Cheap regression. `next/server/src/test/java/ch/fls/build/ToolchainTest.java`.

### Build-time / static checks

- **NullAway negative check** — side source set `src/nullawayDemo/java/ch/fls/nullaway/NullDereferenceDemo.java` with a deliberate null-deref. Custom Gradle task `verifyNullAwayFailsOnViolation` runs `compileNullawayDemoJava` and asserts compile failure. CI invokes both `./gradlew check` (must pass) and `./gradlew verifyNullAwayFailsOnViolation` (must fail-as-expected). ~30 lines of Gradle vs. TestKit (overkill).
- **Spotless / formatter check:** defer if Spotless not pulled in.
- **Dependency-conflict report:** `resolutionStrategy.failOnVersionConflict()` in `build.gradle.kts` fails the build on conflicts.

### E2E tests
None. Owned by S-110.

### Parity tests
None. No legacy oracle.

### Test data + fixtures
N/A — no DB, no domain entities. Testcontainers infrastructure lives in S-015.

### Coverage gaps (deferred)

- DB integration → S-015.
- Spring Security 401/403 → S-020.
- Tenant-leakage harness → S-024.
- Audit-log emission → S-027.
- OpenAPI generation smoke → S-003.
- `spring.jpa.open-in-view=false` runtime assertion → S-009/S-012.
- Full-app Playwright smoke → S-109/S-110.
- Java 25 + NullAway version-pin verification → manual at story-impl time, then locked via Gradle version catalog.

### Risks

- **NullAway demo shape.** Side-source-set + `buildShouldFail` task is ~30 Gradle lines; TestKit alternative is ~100+ and adds a test-only plugin dep. Ship the side-source-set; document update procedure in README.
- **NullAway + JDK 25 compatibility.** NullAway runs as an Error Prone plugin, which tracks JDK releases on a lag. Lock a NullAway release verified against JDK 25; CI matrix runs JDK 25 only.
- **`@SpringBootTest` cold-start cost.** First context ~2-3s, warm sub-second. Keep `ApplicationContextTest` and `ActuatorHealthIT` free of `@MockBean`/`@TestPropertySource` overrides so Spring's context cache reuses across both.
- **`@WebMvcTest` slice doesn't catch full-app misconfig.** Mitigated by pairing with the full-context smoke.
- **Actuator security defaults change when S-020 lands.** Once Spring Security is on the classpath, `ActuatorHealthIT` may need `@WithMockUser` or a `permitAll` exemption. Flag for S-020's test-plan handoff, not here.
- **CI java-version drift.** Even with Gradle toolchain pinned, GitHub Actions runners default differently. Explicit `actions/setup-java@v4` with `java-version: 25` in any CI workflow.

## Performance plan

### Hot paths
- `GET /api/v1/hello` — single-call smoke test, not a production path.
- `GET /actuator/health`/`info` — polled by uptime monitor / Docker healthcheck. Composite of `ping` + `diskSpace` only.
- No business endpoints exist yet. Real hot paths arrive in S-062a (flight list, 5–15 rps mixed-filter) and S-074..S-077 (rules engine batch).

### Required indexes
N/A — no schema. Deferred to S-013 (Flyway baseline), S-058 (Flight entity), S-062a (list-query indexes).

### N+1 risks
N/A — no JPA. **Pin `spring.jpa.open-in-view=false` now** even though there's no JPA starter — locks the invariant for S-012. Forces every list endpoint (S-058+) to fetch-join, `@EntityGraph`, or DTO-project. Surfaces `LazyInitializationException` loudly in dev rather than hiding N+1 behind the OSIV session.

### Cartesian / explosion risks
N/A.

### Caching strategy

**Server-side:** N/A. Do NOT add `spring-boot-starter-cache` or Caffeine yet; S-062a owns master-data caching (Caffeine, TTL 10min, invalidate on mutation).

**Client-side:** N/A — Angular Signal Store cache is downstream (S-006/S-062b).

**HTTP-level:** do NOT enable response cache headers on `/actuator/health` (must always reflect live state).

### Latency budget

Anchored to NFR p95 < 500ms read; tighter at skeleton because there's no business logic. Deviation flags misconfig.

- **Cold start → `/actuator/health` 200:** < **5s** on dev (Ryzen/M-class), < **8s** on 1 vCPU VPS. Slower indicates excess starters or aggressive classpath scanning.
- **`GET /api/v1/hello` p95:** < **20ms** under no load.
- **`GET /actuator/health` p95:** < **30ms**.
- **First request after startup:** typically 2-5x slower due to JIT C2 compilation. Document; do not optimize.

### Memory considerations

- **Heap baseline after startup:** target RSS < **256 MB** for the empty skeleton. Sets VPS-sizing baseline for S-044.
- **Heap defaults:** Spring Boot 4.x sets no `-Xmx`; cgroup-aware JVM defaults to 25% of container memory. **Do not pin `-Xmx` in S-001** — Dockerfile (S-040) does it for prod (`-Xmx512m`).
- **JVM flavor:** JVM, not GraalVM native. Do NOT add `org.springframework.experimental.aot` or `org.graalvm.buildtools.native` plugins.
- **Virtual threads:** when enabled, platform-thread stack memory drops significantly under load. At skeleton stage with no load, no measurable effect.

### Performance test plan

- **Cold-start CI check:** CI job builds JAR, runs `java -jar build/libs/server.jar &`, polls `/actuator/health` with 10s timeout, asserts 200. Fails if startup degrades. Threshold: **boot-to-health-200 < 10s** in CI (2x dev target to absorb GitHub Actions CPU variance).
- **Hello endpoint smoke:** existing MockMvc smoke covers correctness; do NOT add `@Timed`/latency assertions (flaky on CI). Latency enforcement starts in S-062b.
- **Memory smoke (optional):** CI step parses `/actuator/metrics/jvm.memory.used` area=heap, asserts < 300 MB. Catches accidental heap bloat.
- **k6 load:** not in this story. Starts S-062b / S-108.
- **Hibernate query-count assertions:** N/A.

### Configuration choices that affect future perf

Pin these in `application.yml` now; they shape every downstream story.

- **`spring.jpa.open-in-view: false`** — highest-leverage perf default in the skeleton.
- **`spring.threads.virtual.enabled: ?`** — see `## Open design questions`.
- **`server.tomcat.threads.max: 50`** — only if virtual threads enabled (otherwise leave default 200).
- **`server.http2.enabled: true`** — cuts head-of-line blocking for the SPA.
- **`server.compression.enabled: true`** + mime-types — 60-80% JSON payload reduction.
- **`spring.main.lazy-initialization: false`** (default) — faster first request + simpler `@Scheduled` startup semantics. Resist enabling later.
- **`spring.jackson.serialization.write-dates-as-timestamps: false`** + `spring.jackson.time-zone: UTC` — ISO-8601 strings, smaller and debuggable.
- **`spring.jackson.default-property-inclusion: non_null`** — drops null fields.
- **`logging.level.org.hibernate.SQL: WARN`** (default) — when JPA arrives in S-012, keep SQL logging off in prod (`DEBUG` SQL adds 30-50% latency to JDBC calls).

### Risks

- **Virtual threads pin on legacy `synchronized` or native methods.** JEP 491 fixed `synchronized` in Java 21+; native methods can still pin. Enable `-Djdk.tracePinnedThreads=short` in dev profile if virtual threads chosen. Verify under load in S-062a. Fallback: disable virtual threads if pinning pervasive.
- **`open-in-view=false` will cause `LazyInitializationException` in downstream code that forgets to fetch-join.** Desired failure mode — loud in dev > silent N+1 in prod. Document in README.
- **HTTP/2 + reverse proxy (S-044):** ensure Caddy/Traefik terminates TLS and speaks h2c upstream to Spring; mismatch causes silent fallback to HTTP/1.1. Verify in S-044.
- **Cold-start CI assertion flakiness:** GitHub Actions runner CPU varies 2-3x. Threshold at 10s (2x dev target).
- **Lazy-init temptation later:** if boot time creeps up, resist `spring.main.lazy-initialization=true`. Fix root cause (excess starters, eager `@PostConstruct`).

## Open design questions

These specialists' analyses disagreed; surfaced for operator input.

1. **Virtual threads on by default in S-001?**
   - **Performance-engineer (enable):** Java 25 + Spring Boot 4.x. FLS workload is JDBC-blocking; JEP 491 eliminated the `synchronized` pinning footgun that was the historical blocker. Cost is one config key.
   - **Solution-architect (defer):** some downstream stories (S-018 ShedLock, S-022 `@TenantId` `ThreadLocal`) need to be virtual-thread-audited before flipping; legacy JDBC drivers may still pin on native methods; no workload to measure against at skeleton stage. Turn on in a dedicated perf story after S-022 + S-081 land.
   - **Trade-off:** enabling costs near-zero now but commits the project to verifying every later concurrency primitive. Deferring keeps the skeleton minimal but means later perf work must include a "flip virtual threads + measure" pass.

2. **Profile-gated CORS stub in S-001 vs. defer entirely?**
   - **Security-engineer (stub now):** ship `CorsConfigurationSource` `@Bean` profile-gated to `dev` (`http://localhost:4200` allow-list) and `prod` (empty allow-list). Empty prod forces a visible CORS commit in a later story rather than silent inheritance of legacy `*`. Defensive default.
   - **Solution-architect / requirements-engineer (defer):** S-001 has no frontend to integrate; first CORS need is when S-021 (Angular OIDC client) lands. Adding a CORS bean without a consumer is speculative.
   - **Trade-off:** stub now = visible prod-config gap surfaced earlier; defer = leaner skeleton, deferred decision visible at S-021 PR time. README note documenting the deferral handles either choice.

<!-- modernize-refine: end -->
