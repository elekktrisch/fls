plugins {
    `java-library`
}

group = "ch.alpenflight"
version = "0.0.1-SNAPSHOT"
description = "AlpenFlight legacy-FLS migration bundle: shared mappers + parity oracle harness consumed by the JAR exporter (S-139) and the server-side ingest pipeline (S-141)."

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

val mockitoAgent: Configuration by configurations.creating

// S-187: dedicated parity source set. Carries the Testcontainers MSSQL +
// Postgres harness, the Faker-only LegacyFixtureSeeder, and the diff
// engine. Kept out of `testImplementation` so `./gradlew test` does not
// pull Testcontainers + JDBC drivers + ~200 MB of container images — the
// sub-30-s test budget is the point.
//
// The `parityTest` task below is gated separately (CI nightly + on-demand)
// and is NOT wired into `check`; running the parity oracle requires a
// working Docker daemon, which is not a hard prerequisite for the rest of
// the build. Matches the precedent set by S-188's `src/jmh/java/`.
val parity: SourceSet by sourceSets.creating {
    java.srcDir("src/parity/java")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}

configurations.named("parityImplementation").configure {
    extendsFrom(configurations.implementation.get())
}

configurations.named("parityRuntimeOnly").configure {
    extendsFrom(configurations.runtimeOnly.get())
}

dependencies {
    api("org.jspecify:jspecify:1.0.0")

    // Mapper interface signatures expose Jackson streaming (JsonGenerator) +
    // tree (JsonNode) types. The JDBC ResultSet / PreparedStatement surface
    // ships with the JDK (java.sql.*) — no JDBC dependency required at api.
    api("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.4")
    testImplementation("org.mockito:mockito-core:5.18.0")
    // 1.4+ recognises Java 25 classfile version 69; 1.3.0 silently returned
    // zero classes for the bundle's own bytecode, hollowing out the
    // ArchitectureTest structural rules.
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
    testImplementation("net.datafaker:datafaker:2.4.2")

    // Mockito 5 inline mocking requires the agent to be attached explicitly
    // on Java 21+. Resolved separately so the agent jar path can be passed
    // as -javaagent to the test JVM.
    mockitoAgent("org.mockito:mockito-core:5.18.0") { isTransitive = false }

    // S-187: parity source set. Faker stays in testImplementation for the
    // per-mapper unit suites (subclass legacyRow uses it) and is re-added
    // here so the parity LegacyFixtureSeeder can reach it without
    // depending on testImplementation. Apache Commons Compress emits the
    // in-memory tar.gz envelope without a per-row allocation regression.
    // Flyway is loaded directly so the harness can apply the alpenflight
    // server schema migrations to the Postgres container.
    "parityImplementation"(platform("org.junit:junit-bom:5.11.3"))
    "parityImplementation"("org.junit.jupiter:junit-jupiter")
    "parityRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    "parityImplementation"("org.assertj:assertj-core:3.27.4")
    "parityImplementation"("net.datafaker:datafaker:2.4.2")
    "parityImplementation"("org.apache.commons:commons-compress:1.27.1")
    "parityImplementation"(platform("org.testcontainers:testcontainers-bom:1.20.4"))
    "parityImplementation"("org.testcontainers:junit-jupiter")
    "parityImplementation"("org.testcontainers:mssqlserver")
    "parityImplementation"("org.testcontainers:postgresql")
    "parityImplementation"("org.flywaydb:flyway-core:11.14.1")
    "parityImplementation"("org.flywaydb:flyway-database-postgresql:11.14.1")
    "parityRuntimeOnly"("org.postgresql:postgresql:42.7.4")
    "parityRuntimeOnly"("com.microsoft.sqlserver:mssql-jdbc:12.8.1.jre11")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-javaagent:${mockitoAgent.asPath}")
}

// S-187: the parity oracle harness — separate task from `test`, separate
// source set, separate dependency graph. Run on demand:
//
//     ./gradlew parityTest -Dparity.seed=42 -Dparity.scale=1
//
// CI invokes this with `-Dparity.scale=10` nightly on `main`. Not wired
// into `check` — Docker is not a hard prerequisite for the rest of the
// build, and the harness's per-class container start tax (~30-60 s MSSQL
// + ~5-10 s Postgres) is incompatible with the `./gradlew test` budget.
val parityTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the migration-bundle parity oracle (MSSQL → mapper round-trip → Postgres → diff)."
    testClassesDirs = parity.output.classesDirs
    classpath = parity.runtimeClasspath
    useJUnitPlatform {
        // `parity-meta` (mutation-smoke) and `parity-reject` (negative-path
        // bundle-reject cases) land at S-187a. Excluding them now keeps the
        // happy-path summary.json from colouring red on a tag mismatch.
        excludeTags("parity-meta", "parity-reject")
    }
    systemProperty("parity.seed", System.getProperty("parity.seed", "42"))
    systemProperty("parity.scale", System.getProperty("parity.scale", "1"))
}
