<!-- bmad:context -->
<!-- Verified 2026-08-29 against a5ea53eee24f0a7066118fde7497539d8d074019. Managed by bmad-project-context; edits inside this block are replaced on refresh. Keep anything you want preserved outside the markers. -->

## alpenflight

AlpenFlight rebuild 2: a modular monolith of vertical slices. `server/` is Java 25 / Spring Boot
4.1.1 (Gradle, Kotlin DSL). `client/` is Angular 22.0.1, zoneless, esbuild-based `@angular/build`.
The binding architecture is
`_bmad-output/planning-artifacts/architecture/architecture-fls-2026-08-29/ARCHITECTURE-SPINE.md`;
repo-wide policy (legacy read-only, ASD-STE100, naming discipline) is the root `CLAUDE.md`.

## Where things are

- Build and run the whole tree: `alpenflight/README.md`.
- Every pinned tool version: `gradle/libs.versions.toml`, the single source of truth. The
  release-note evidence for a pin lives in the commit message that introduced it, not in a
  tracked file.
- The OpenAPI codegen pipeline: `server/platform/build.gradle.kts` (task `exportOpenApiSpec`)
  feeds `client/platform/build.gradle.kts` (task `generateApiClient`).

## Running and verifying

- `./gradlew build` needs a running Docker daemon: the root project's `build` task also builds the
  container image (task `dockerImage`, `deploy/Dockerfile`), so the whole command fails without
  Docker even though most subprojects do not need it.
- A cold `./gradlew build` across every module takes on the order of 15 minutes (Node, Gradle, and
  the OpenAPI Generator jar all download on first run); a cold `docker build -f deploy/Dockerfile .`
  takes longer still, because it repeats the server and client builds inside clean stages on
  purpose. Neither hang; let them finish.

## Known pitfalls

- Spring Boot 4.1.1's test classpath does not carry `TestRestTemplate` the way older Spring Boot
  guides assume. Use the JDK `HttpClient` (`server/platform/src/test/java/ch/alpenflight/platform/PlatformApplicationTests.java`)
  or `RestTestClient`, never assume `TestRestTemplate` compiles.
- Spring Boot 4.1.1's own tutorial starter is `spring-boot-starter-webmvc`, not the older
  `spring-boot-starter-web`. Both resolve; use `-webmvc` to match current Spring Boot 4 convention.
- `openapi-generator-cli` (used by `npm run generate:api`) shells out to a Java jar even though the
  rest of `client/platform` is pure Node. A plain Node image or machine needs a JRE on `PATH` too,
  or the command fails with `java: not found` (`deploy/Dockerfile`'s `client` stage installs one).

<!-- /bmad:context -->
