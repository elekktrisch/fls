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

// J-0b T-10: composite-include the migration-tool so the real-producer
// round-trip IT (LocationRealProducerRoundTripIT) can drive the actual
// BundleWriter.assembleTarGz tar-entry ordering through the real server ingest
// — guarding the pgcopy-before-NDJSON order against silent regression.
// TEST-only (testImplementation below); no production dependency on the export
// JAR. migration-tool also includeBuilds migration-bundle; Gradle dedupes
// included builds by path.
includeBuild("../migration-tool")
