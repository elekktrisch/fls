rootProject.name = "alpenflight"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "server:platform",
    "server:core",
    "server:modules-open",
    "server:modules-pro",
    "server:build-gates",
    "client:platform",
    "client:features",
)
