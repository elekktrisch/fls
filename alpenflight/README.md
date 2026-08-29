# AlpenFlight

This is the rebuild. Read
[`ARCHITECTURE-SPINE.md`](../_bmad-output/planning-artifacts/architecture/architecture-fls-2026-08-29/ARCHITECTURE-SPINE.md)
first. It binds every module in this tree.

## The build command

Run these two commands from this directory, in order.

```sh
./gradlew build
docker build -f deploy/Dockerfile -t alpenflight:local .
```

The first command builds and tests the seven modules: `server/platform`, `server/core`,
`server/modules-open`, `server/modules-pro`, `client/platform`, `client/features`, and this root
project. It also runs the second command as its own last step (task `dockerImage`), so
`./gradlew build` alone produces the image too. The Dockerfile builds a second, independent copy
of the jar and the client inside clean stages, so the image never depends on files the first
command left on disk.

Docker Desktop (or another Docker daemon) must run for either command to finish.

The first build on a machine is slow: Gradle, Node, and the OpenAPI Generator jar all download.
Every later `./gradlew build` is fast once Gradle's cache and Docker's build cache are warm.
`./gradlew build -x dockerImage` skips the image step; use it as the fast inner-dev-loop command
while iterating on server or client code. It does not replace `./gradlew build` as the documented,
image-producing command this story's acceptance criteria require -- it is an additional shortcut.

`deploy/Dockerfile` builds in four stages: `spec` starts `server/platform` on its own and captures
the OpenAPI spec it publishes; `client` generates TypeScript from that spec and builds the Angular
app; `server` copies the built Angular output into Spring Boot's static resources and packages the
jar; `runtime` ships only that jar. Every `RUN` in these stages names a project-qualified Gradle or
npm task, never the aggregate `build`/`dockerImage` task, so `docker build` never triggers another
`docker build` of itself. The `client` stage installs a headless JRE alongside Node, because
`openapi-generator-cli` runs its generator as a jar even on a pure-Node image.

## Run the image

```sh
docker run --rm -p 8080:8080 alpenflight:local
```

Then open `http://localhost:8080`. The page loads the built Angular client. The client calls
`/api/v1/system/status`, and the one Spring Boot process answers both.

## Generate the client's API types by hand

`client/platform`'s build already does this as part of `./gradlew build`. To run it alone, after
`server/platform` has published its spec at least once (`./gradlew :server:platform:exportOpenApiSpec`):

```sh
npm install --prefix client
npm run generate:api --workspace=platform --prefix client
```

## What this story does not wire up yet

- No database, no first migration, no id strategy -- story 1.2.
- No CI workflow -- story 1.3.
- No application shell, no design tokens -- story 1.4.
- Every module under `server/core`, `server/modules-open`, `server/modules-pro` is an empty
  placeholder: build wiring only, no slice, no business rule.
