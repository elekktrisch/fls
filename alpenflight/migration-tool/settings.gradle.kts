plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "alpenflight-migration-tool"

// Composite build: the standalone export JAR reuses the shared mappers,
// the SELECT registry (MapperLegacyBindings), and the ALPF crypto envelope
// from migration-bundle. Substituted in-place so a registry change (e.g.
// S-187a adding bindings) is picked up without a publish step.
includeBuild("../migration-bundle")
