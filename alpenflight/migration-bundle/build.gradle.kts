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

    api("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    compileOnly("org.springframework.modulith:spring-modulith-api:2.0.4")

    implementation("com.google.crypto.tink:tink:1.18.0") {
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    }

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.4")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
    testImplementation("net.datafaker:datafaker:2.4.2")

    mockitoAgent("org.mockito:mockito-core:5.18.0") { isTransitive = false }

    "parityImplementation"(platform("org.junit:junit-bom:5.11.3"))
    "parityImplementation"("org.junit.jupiter:junit-jupiter")
    "parityRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    "parityImplementation"("org.assertj:assertj-core:3.27.4")
    "parityImplementation"("net.datafaker:datafaker:2.4.2")
    "parityImplementation"("org.apache.commons:commons-compress:1.27.1")
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

tasks.check {
    dependsOn(parity.classesTaskName)
}

val parityTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the migration-bundle parity oracle (MSSQL → mapper round-trip → Postgres → diff)."
    testClassesDirs = parity.output.classesDirs
    classpath = parity.runtimeClasspath
    useJUnitPlatform {
        excludeTags("parity-meta", "parity-reject")
    }
    systemProperty("parity.seed", System.getProperty("parity.seed", "42"))
    systemProperty("parity.scale", System.getProperty("parity.scale", "1"))
}

val parityRejectTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the parity negative-path bundle-reject cases (@Tag parity-reject)."
    testClassesDirs = parity.output.classesDirs
    classpath = parity.runtimeClasspath
    useJUnitPlatform {
        includeTags("parity-reject")
    }
    systemProperty("parity.seed", System.getProperty("parity.seed", "42"))
    systemProperty("parity.scale", System.getProperty("parity.scale", "1"))
}

val parityMetaTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the parity harness self-test / mutation smoke (@Tag parity-meta)."
    testClassesDirs = parity.output.classesDirs
    classpath = parity.runtimeClasspath
    useJUnitPlatform {
        includeTags("parity-meta")
    }
    systemProperty("parity.seed", System.getProperty("parity.seed", "42"))
    systemProperty("parity.scale", System.getProperty("parity.scale", "1"))
}
