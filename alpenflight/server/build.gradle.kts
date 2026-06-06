import de.aaschmid.gradle.plugins.cpd.Cpd
import java.io.ByteArrayOutputStream
import java.time.Instant
import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone
import net.ltgt.gradle.nullaway.nullaway

plugins {
    java
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
    id("net.ltgt.errorprone") version "5.1.0"
    id("net.ltgt.nullaway") version "3.0.0"
    // S-009: Flyway Gradle plugin for `flywayInfo` / `flywayValidate` /
    // `flywayMigrate` invokable from CI + ad-hoc local runs. Plugin version
    // is independent of the BOM-managed flyway-core version.
    id("org.flywaydb.flyway") version "11.14.1"
    // J-5 T-11 (boyscout rider): the maintainability axes the architecture
    // guards (Spring Modulith ApplicationModulesTest + ArchUnit) do NOT cover —
    // complexity + dead/unused code (PMD) and copy-paste duplication (CPD). The
    // Java analog of fallow on the TS side ([[reference_fallow_maintainability_analyzer]]).
    //   - `pmd`  : Gradle built-in; curated ruleset at config/pmd/ruleset.xml.
    //   - `cpd`  : de.aaschmid copy-paste detector (PMD's CPD), `cpdCheck` task.
    // Both are REPORT-GENERATING + RATCHET-GUARDING, not hard-failing on today's
    // debt (see the pmd{}/cpd{} blocks + the cpdRatchet task below).
    pmd
    id("de.aaschmid.cpd") version "3.5"
}

// Add the Postgres database module to the Flyway Gradle plugin's classpath
// (separate from `implementation` which is Spring Boot's runtime). Without
// this, `./gradlew flywayMigrate` reports "No Flyway database plugin found
// to handle jdbc:postgresql://...".
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.flywaydb:flyway-database-postgresql:11.14.1")
    }
}

group = "ch.alpenflight"
version = "0.0.1-SNAPSHOT"
description = "AlpenFlight server"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// S-155 (ADR 0023): Spring Modulith verifies module-level boundaries between
// top-level packages under ch.alpenflight (clubs, auth, platform, …); ArchUnit
// verifies inner-layer direction-of-dependency inside each module
// (domain/application/web/infra). Both are test-only — runtime classpath is
// unaffected.
dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:2.0.5")
    }
}

// Dedicated source set used by `verifyNullAwayFailsOnViolation` to prove that
// null-safety violations fail the build (acceptance criterion 3). Not wired
// into `check` / `build`; only the verification task invokes its compile.
val nullawayDemo: SourceSet by sourceSets.creating {
    java.srcDir("src/nullawayDemo/java")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations.named("nullawayDemoImplementation").configure {
    extendsFrom(configurations.implementation.get())
}

// S-155: regression source set for ADR-0023 layering rules. Holds three
// deliberate violations (one per rule) + a test class that re-declares
// the same rules and asserts each fires. Wired only into the
// `verifyArchUnitFailsOnViolation` task — NOT into `test` / `check`, so
// `./gradlew test` is unaffected.
val archDemo: SourceSet by sourceSets.creating {
    java.srcDir("src/archDemo/java")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}

configurations.named("archDemoImplementation").configure {
    extendsFrom(configurations.implementation.get())
    extendsFrom(configurations.testImplementation.get())
}

configurations.named("archDemoRuntimeOnly").configure {
    extendsFrom(configurations.testRuntimeOnly.get())
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // S-137: AOP runtime (aspectjweaver + Spring AOP) for the
    // @LifecycleStateFilter @Around advice on @Scheduled job classes.
    // Webmvc starter pulls spring-aop transitively but not aspectjweaver;
    // @Aspect needs the weaver. Spring Boot 4 restructured the starter
    // hierarchy — spring-boot-starter-aop is no longer published; pull
    // spring-aop (already managed by the BOM) + aspectjweaver directly.
    implementation("org.springframework:spring-aop")
    implementation("org.aspectj:aspectjweaver")
    // S-048 adds JPA on top of the JDBC starter (the JDBC dep stays so Flyway
    // keeps working with its lightweight DataSource). Hibernate ships under
    // the JPA umbrella.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // Spring Security activates the default chain at startup; the production
    // SecurityConfig wires it as an OAuth2 resource server (per ADR 0007 /
    // S-020) and the @PreAuthorize predicates on controllers (S-026) are the
    // method-level gates.
    implementation("org.springframework.boot:spring-boot-starter-security")
    // Resource-server + jose modules bring `Jwt`, `JwtAuthenticationToken`,
    // and `JwtAuthenticationConverter`. JwtDecoderConfig consumes them to
    // validate incoming Bearer tokens against the configured issuer's JWKS.
    implementation("org.springframework.security:spring-security-oauth2-resource-server")
    implementation("org.springframework.security:spring-security-oauth2-jose")
    // S-022: application-side UUID v7 generation per ADR 0019. Wired by
    // `FlsUuidV7Generator` (Hibernate `BeforeExecutionGenerator`) and the
    // `@UuidV7` meta-annotation. Time-ordered keys give B-tree-friendly
    // inserts which matters for S-028's bulk cutover; gen_random_uuid()
    // default values on PKs are forbidden by `forbidden-migration-patterns.txt`.
    implementation("com.github.f4b6a3:uuid-creator:6.0.0")
    // S-027: bounded TTL cache for the JWT sub → user.id lookup that fires
    // on every authenticated request before the audit row is written.
    // Spring Boot BOM manages the Caffeine version. Excluded transitive
    // error-prone annotations — Caffeine's 3.2 line bumps them past the
    // version Spring's `errorprone` configuration already brings, which
    // trips `failOnVersionConflict()`. The annotations are compile-only
    // markers (@CheckReturnValue etc.) and unused at runtime.
    implementation("com.github.ben-manes.caffeine:caffeine") {
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    }
    // Boot 4 modularized: FlywayAutoConfiguration moved out of
    // spring-boot-autoconfigure into spring-boot-flyway. flyway-core alone
    // does NOT bring it in — explicit declaration needed.
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    // springdoc 3.x is the Boot-4-compatible line (parent POM = spring-boot-starter-parent
    // 4.0.x). 2.8.x referenced the pre-modular `org.springframework.boot.autoconfigure
    // .web.servlet.WebMvcProperties` path that Boot 4 moved to `.webmvc.autoconfigure`,
    // which broke springdoc context startup on this stack.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
    // S-140: Google Tink AEAD for the per-upload private-key envelope.
    // AEAD primitive only at this story; S-141 may adopt StreamingAead
    // (AES256_GCM_HKDF_4KB) on top of the same KeysetHandle.
    // Exclude error_prone_annotations to dodge failOnVersionConflict() —
    // same rationale as Caffeine above (compile-only marker annotations).
    implementation("com.google.crypto.tink:tink:1.18.0") {
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    }
    implementation("org.jspecify:jspecify:1.0.0")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    errorprone("com.google.errorprone:error_prone_core:2.49.0")
    errorprone("com.uber.nullaway:nullaway:0.13.4")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    // No H2 — every @SpringBootTest shares a single Postgres testcontainer
    // via SharedPostgresContainer. The Docker daemon is a hard requirement
    // for the DB-touching tests; @EnabledIf("dockerAvailable") on each class
    // skips them cleanly when Docker is absent (`./gradlew check` still
    // passes).
    // Boot 4.0 split: TestRestTemplate (in spring-boot-resttestclient) depends
    // on RestTemplateBuilder which lives in spring-boot-restclient.
    testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
    // spring-security-test provides @WithMockUser + MockMvc `.with(jwt())`
    // post-processors so role-gate ITs can plant arbitrary principals + claims
    // per request without crafting a full RSA-signed JWT (the live decoder
    // path is covered by SecurityFilterChainIT against JwtTestFixture).
    testImplementation("org.springframework.security:spring-security-test")
    // S-155: Spring Modulith annotation types (@ApplicationModule, @NamedInterface)
    // on the compile classpath only — package-info declarations need them, but
    // we deliberately don't pull in spring-modulith-starter-core's runtime
    // auto-config (event-publication registry, observability) until ADR 0018
    // domain events first ship.
    compileOnly("org.springframework.modulith:spring-modulith-api")
    // S-155: ApplicationModules.of(...).verify() — Spring Modulith's module
    // boundary check. Test-only; the BOM (above) pins the version.
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    // S-155: ArchUnit + its JUnit 5 runner — inner-layer direction-of-dependency
    // rules (ADR 0023). Test-only.
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // S-141 promoted to `implementation` (was testImplementation under S-187):
    // the bundle-ingest pipeline calls `Mapper.readEntity` + `KnownMappers.all()`
    // + `LegacyIdMapTables.resolveForeignKeyArrayQuery` from production code.
    // Module coordinate matches `rootProject.name` in
    // `migration-bundle/settings.gradle.kts`.
    implementation("ch.alpenflight:alpenflight-migration-bundle")
    // S-141 needs the org.postgresql driver classes on the COMPILE classpath
    // (PgConnection + CopyManager for binary COPY into the per-bundle
    // legacy_id_map_<entity> temporary tables). The driver itself stays
    // runtimeOnly above; this compileOnly declaration exposes the API surface.
    compileOnly("org.postgresql:postgresql")
    // S-141 streaming gzip + tar reader for the encrypted bundle body.
    // Same artifact the migration-bundle module's parity producer uses on
    // the write side — keeps producer + consumer on lockstep tooling.
    implementation("org.apache.commons:commons-compress:1.27.1")

    // J-0b T-10: TEST-only — LocationRealProducerRoundTripIT builds a bundle via
    // the REAL BundleWriter.assembleTarGz (not the hand-ordered test factory) to
    // prove the pgcopy-before-NDJSON tar entry order survives a real producer run
    // through the real ingest. No production dependency on the export JAR.
    testImplementation("ch.alpenflight:alpenflight-migration-tool")
}

nullaway {
    // ch.alpenflight is treated as @NullMarked; NullAway enforces non-null
    // defaults for everything under it. Spring framework internals stay
    // un-checked.
    onlyNullMarked = true
}

tasks.withType<JavaCompile>().configureEach {
    options.errorprone.nullaway {
        severity = CheckSeverity.ERROR
    }
}

// Test compile: NullAway off (mocks + null assertions are legitimately
// nullable / unset). Keep the rest of Error Prone's checks active — disabling
// errorprone wholesale would silently drop FutureReturnValueIgnored,
// EqualsHashCode, etc. as the test suite grows.
tasks.named<JavaCompile>("compileTestJava") {
    options.errorprone.nullaway {
        severity = CheckSeverity.OFF
    }
}

// archDemo compile: NullAway off (the deliberate-leak classes carry no
// null-safety contract — they exist purely to trip ArchUnit). Same
// rationale as compileTestJava.
tasks.named<JavaCompile>("compileArchDemoJava") {
    options.errorprone.nullaway {
        severity = CheckSeverity.OFF
    }
}

// Custom verification task: runs the demo compile in a sub-Gradle invocation
// and asserts it fails because of NullAway. Wired into `check` is intentional
// NOT done — CI / contributors run this explicitly:
//     ./gradlew verifyNullAwayFailsOnViolation
val verifyNullAwayFailsOnViolation by tasks.registering(Exec::class) {
    group = "verification"
    description = "Asserts NullAway rejects a deliberately null-passing snippet (AC3)."

    val launcher = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "gradlew.bat" else "gradlew"
    workingDir = rootDir
    commandLine = listOf(
        rootDir.resolve(launcher).absolutePath,
        "compileNullawayDemoJava",
        "--rerun-tasks",
        "--no-daemon",
        "--quiet",
    )
    isIgnoreExitValue = true

    val capturedOutput = ByteArrayOutputStream()
    standardOutput = capturedOutput
    errorOutput = capturedOutput

    val markerFile = layout.buildDirectory.file("nullaway-demo/verified.marker")
    outputs.file(markerFile)

    doLast {
        val exit = executionResult.get().exitValue
        val out = capturedOutput.toString()
        if (exit == 0) {
            throw GradleException(
                "compileNullawayDemoJava unexpectedly succeeded — AC3 (build-time null-safety) is NOT enforced.\n" +
                    "Output was:\n$out",
            )
        }
        if (!out.contains("NullAway")) {
            throw GradleException(
                "compileNullawayDemoJava failed (exit $exit) but NullAway was not the cause. Output was:\n$out",
            )
        }
        val marker = markerFile.get().asFile
        marker.parentFile.mkdirs()
        marker.writeText("verified at ${Instant.now()}\n")
        logger.lifecycle("NullAway correctly rejected the demo snippet (exit code $exit).")
    }
}

// Fail the build if a runtime/compile classpath dep resolves to multiple
// versions. Limited to the production classpaths because errorprone's own
// classpath has unmanaged conflicts (guava + error_prone_annotations).
configurations.matching {
    it.name == "runtimeClasspath" || it.name == "compileClasspath"
}.configureEach {
    resolutionStrategy.failOnVersionConflict()
}

// ---------------------------------------------------------------------------
// J-5 T-11 (boyscout rider) — maintainability: complexity + dead code (PMD) +
// duplication (CPD). The Java analog of fallow on the TS side. The architecture
// axis is already covered (Spring Modulith ApplicationModulesTest + ArchUnit);
// this fills the other three maintainability axes the operator counts
// ([[feedback_maintainability_includes_dupes_and_deadcode]]).
//
// Posture (deliberate): REPORT-GENERATING + RATCHET-GUARDING, NOT a hard fail
// on today's debt.
//   - pmdMain      : ignoreFailures = true  → always emits the XML/HTML report,
//                    never reds the build. Curated ruleset (complexity + unused
//                    code only, no style/naming noise) at config/pmd/ruleset.xml.
//   - cpdCheck     : ignoreFailures = true  → emits the duplication XML report,
//                    never reds the build on its own.
//   - cpdRatchet   : the ONLY duplication gate — fails iff the duplicate-token
//                    total GROWS beyond the committed baseline (config/pmd/
//                    cpd-baseline.txt). Ratchets down automatically when debt
//                    is paid (a lower measurement rewrites nothing; you commit
//                    the new lower baseline to lock the gain).
// Reports under build/reports/{pmd,cpd}/ feed the J-5 T-12/T-13 gallery panel.
// ---------------------------------------------------------------------------

// PMD applies to all source sets by default (incl. test / archDemo / nullawayDemo /
// the generated/demo leaks). We only want production code measured, so disable
// the auto-created tasks for the non-main source sets and keep `pmdMain`.
pmd {
    // 7.25.0: 7.7.0 hit a StackOverflowError in type-resolution on this
    // codebase (deep generic/JDK-25 bytecode parse); the fix landed in a later
    // 7.x. Newer than the Gradle 9.4.1 PMD-plugin default, pinned for repro.
    toolVersion = "7.25.0"
    isConsoleOutput = false
    // Our curated ruleset is the COMPLETE rule list (not additive on top of the
    // PMD default), so switch the built-in defaults off.
    ruleSetConfig = resources.text.fromFile("config/pmd/ruleset.xml")
    ruleSets = emptyList()
    // Report-generating, never a hard build failure on existing debt.
    isIgnoreFailures = true
}

tasks.withType<org.gradle.api.plugins.quality.Pmd>().configureEach {
    // Only the production aggregate matters for the maintainability signal —
    // test/demo/generated code would flood the report with false hotspots.
    enabled = name == "pmdMain"
    reports {
        xml.required = true
        html.required = true
    }
}

// CPD = PMD's copy-paste detector (the duplication axis). minimumTokenCount is
// the sensitivity knob; 50 tokens ≈ jscpd's --min-lines 8 proxy used for the
// 5.57% baseline number in the rider. Scan ONLY production Java.
cpd {
    toolVersion = "7.7.0"
    language = "java"
    minimumTokenCount = 50
    isIgnoreFailures = true
    isIgnoreLiterals = false
    isIgnoreIdentifiers = false
}

tasks.named<Cpd>("cpdCheck") {
    // Production sources only — exclude tests, the archDemo/nullawayDemo leak
    // source sets, and any generated tree.
    source = sourceSets.main.get().allJava
    reports {
        xml.required = true
        text.required = false
    }
}

// The duplication RATCHET. cpdCheck itself never fails (ignoreFailures); this
// task is the gate: it parses the CPD XML, sums the duplicated-token count
// across all <duplication> blocks, and fails iff that total exceeds the
// committed baseline. So existing debt passes, but NEW duplication reds CI.
val cpdBaselineFile = layout.projectDirectory.file("config/pmd/cpd-baseline.txt")

val cpdRatchet by tasks.registering {
    group = "verification"
    description = "Fail iff CPD duplicate-token total grows beyond config/pmd/cpd-baseline.txt (ratchet)."
    dependsOn("cpdCheck")
    val reportXml = layout.buildDirectory.file("reports/cpd/cpdCheck.xml")
    val baseline = cpdBaselineFile
    inputs.file(reportXml).optional(true)
    inputs.file(baseline).optional(true)
    doLast {
        val xml = reportXml.get().asFile
        // No report ⇒ no duplication found ⇒ measured 0 (cpdCheck emits no XML
        // when there are zero clones). Treat absent report as 0 tokens.
        val measured =
            if (xml.exists()) {
                Regex("""<duplication\s+lines="\d+"\s+tokens="(\d+)"""")
                    .findAll(xml.readText())
                    .sumOf { it.groupValues[1].toLong() }
            } else {
                0L
            }
        val baselineFile = baseline.asFile
        val baselineTokens =
            if (baselineFile.exists()) {
                baselineFile.readLines()
                    .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                    ?.trim()?.toLongOrNull() ?: 0L
            } else {
                Long.MAX_VALUE // no baseline committed yet ⇒ never fail; just report
            }
        logger.lifecycle("CPD duplicate tokens: measured=$measured baseline=$baselineTokens")
        if (measured > baselineTokens) {
            throw GradleException(
                "Code duplication grew: $measured duplicated tokens > baseline $baselineTokens " +
                    "(config/pmd/cpd-baseline.txt). Reduce duplication, or — if intentional — " +
                    "re-measure with `./gradlew cpdCheck` and commit the new (lower-is-better) baseline.",
            )
        }
        if (measured < baselineTokens && baselineTokens != Long.MAX_VALUE) {
            logger.lifecycle(
                "Duplication DROPPED below baseline ($measured < $baselineTokens) — " +
                    "commit $measured to config/pmd/cpd-baseline.txt to lock the gain.",
            )
        }
    }
}

// Wire both maintainability checks into `check` (which `build` depends on) so
// they run in CI's `./gradlew build`. pmdMain + cpdCheck emit reports; cpdRatchet
// is the only one that can red the build, and only on GROWTH past the baseline.
tasks.named("check") {
    dependsOn("pmdMain", cpdRatchet)
}

// S-009: Flyway Gradle plugin connection details. Reads env vars so CI can
// inject Postgres credentials without committing them. Defaults to loopback
// dev defaults so an operator with a compose-up Postgres can invoke
// `./gradlew flywayInfo` / `flywayValidate` locally.
flyway {
    url = System.getenv("DATASOURCE_URL") ?: "jdbc:postgresql://localhost:5432/alpenflight"
    user = System.getenv("DATASOURCE_USER") ?: "alpenflight"
    password = System.getenv("DATASOURCE_PASSWORD") ?: "alpenflight"
    locations = arrayOf("filesystem:src/main/resources/db/migration")
    outOfOrder = false
    cleanDisabled = true
    baselineOnMigrate = false
    validateMigrationNaming = true
    // Mirror the application.yml placeholder shape so ad-hoc
    // `./gradlew flywayMigrate` against a local DB doesn't trip on V14's
    // ${alpenflight.operator.keycloak_sub} substitution. Env override
    // honoured for non-loopback environments.
    placeholders = mapOf(
        "alpenflight.operator.keycloak_sub"
            to (System.getenv("ALPENFLIGHT_OPERATOR_KEYCLOAK_SUB")
                ?: "00000000-0000-0000-0000-0000000000ff"),
    )
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        // Surface full stack traces on failure so a CI context-load error
        // doesn't collapse to a single `NoSuchBeanDefinitionException` line.
        events("failed")
        showExceptions = true
        showCauses = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // Optional Gradle-layer fork parallelism for the test task. Each fork's
    // SharedPostgresContainer is its own JVM singleton — separate Postgres
    // container per fork — so TenantTestContext's ThreadLocal carrier never
    // aliases (forks don't share memory). Sequential per-JVM JUnit execution
    // stays on (junit-platform.properties); only the Gradle layer parallelises.
    //
    // Default is 2 (dev sandbox ≥ 11 GiB; CI runners similar). Two forks each
    // boot Spring + Postgres + an idle Hikari pool — fits comfortably.
    // Constrained environments can pin a smaller value via:
    //     ALPENFLIGHT_TEST_FORKS=1 ./gradlew test
    // Expected wall-time reduction at N=2 on the ~50-class suite: ~40-50 %.
    maxParallelForks = (System.getenv("ALPENFLIGHT_TEST_FORKS")?.toIntOrNull() ?: 2).coerceAtLeast(1)
}

// S-155: runs `LayeringRulesDemoTest` (in the archDemo source set) which
// re-declares the production ArchUnit rules and asserts each one FIRES
// on the deliberate violations in `src/archDemo/java/...`. Catches the
// regression where someone weakens a rule and `./gradlew test` silently
// keeps passing.
//
// Intentionally NOT wired into `check` — CI invokes it explicitly:
//     ./gradlew verifyArchUnitFailsOnViolation
val verifyArchUnitFailsOnViolation by tasks.registering(Test::class) {
    group = "verification"
    description = "Asserts ArchUnit detects the deliberate layering violations under src/archDemo/java (ADR 0023 regression guard)."
    testClassesDirs = archDemo.output.classesDirs
    classpath = archDemo.runtimeClasspath
    useJUnitPlatform()
}

// ---------------------------------------------------------------------------
// S-003: OpenAPI snapshot maintenance.
//
//   generateOpenApiSnapshot   refresh alpenflight/web/openapi/openapi.json
//   compareOpenApiSnapshot    fail with non-zero exit if the committed snapshot
//                             diverges from the live spec (run by CI)
//
// The drift gate is enforced by OpenApiSnapshotIT (regular @SpringBootTest)
// rather than wiring compareOpenApiSnapshot into `check` — the IT reuses the
// shared Postgres testcontainer and is skipped cleanly when Docker is absent,
// keeping `./gradlew check` runnable on machines without Docker.
// ---------------------------------------------------------------------------

val openApiSnapshotFile = rootProject.projectDir.resolve("../web/openapi/openapi.json")

val generateOpenApiSnapshot by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Refresh alpenflight/web/openapi/openapi.json from the live springdoc spec."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "ch.alpenflight.platform.openapi.OpenApiSnapshotMain"
    args = listOf("--write", openApiSnapshotFile.absolutePath)
}

val compareOpenApiSnapshot by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Fail if the committed OpenAPI snapshot diverges from the live spec."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "ch.alpenflight.platform.openapi.OpenApiSnapshotMain"
    args = listOf("--compare", openApiSnapshotFile.absolutePath)
}

// J-0c T-03: launchable seam for the fan-out parity Playwright spec
// (alpenflight/web/e2e/tests/real-idp/fan-out-migration-parity.spec.ts). The
// ALPF bundle envelope is built in Java (reusing the server-IT bundle factory),
// so the TS spec shells out to this task to materialize the encrypted fan-out
// bundle bytes; it then POSTs them through the REAL /api/v1/migrations endpoint.
// Pure byte-factory — no Keycloak / backend / DB access (see the class javadoc).
// Test runtime classpath: the seeder + MigrationBundleTestFactory live in
// src/test/java alongside the round-trip ITs they share the bundle shape with.
val seedFanOutParityBundle by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Build the J-0c synthesized fan-out parity bundle (base64) for the e2e spec."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "ch.alpenflight.migrations.web.FanOutParityBundleSeeder"
    // Args supplied by the spec at invocation:
    //   <publicKeyPemPath> <uploadId> <locationName> <clubKeyPrefix> <outputPath>
    // Read from the `-PseederArgs=` project property (space-separated).
    val seederArgs = providers.gradleProperty("seederArgs")
    if (seederArgs.isPresent) {
        args = seederArgs.get().split(" ")
    }
}

// J-1 T-07: launchable seam for the aircraft migration parity Playwright spec
// (alpenflight/web/e2e/tests/real-idp/aircraft-migration-parity.spec.ts). Mirrors
// seedFanOutParityBundle — the ALPF bundle envelope is built in Java (reusing the
// server-IT bundle factory + the AIRCRAFT round-trip bundle shape), so the TS spec
// shells out here to materialize the encrypted aircraft bundle bytes; it then POSTs
// them through the REAL /api/v1/migrations endpoint. Pure byte-factory.
val seedAircraftParityBundle by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Build the J-1 synthesized aircraft migration parity bundle (base64) for the e2e spec."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "ch.alpenflight.migrations.web.AircraftParityBundleSeeder"
    // Args supplied by the spec at invocation:
    //   <publicKeyPemPath> <uploadId> <immatriculation> <clubKey> <outputPath>
    val seederArgs = providers.gradleProperty("seederArgs")
    if (seederArgs.isPresent) {
        args = seederArgs.get().split(" ")
    }
}

// J-2 T-08: launchable seam for the flight migration parity Playwright spec
// (alpenflight/web/e2e/tests/real-idp/flight-migration-parity.spec.ts). Mirrors
// seedAircraftParityBundle — the ALPF bundle envelope is built in Java (reusing the
// server-IT bundle factory + the FLIGHT + FLIGHT_CREW round-trip bundle shape), so
// the TS spec shells out here to materialize the encrypted flight bundle bytes; it
// then POSTs them through the REAL /api/v1/migrations endpoint. Pure byte-factory.
val seedFlightParityBundle by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Build the J-2 synthesized flight migration parity bundle (base64) for the e2e spec."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "ch.alpenflight.migrations.web.FlightParityBundleSeeder"
    // Args supplied by the spec at invocation:
    //   <publicKeyPemPath> <uploadId> <freshnessToken> <clubKey> <outputPath>
    val seederArgs = providers.gradleProperty("seederArgs")
    if (seederArgs.isPresent) {
        args = seederArgs.get().split(" ")
    }
}

// J-1 T-07: DB-fixture seam for the S-163 owner-person edit [edge] case. Sets the
// owner Person + the JWT-sub→Person t_user link + the aircraft's
// aircraft_owner_person_id directly against the live dev Postgres (no UI/REST
// surface sets these). The S-163 access decision still runs fully real off these
// rows — this is fixture state, not a mocked seam. Connects via DATASOURCE_*.
val seedAircraftOwnerLink by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Seed the S-163 owner-person link (person + t_user + aircraft) for the e2e spec."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "ch.alpenflight.migrations.web.AircraftOwnerLinkSeeder"
    // Args supplied by the spec at invocation:
    //   <aircraftId> <ownerKeycloakSub> <ownerClubId> <languageId>
    val seederArgs = providers.gradleProperty("seederArgs")
    if (seederArgs.isPresent) {
        args = seederArgs.get().split(" ")
    }
}

// J-3 T-02: one-command loader for the on-demand SHOWCASE seed — a cumulative,
// deterministic, reusable demo dataset (see src/main/resources/showcase/README.md).
// Runs the app with the `showcase` profile (+ `dev` for the loopback datasource
// defaults) and `exit-after-seed=true`, so the @Profile("showcase")
// ShowcaseSeedRunner fires the idempotent seed once and the process exits 0 —
// no long-running web server. NOT a Flyway V__ migration and never on the IT
// bootstrap path (ADR 0021 keeps ITs lean). Requires a Flyway-migrated Postgres
// reachable via the DATASOURCE_* env (application-dev.yml carries loopback
// defaults). Main runtime classpath (the seeder + runner live in src/main).
val seedShowcase by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Load the on-demand showcase seed (deterministic demo dataset) in one command."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "ch.alpenflight.AlpenFlightApplication"
    args = listOf(
        "--spring.profiles.active=dev,showcase",
        "--alpenflight.showcase.exit-after-seed=true",
        // Bind the web server to an EPHEMERAL port (--server.port=0) so this task
        // never collides with port 8080 when it runs ALONGSIDE the already-running
        // backend in the fan-out's seed-after-gating-specs ordering
        // (alpenflight-proof-fanout.yml step 6b, run 27035833678: "Port 8080 was
        // already in use" → the seed crashed before the runner fired → the J-4
        // /profile capture + its 4 gallery PNGs were skipped).
        //
        // We deliberately KEEP the servlet web context. The seed is driven by
        // ShowcaseSeedRunner (an ApplicationRunner that fires on app-ready, commits
        // the dataset, then System.exit(0)) and never serves a request — but
        // SecurityConfig.defaultFilterChain requires HttpSecurity, which only the
        // servlet web context provides. An earlier fix used
        // --spring.main.web-application-type=none to dodge the port collision; that
        // dropped the servlet context and broke bean wiring
        // (UnsatisfiedDependencyException: no HttpSecurity for 'defaultFilterChain',
        // run 27039051676) before the runner could fire. An ephemeral random port
        // boots Tomcat harmlessly, seeds, and exits 0 — and stays correct in ci.yml's
        // seed-BEFORE-backend ordering too (a throwaway port there is a no-op).
        "--server.port=0",
    )
}
