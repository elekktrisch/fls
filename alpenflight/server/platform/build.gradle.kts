import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit

plugins {
    `java-library`
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "ch.alpenflight"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.spring.boot.starter.webmvc)
    api(libs.spring.boot.starter.data.jpa)
    implementation(libs.springdoc.openapi.starter.webmvc.ui)
    implementation(libs.spring.boot.flyway)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

springBoot {
    buildInfo()
}

fun startPlatformAppOnPort(jarFile: java.io.File, port: Int): Process =
    ProcessBuilder(
        "java",
        "-jar", jarFile.absolutePath,
        "--server.port=$port",
        "--spring.main.banner-mode=off",
        "--spring.main.lazy-initialization=false",
        "--spring.autoconfigure.exclude=" +
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration," +
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration," +
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
    ).redirectErrorStream(true).start()

fun pumpProcessOutputUntilClosed(process: Process, into: StringBuilder): Thread =
    Thread {
        try {
            process.inputStream.bufferedReader().forEachLine { into.appendLine(it) }
        } catch (_: java.io.IOException) {
        }
    }.apply { isDaemon = true; start() }

fun pollForOpenApiSpecJson(port: Int, deadlineMillis: Long): String? {
    while (System.currentTimeMillis() < deadlineMillis) {
        try {
            val connection = URI("http://localhost:$port/v3/api-docs").toURL()
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 1000
            connection.readTimeout = 2000
            if (connection.responseCode == 200) {
                return connection.inputStream.bufferedReader().readText()
            }
            connection.disconnect()
        } catch (_: Exception) {
            Thread.sleep(500)
        }
    }
    return null
}

fun stopProcessAndWaitForPump(process: Process, pump: Thread) {
    process.destroy()
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly()
    }
    pump.join(2000)
}

tasks.register("exportOpenApiSpec") {
    group = "documentation"
    description = "Starts the platform app briefly and captures its published OpenAPI spec to build/openapi/openapi.json."
    dependsOn(tasks.named("bootJar"))

    val jarFile = tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar").flatMap { it.archiveFile }
    val outputFile = layout.buildDirectory.file("openapi/openapi.json")
    inputs.file(jarFile)
    outputs.file(outputFile)

    doLast {
        val port = (20000..29999).random()
        val process = startPlatformAppOnPort(jarFile.get().asFile, port)
        val processOutput = StringBuilder()
        val pump = pumpProcessOutputUntilClosed(process, processOutput)

        try {
            val specJson = pollForOpenApiSpecJson(port, System.currentTimeMillis() + 90_000)
                ?: throw GradleException(
                    "server/platform did not publish its OpenAPI spec within 90s.\n--- app output ---\n$processOutput"
                )
            val target = outputFile.get().asFile
            target.parentFile.mkdirs()
            target.writeText(specJson)
            logger.lifecycle("Wrote OpenAPI spec to ${target.absolutePath}")
        } finally {
            stopProcessAndWaitForPump(process, pump)
        }
    }
}
