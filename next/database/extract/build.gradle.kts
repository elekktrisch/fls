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

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers:1.21.3")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
    testImplementation("org.testcontainers:mssqlserver:1.21.3")
    // Override docker-java to a version that negotiates Docker API 1.44+
    // (host daemon enforces 1.44 minimum on the sandbox). Testcontainers
    // 1.21.3 pins docker-java 3.4.2 which reports API 1.32 — too old.
    testImplementation("com.github.docker-java:docker-java-api:3.5.1")
    testImplementation("com.github.docker-java:docker-java-transport-zerodep:3.5.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

configurations.matching {
    it.name == "runtimeClasspath" || it.name == "compileClasspath"
}.configureEach {
    resolutionStrategy.failOnVersionConflict()
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Testcontainers integration tests are slow; tag them and let the default
    // `test` run them all. Operators can subset with `--tests` if needed.
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
