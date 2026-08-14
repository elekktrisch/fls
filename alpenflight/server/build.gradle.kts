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
    id("org.flywaydb.flyway") version "11.14.1"
    pmd
    id("de.aaschmid.cpd") version "3.5"
}

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

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:2.0.5")
    }
}

val nullawayDemo: SourceSet by sourceSets.creating {
    java.srcDir("src/nullawayDemo/java")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations.named("nullawayDemoImplementation").configure {
    extendsFrom(configurations.implementation.get())
}

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
    implementation("org.springframework:spring-aop")
    implementation("org.aspectj:aspectjweaver")
    implementation("net.javacrumbs.shedlock:shedlock-spring:7.7.0")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.7.0")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.security:spring-security-oauth2-resource-server")
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("com.github.f4b6a3:uuid-creator:6.0.0")
    implementation("com.github.ben-manes.caffeine:caffeine") {
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    }
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
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
    testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
    testImplementation("org.springframework.security:spring-security-test")
    compileOnly("org.springframework.modulith:spring-modulith-api")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    implementation("ch.alpenflight:alpenflight-migration-bundle")
    compileOnly("org.postgresql:postgresql")
    implementation("org.apache.commons:commons-compress:1.27.1")

    implementation("org.apache.poi:poi-ooxml:5.5.1")
    implementation("commons-io:commons-io:2.21.0")

    testImplementation("ch.alpenflight:alpenflight-migration-tool")
}

nullaway {
    onlyNullMarked = true
}

tasks.withType<JavaCompile>().configureEach {
    options.errorprone.nullaway {
        severity = CheckSeverity.ERROR
    }
}

tasks.named<JavaCompile>("compileTestJava") {
    options.errorprone.nullaway {
        severity = CheckSeverity.OFF
    }
}

tasks.named<JavaCompile>("compileArchDemoJava") {
    options.errorprone.nullaway {
        severity = CheckSeverity.OFF
    }
}

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

configurations.matching {
    it.name == "runtimeClasspath" || it.name == "compileClasspath"
}.configureEach {
    resolutionStrategy.failOnVersionConflict()
}


pmd {
    toolVersion = "7.25.0"
    isConsoleOutput = false
    ruleSetConfig = resources.text.fromFile("config/pmd/ruleset.xml")
    ruleSets = emptyList()
    isIgnoreFailures = true
}

tasks.withType<org.gradle.api.plugins.quality.Pmd>().configureEach {
    enabled = name == "pmdMain"
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.named("check") {
    setDependsOn(
        dependsOn.filterNot { dep ->
            dep.javaClass.name.startsWith("org.gradle.api.plugins.quality")
        },
    )
    dependsOn("pmdMain")
}

cpd {
    toolVersion = "7.7.0"
    language = "java"
    minimumTokenCount = 50
    isIgnoreFailures = true
    isIgnoreLiterals = false
    isIgnoreIdentifiers = false
}

tasks.named<Cpd>("cpdCheck") {
    source = sourceSets.main.get().allJava
    reports {
        xml.required = true
        text.required = false
    }
}

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
                Long.MAX_VALUE
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

tasks.named("check") {
    dependsOn("pmdMain", cpdRatchet)
}

flyway {
    url = System.getenv("MIGRATOR_DB_URL")
        ?: System.getenv("DATASOURCE_URL")
        ?: "jdbc:postgresql://localhost:5432/alpenflight"
    user = System.getenv("MIGRATOR_DB_USER")
        ?: System.getenv("DATASOURCE_USER")
        ?: "alpenflight"
    password = System.getenv("MIGRATOR_DB_PASSWORD")
        ?: System.getenv("DATASOURCE_PASSWORD")
        ?: "alpenflight"
    locations = arrayOf("filesystem:src/main/resources/db/migration")
    outOfOrder = false
    cleanDisabled = true
    baselineOnMigrate = false
    validateMigrationNaming = true
    placeholders = mapOf(
        "alpenflight.operator.keycloak_sub"
            to (System.getenv("ALPENFLIGHT_OPERATOR_KEYCLOAK_SUB")
                ?: "00000000-0000-0000-0000-0000000000ff"),
        "app_role_password"
            to (System.getenv("APP_DB_PASSWORD") ?: "alpenflight_app"),
    )
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("failed")
        showExceptions = true
        showCauses = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    maxParallelForks = (System.getenv("ALPENFLIGHT_TEST_FORKS")?.toIntOrNull() ?: 2).coerceAtLeast(1)
    maxHeapSize = "1g"
}

val verifyArchUnitFailsOnViolation by tasks.registering(Test::class) {
    group = "verification"
    description = "Asserts ArchUnit detects the deliberate layering violations under src/archDemo/java (ADR 0023 regression guard)."
    testClassesDirs = archDemo.output.classesDirs
    classpath = archDemo.runtimeClasspath
    useJUnitPlatform()
}


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

val seedFanOutParityBundle by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Build the J-0c synthesized fan-out parity bundle (base64) for the e2e spec."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "ch.alpenflight.migrations.web.FanOutParityBundleSeeder"
    val seederArgs = providers.gradleProperty("seederArgs")
    if (seederArgs.isPresent) {
        args = seederArgs.get().split(" ")
    }
}

val seedAircraftParityBundle by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Build the J-1 synthesized aircraft migration parity bundle (base64) for the e2e spec."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "ch.alpenflight.migrations.web.AircraftParityBundleSeeder"
    val seederArgs = providers.gradleProperty("seederArgs")
    if (seederArgs.isPresent) {
        args = seederArgs.get().split(" ")
    }
}

val seedFlightParityBundle by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Build the J-2 synthesized flight migration parity bundle (base64) for the e2e spec."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "ch.alpenflight.migrations.web.FlightParityBundleSeeder"
    val seederArgs = providers.gradleProperty("seederArgs")
    if (seederArgs.isPresent) {
        args = seederArgs.get().split(" ")
    }
}

val seedAircraftOwnerLink by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Seed the S-163 owner-person link (person + t_user + aircraft) for the e2e spec."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "ch.alpenflight.migrations.web.AircraftOwnerLinkSeeder"
    val seederArgs = providers.gradleProperty("seederArgs")
    if (seederArgs.isPresent) {
        args = seederArgs.get().split(" ")
    }
}

val seedDelivery by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Seed a clean-seed Delivery (+items +article) for the e2e deliveries spec."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "ch.alpenflight.migrations.web.DeliverySeeder"
    val seederArgs = providers.gradleProperty("seederArgs")
    if (seederArgs.isPresent) {
        args = seederArgs.get().split(" ")
    }
}

val seedDeliveryWriteFixture by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Seed the write-side input states (locked/aged flight, tow link, rule filter, " +
        "pre-built + cross-tenant deliveries) for the e2e deliveries-write spec."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "ch.alpenflight.migrations.web.DeliveryWriteFixtureSeeder"
    val seederArgs = providers.gradleProperty("seederArgs")
    if (seederArgs.isPresent) {
        args = seederArgs.get().split(" ")
    }
}

val generateFlightReportGoldenFixture by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Regenerate the FlightReports Excel golden-parity fixture (S-096)."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "ch.alpenflight.flights.web.FlightReportGoldenFixtureGenerator"
    args = listOf("src/test/resources/excel-parity/flight-reports-legacy-golden.xlsx")
}

val seedShowcase by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Load the on-demand showcase seed (deterministic demo dataset) in one command."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "ch.alpenflight.AlpenFlightApplication"
    args = listOf(
        "--spring.profiles.active=dev,showcase",
        "--alpenflight.showcase.exit-after-seed=true",
        "--server.port=0",
    )
}
