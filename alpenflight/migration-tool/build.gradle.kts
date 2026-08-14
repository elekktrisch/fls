plugins {
    application
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
    implementation("ch.alpenflight:alpenflight-migration-bundle")

    implementation("info.picocli:picocli:4.7.6")
    annotationProcessor("info.picocli:picocli-codegen:4.7.6")

    implementation("com.microsoft.sqlserver:mssql-jdbc:12.8.1.jre11")

    implementation("org.apache.commons:commons-compress:1.27.1")

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
    mergeServiceFiles()
}

tasks.named("assemble") {
    dependsOn(tasks.shadowJar)
}
