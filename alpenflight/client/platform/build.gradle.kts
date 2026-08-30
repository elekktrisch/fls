import com.github.gradle.node.npm.task.NpmTask
import java.io.File

fun jdkBinDirectory(): String =
    File(System.getenv("JAVA_HOME") ?: System.getProperty("java.home"), "bin").absolutePath

fun pathWithJdkBinFirst(): String =
    listOf(jdkBinDirectory(), System.getenv("PATH") ?: "").joinToString(File.pathSeparator)

plugins {
    base
    alias(libs.plugins.node.gradle)
}

node {
    version.set("22.23.2")
    download.set(true)
    nodeProjectDir.set(file("$projectDir/.."))
}

tasks.register<NpmTask>("generateApiClient") {
    group = "build"
    description = "Generates client/platform/src/generated/openapi from server/platform's published OpenAPI spec."
    dependsOn(tasks.named("npmInstall"), ":server:platform:exportOpenApiSpec")
    npmCommand.set(listOf("run", "generate:api", "--workspace=platform"))
    environment.put("PATH", pathWithJdkBinFirst())
    inputs.file(rootProject.file("server/platform/build/openapi/openapi.json"))
    outputs.dir(file("src/generated/openapi"))
}

tasks.register<NpmTask>("ngBuild") {
    group = "build"
    description = "Builds the Angular application (ng build), including the client/features placeholder component."
    dependsOn("generateApiClient")
    npmCommand.set(listOf("run", "build", "--workspace=platform"))
    inputs.dir(file("src"))
    inputs.dir(rootProject.file("client/features"))
    outputs.dir(file("dist/platform"))
}

tasks.register<NpmTask>("ngTest") {
    group = "verification"
    description = "Runs the Angular unit test suite (ng test --watch=false)."
    dependsOn("generateApiClient")
    npmCommand.set(listOf("run", "test", "--workspace=platform", "--", "--watch=false"))
    inputs.dir(file("src"))
    inputs.dir(rootProject.file("client/features"))
}

tasks.named("assemble") {
    dependsOn("ngBuild")
}

tasks.named("check") {
    dependsOn("ngTest")
}
