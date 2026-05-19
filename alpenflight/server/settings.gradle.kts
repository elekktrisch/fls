plugins {
    // Auto-provision JDK 25 via the Foojay discovery API (https://api.foojay.io)
    // when the local machine doesn't have a matching JDK installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "alpenflight-server"
