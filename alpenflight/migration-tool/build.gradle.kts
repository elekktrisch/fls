plugins {
    application
    // Fat-jar: bundles migration-bundle + Tink + JDBC + commons-compress +
    // picocli into a single runnable alpenflight-export.jar the operator
    // runs against a legacy FLS MSSQL instance with no classpath wiring.
    id("com.gradleup.shadow") version "9.2.2"
}

group = "ch.alpenflight"
version = "0.0.1-SNAPSHOT"
description = "AlpenFlight standalone legacy-FLS export JAR (S-139): reads a legacy MSSQL instance read-only, emits an ALPF-encrypted migration bundle."

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

application {
    mainClass = "ch.alpenflight.migration.tool.ExportCommand"
}

dependencies {
    // Shared mappers + SELECT registry (MapperLegacyBindings) + ALPF crypto
    // envelope (TinkMigrationBundleCipher, BundleHeader, SecureBytes) +
    // LegacyIdMapWriter. Substituted from the composite build.
    implementation("ch.alpenflight:alpenflight-migration-bundle")

    // picocli @Command CLI; the annotation processor generates the
    // reflection-free GraalVM/picocli metadata at compile time.
    implementation("info.picocli:picocli:4.7.6")
    annotationProcessor("info.picocli:picocli-codegen:4.7.6")

    // Legacy FLS backend is SQL Server. Same driver coordinate the parity
    // harness uses so producer + harness read identical wire types.
    implementation("com.microsoft.sqlserver:mssql-jdbc:12.8.1.jre11")

    // tar(.gz) envelope writer for the bundle plaintext.
    implementation("org.apache.commons:commons-compress:1.27.1")

    // Manifest JSON + per-row NDJSON generation. Pinned to the same version
    // migration-bundle exposes on its Mapper API surface so JsonGenerator /
    // ObjectMapper types are binary-identical across the module boundary.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.4")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName = "alpenflight-export"
    archiveClassifier = ""
    archiveVersion = ""
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
    // Tink ships service-loader META-INF files (KeyManager registration);
    // merge them so the StreamingAead primitive resolves inside the fat-jar.
    mergeServiceFiles()
}

// Make `build` produce the fat-jar so CI's default gate covers it.
tasks.named("assemble") {
    dependsOn(tasks.shadowJar)
}
