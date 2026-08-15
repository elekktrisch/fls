plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "alpenflight-server"

includeBuild("../migration-bundle")

includeBuild("../migration-tool")
