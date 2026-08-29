plugins {
    base
}

tasks.register<Exec>("dockerImage") {
    group = "build"
    description = "Builds the one AlpenFlight container image (API + built client) from deploy/Dockerfile."
    workingDir = rootDir
    commandLine("docker", "build", "-f", "deploy/Dockerfile", "-t", "alpenflight:local", ".")
}

tasks.named("build") {
    dependsOn("dockerImage")
}
