plugins {
    base
}

tasks.named("assemble") {
    dependsOn(":client:platform:assemble")
}

tasks.named("check") {
    dependsOn(":client:platform:check")
}
