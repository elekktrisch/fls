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
}

group = "ch.fls"
version = "0.0.1-SNAPSHOT"
description = "FLS server"

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
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    // On the classpath so S-003 can switch springdoc on without a dep change.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17")
    implementation("org.jspecify:jspecify:1.0.0")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    errorprone("com.google.errorprone:error_prone_core:2.49.0")
    errorprone("com.uber.nullaway:nullaway:0.13.4")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    // Boot 4.0 split: TestRestTemplate (in spring-boot-resttestclient) depends
    // on RestTemplateBuilder which lives in spring-boot-restclient.
    testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

nullaway {
    // ch.fls is treated as @NullMarked; NullAway enforces non-null defaults
    // for everything under it. Spring framework internals stay un-checked.
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

tasks.withType<Test> {
    useJUnitPlatform()
}
