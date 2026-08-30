plugins {
    `java-library`
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

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
    }
}

val gateFixtureViolateStandaloneBuild =
    (project.findProperty("gateFixtureViolateStandaloneBuild") as String?)?.toBoolean() ?: false

dependencies {
    api(project(":server:core"))
    testImplementation(libs.spring.boot.starter.test)
    if (gateFixtureViolateStandaloneBuild) {
        implementation(project(":server:modules-pro"))
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

val verifyStandaloneBuildGate =
    tasks.register("verifyStandaloneBuildGate") {
        group = "verification"
        description = "Gate 3 (standalone build): proves :server:modules-open's resolved runtime classpath " +
            "excludes :server:modules-pro unless -PgateFixtureViolateStandaloneBuild=true deliberately violates it."

        val runtimeClasspath = configurations.getByName("runtimeClasspath")
        inputs.property("gateFixtureViolateStandaloneBuild", gateFixtureViolateStandaloneBuild)

        doLast {
            val resolvedProjectPaths = runtimeClasspath.incoming.resolutionResult.allComponents
                .mapNotNull { it.id as? org.gradle.api.artifacts.component.ProjectComponentIdentifier }
                .map { it.projectPath }
                .toSet()
            val includesModulesPro = ":server:modules-pro" in resolvedProjectPaths

            if (gateFixtureViolateStandaloneBuild && !includesModulesPro) {
                throw GradleException(
                    "Gate 3 (standalone build) fixture failed to trigger: " +
                        "-PgateFixtureViolateStandaloneBuild=true did not add :server:modules-pro to " +
                        ":server:modules-open's resolved runtime classpath."
                )
            }
            if (!gateFixtureViolateStandaloneBuild && includesModulesPro) {
                throw GradleException(
                    "Gate 3 (standalone build) violated: :server:modules-open's resolved runtime classpath " +
                        "includes :server:modules-pro with no property set."
                )
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyStandaloneBuildGate)
}
