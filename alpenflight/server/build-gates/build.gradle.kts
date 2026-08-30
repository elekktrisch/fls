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

dependencies {
    testImplementation(project(":server:platform"))
    testImplementation(project(":server:core"))
    testImplementation(project(":server:modules-open"))
    testImplementation(project(":server:modules-pro"))
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
