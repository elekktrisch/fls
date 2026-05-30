plugins {
    // Auto-provision JDK 25 via the Foojay discovery API (https://api.foojay.io)
    // when the local machine doesn't have a matching JDK installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "alpenflight-server"

// S-187: composite-include the migration-bundle so MapperVsSchemaCompatibilityTest
// can reach the 28 concrete mapper classes + the Mapper interface + the
// EntityType enum. Bundle stays its own Gradle root project (separate
// settings.gradle.kts) — substitution kicks in at `ch.alpenflight:migration-bundle`.
includeBuild("../migration-bundle")
