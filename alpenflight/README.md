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

## Start the whole system

```sh
docker compose up --build
```

Run this from `deploy/`. It starts PostgreSQL, Keycloak, and the app image together, on one
network. The first migration creates the `club` table and seeds its one row; the app connects as a
non-owner database role that row-level security still restricts. Every table's primary key is a
UUID v7, minted by PostgreSQL's native `uuidv7()` column default and read back through Hibernate.

## CI

`.github/workflows/ci.yml` runs on pushes to `main` and on every pull request, four jobs, none
needing a secret:

- **Build** -- `./gradlew build` (from `alpenflight/`). Runs every module's unit tests, the client,
  and the image, plus two build gate mechanisms:
  - `server/build-gates` proves gates 1 (slice shape), 2 (core never references pro), 5 (no binary
    float in a money type), 6 (no cross-slice reach), 8 (the `Clock` is injected), and 9 (every
    mutable entity carries a version column). Each gate is one ArchUnit-backed rule plus a
    checked-in fixture that deliberately violates it, proving the gate still catches a regression.
  - `server/modules-open/build.gradle.kts`'s `verifyStandaloneBuildGate` task proves gate 3
    (standalone build): it is Gradle-level, not a compiled-class scan, and runs as part of
    `:server:modules-open:check` on every build, asserting `:server:modules-open`'s resolved
    runtime classpath carries no `:server:modules-pro` reference.
- **Tenant isolation integration test** -- `./gradlew :server:core:integrationTest` (from
  `alpenflight/`). Docker-backed (Testcontainers PostgreSQL); proves gate 7, a cross-tenant query
  without a filter returns zero rows. This is `ClubTenantIsolationIT`, unchanged from story 1.2.
- **Standalone build** -- `./gradlew :server:platform:build :server:core:build
  :server:modules-open:build` (from `alpenflight/`), the `gateFixtureViolateStandaloneBuild`
  property left off. Proves `:server:platform`, `:server:core`, and `:server:modules-open` build
  and test with no `:server:modules-pro` task in the graph.
- **Standalone build gate fixture self-test** -- `./gradlew
  :server:modules-open:verifyStandaloneBuildGate -PgateFixtureViolateStandaloneBuild=true` (from
  `alpenflight/`). The automated proof that gate 3's detection logic still works: with the property
  on, the task must find `:server:modules-pro` on `:server:modules-open`'s resolved runtime
  classpath, or it fails naming the gate. This is the same self-testing-fixture pattern every other
  gate already has, run as its own CI job so a weakened gate 3 is caught on every commit, not only
  when someone remembers to run the command by hand.

Run the fixture-on task directly, not through `:server:modules-open:build`. `:server:modules-pro`
already depends on `:server:modules-open` (the module tiers), so the fixture's reverse dependency
makes the two projects depend on each other; a task that actually compiles or packages
`:server:modules-open` (`build`, `assemble`, `jar`, `compileJava`) hits that real Gradle circular
dependency and fails before any gate code runs -- itself a valid, even blunter proof that the
standalone build cannot happen with the reference in place. `verifyStandaloneBuildGate` alone reads
only the dependency graph's metadata, not its build outputs, so it runs cleanly either way and
states the gate by name.

Gate 4 (every table declares its data kind, and the row-level-security check that goes with it)
ships separately; see `_bmad-output/implementation-artifacts/deferred-work.md`.

## What this story does not wire up yet

- No application shell, no design tokens -- story 1.4.
- Every module under `server/core`, `server/modules-open`, `server/modules-pro` is an empty
  placeholder: build wiring only, no slice, no business rule, except the `Club` entity story 1.2
  adds to prove the id strategy.
- No sign-in, no realm content beyond the import proof -- story 1.7.
