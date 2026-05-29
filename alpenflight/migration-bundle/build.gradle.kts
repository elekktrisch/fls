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
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-javaagent:${mockitoAgent.asPath}")
}
