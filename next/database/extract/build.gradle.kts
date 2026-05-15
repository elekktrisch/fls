plugins {
    java
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "ch.fls.legacyextract"
version = "0.0.1-SNAPSHOT"
description = "FLS legacy SQL Server metadata extractor"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // CLI-only — no web, no JPA. JDBC starter for SQL Server access.
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("com.microsoft.sqlserver:mssql-jdbc:12.10.0.jre11")
    implementation("com.fasterxml.jackson.module:jackson-module-parameter-names")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // No Testcontainers — the bundled docker-java hardcodes Docker REST API
    // 1.32 and our host daemon enforces a minimum of 1.44. The pragmatic
    // fix is to drive the container lifecycle through `docker` CLI directly
    // (the CLI auto-negotiates the latest version), which is what
    // MssqlTestContainerLifecycle does. JUnit-only test infra.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

configurations.matching {
    it.name == "runtimeClasspath" || it.name == "compileClasspath"
}.configureEach {
    resolutionStrategy.failOnVersionConflict()
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
    // Container lifecycle is driven by docker CLI in
    // MssqlTestContainerLifecycle; the test JVM doesn't need any docker env
    // beyond inheriting DOCKER_HOST.
}
