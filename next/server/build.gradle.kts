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

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // S-048 adds JPA on top of the JDBC starter (the JDBC dep stays so Flyway
    // keeps working with its lightweight DataSource). Hibernate ships under
    // the JPA umbrella.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // S-048 walking-skeleton wires real @PreAuthorize predicates against a
    // mocked principal (profile `mock-auth`). The starter activates Spring
    // Security's default chain even without `mock-auth` — see SecurityConfig
    // for the no-auth-yet baseline that still permits OpenAPI / actuator /
    // hello so the existing ITs stay green.
    implementation("org.springframework.boot:spring-boot-starter-security")
    // S-048: the resource-server + jose modules bring `Jwt`,
    // `JwtAuthenticationToken`, and `JwtAuthenticationConverter` — the
    // converter shape ClubAwareJwtAuthenticationConverter wraps so the
    // S-020 swap is a one-line change. Auto-configuration is dormant unless
    // `spring.security.oauth2.resourceserver.*` is set, so adding these
    // jars does NOT enable a real JWT decoder at runtime.
    implementation("org.springframework.security:spring-security-oauth2-resource-server")
    implementation("org.springframework.security:spring-security-oauth2-jose")
    // S-022: application-side UUID v7 generation per ADR 0019. Wired by
    // `FlsUuidV7Generator` (Hibernate `BeforeExecutionGenerator`) and the
    // `@UuidV7` meta-annotation. Time-ordered keys give B-tree-friendly
    // inserts which matters for S-028's bulk cutover; gen_random_uuid()
    // default values on PKs are forbidden by `forbidden-migration-patterns.txt`.
    implementation("com.github.f4b6a3:uuid-creator:6.0.0")
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
    // passes). HelloControllerIT uses @WebMvcTest (slice; no DataSource).
    // Boot 4.0 split: TestRestTemplate (in spring-boot-resttestclient) depends
    // on RestTemplateBuilder which lives in spring-boot-restclient.
    testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
    // S-048: spring-security-test provides @WithMockUser + MockMvc
    // `.with(jwt())` post-processors so role-gate ITs can downgrade the
    // principal without booting the mock-auth filter chain.
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// ---------------------------------------------------------------------------
// S-003: OpenAPI snapshot maintenance.
//
//   generateOpenApiSnapshot   refresh next/web/openapi/openapi.json
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
    description = "Refresh next/web/openapi/openapi.json from the live springdoc spec."
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
