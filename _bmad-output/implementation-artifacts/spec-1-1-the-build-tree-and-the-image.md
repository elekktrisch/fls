---
title: 'Story 1.1: The build tree and the image'
type: 'feature'
created: '2026-08-29'
status: 'done'
review_loop_iteration: 0
context: ['{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md']
baseline_commit: 'a5ea53eee24f0a7066118fde7497539d8d074019'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `alpenflight/` is empty. No later story has a proven tree, a pinned tool set, or a
container image to start from.

**Approach:** Build the Gradle multi-module tree and the Angular workspace that match the
architecture spine's Structural Seed, pin the five named tool versions against verified release
notes, generate the client's TypeScript types from the server's published OpenAPI spec, produce
one container image that serves the API and the built client, and run `bmad-project-context`
against the new tree to write `AGENTS.md`.

## Boundaries & Constraints

**Always:** Match the Structural Seed tree exactly: `server/platform`, `server/core`,
`server/modules-open`, `server/modules-pro`, `client/platform`, `client/features`, `deploy/`.
Every server module stays an empty placeholder with build wiring only — no slice, no business
rule; that starts in story 1.6 onward. Pin Gradle, Flyway, the OpenAPI generator, the IndexedDB
wrapper, and Keycloak to a concrete version each, cited from a release note fetched during this
story, never recalled from memory. The client generates its TypeScript types from the server's
published OpenAPI spec, and at least one client component uses a generated type as its own type.
One Dockerfile produces one image that serves both the API and the built client. No comment in
implementation code — a name and a structure carry the intent instead, so the code stays the one
source of truth (exception: a `package-info.java` file's Javadoc). No markdown file in this story
restates a pinned version number — a framework or a tool is named, never its version; the version
lives once, in the source config that consumes it (`libs.versions.toml`, `package.json`), and the
"verified against a release note" evidence goes in the commit message, not a tracked file.

**Ask First:** Whether the image serves the Angular build from Spring Boot's static resources or
a separate layer in the same image, if evidence during implementation changes that tradeoff.

**Never:** No CI workflow — story 1.3 owns it. No runtime orchestration beyond proving the image
builds and serves — story 1.2 owns "one command starts the whole system" and the id strategy. No
edit to `flsserver/`, `flsweb/`, or the repository-root `docker-compose.yml` (legacy e2e infra
only).

</frozen-after-approval>

## Code Map

- `alpenflight/` -- does not exist on this branch; create fresh, no prior code to reconcile
- `_bmad-output/planning-artifacts/architecture/architecture-fls-2026-08-29/ARCHITECTURE-SPINE.md:261-287`
  -- Structural Seed tree, the exact folder names and module dependency direction
  (pro -> open -> core -> platform)
- `_bmad-output/planning-artifacts/architecture/architecture-fls-2026-08-29/ARCHITECTURE-SPINE.md:248-259`
  -- Stack table (Java 25, Spring Boot 4.1.1, PostgreSQL 18.6, Angular 22.0.1) and the pin
  requirement for the remaining tools
- `docker-compose.yml` (repo root) -- confirmed legacy-only (mssql, mailpit for `e2e/`); do not
  reuse or edit; `deploy/` gets its own file, wired for real in story 1.2
- `.claude/skills/bmad-project-context/` -- the skill this story must run against `alpenflight/`
  for the `AGENTS.md` acceptance criterion
- Verified absent: no `*.gradle*` file, no root `Dockerfile`, no `alpenflight/` anywhere outside
  `docs/attempt-1/` (archive, not a source to copy from)

## Tasks & Acceptance

**Execution:**
- [x] `alpenflight/settings.gradle.kts` -- declare the 7 subprojects, `server/*` as Java modules,
  `client/platform` and `client/features` driven by a Node Gradle plugin -- one entry point over
  the whole tree
- [x] `alpenflight/gradle/libs.versions.toml` -- version catalog; the single source of truth for
  every pin, no comment
- [x] `alpenflight/gradlew` + `gradle/wrapper/gradle-wrapper.properties` -- wrapper pinned to the
  verified latest stable Gradle
- [x] `alpenflight/server/platform/build.gradle.kts` -- Spring Boot app module, publishes the
  OpenAPI spec (springdoc)
- [x] `alpenflight/server/core`, `server/modules-open`, `server/modules-pro` `build.gradle.kts`
  each -- empty placeholder modules wired per the Structural Seed dependency direction only
- [x] `alpenflight/client/platform/package.json` -- Angular workspace root, IndexedDB wrapper
  pinned as a dependency
- [x] `alpenflight/client/features/` -- one placeholder component whose type is a generated
  OpenAPI type, with no hand-written duplicate model
- [x] OpenAPI codegen task (npm script or Gradle task) in `client/platform` -- generates
  TypeScript from `server/platform`'s published spec
- [x] `alpenflight/deploy/Dockerfile` -- multi-stage: build the Angular client, build the Spring
  Boot jar, final image serves both
- [x] Record the Keycloak version pin in `libs.versions.toml` only, no separate markdown file --
  runtime wiring is story 1.2, but the pin ships now
- [x] Run `bmad-project-context` against `alpenflight/`; verify `alpenflight/AGENTS.md` is written

**Acceptance Criteria:**
- Given a clean checkout, when the supplier runs the documented build command, then Gradle builds
  the seven-module tree and the build produces one container image serving the API and the built
  client.
- Given the five named tools, when this story completes, then each carries a version pinned and
  verified against its published release notes, not recalled from memory.
- Given the server publishes an OpenAPI spec, when the client build runs, then it generates
  TypeScript types from that spec, and a component uses a generated type as its own type.
- Given `alpenflight/` now holds code, when the supplier runs `bmad-project-context` against it,
  then the run writes `AGENTS.md`.

## Spec Change Log

- 2026-08-29 — `bmad-correct-course`: Roman raised that generated code must carry no comments, so
  naming and structure stay the only source of truth. Amended the frozen "Always" constraint to add
  the no-comment rule (exception: `package-info.java`'s Javadoc). Known-bad state avoided: a
  rationale comment (e.g. citing an AD number or a story) drifting out of sync with the code it
  describes. Also recorded in `ARCHITECTURE-SPINE.md`'s Consistency Conventions table and
  `epic-1-context.md`, so every later story inherits it without rereading this log.
- 2026-08-29 — `bmad-correct-course`: Roman raised that a markdown planning file must never restate
  a pinned version number, to avoid the same drift risk as a code comment. Amended the frozen
  "Always" constraint. Removed this section's provisional version numbers below, replacing them
  with tool names only — the real pin belongs solely in `libs.versions.toml`/`package.json`.
  Deleted the corresponding `Record the Keycloak version pin in ... deploy/VERSIONS.md` task
  option in favor of `libs.versions.toml` only. Also recorded in `ARCHITECTURE-SPINE.md` (new
  "Versions" convention row) and `epic-1-context.md`.
- 2026-08-29 — Step-04 review (verification-gap + blind-hunter, patch findings): the Comments
  convention's scope was ambiguous about unmodified tool-scaffolded config (`tsconfig.json`,
  `.vscode/*.json`), which retains stock comments Angular CLI writes. Clarified in
  `ARCHITECTURE-SPINE.md` and `epic-1-context.md`: the rule covers hand-authored implementation
  code; a scaffolded file left untouched is not implementation code, same footing as
  `package-info.java`. Corrected this section's sweep claim to say so explicitly. KEEP: the sweep
  itself (Java, Kotlin, TypeScript, `libs.versions.toml`, the Dockerfile) was already accurate and
  complete — only the written claim's scope needed widening, not the underlying work.

## Design Notes

Pin Gradle, Flyway, the OpenAPI generator, idb, and Keycloak by checking each one's current
release notes during implementation — never recall a version from memory, and never write the
number itself into this file or any other markdown file. The version lives once, in the config
that consumes it. Two things worth knowing going in:

- Spring Boot 4.1.1 manages a Flyway version by default; the latest standalone Community release
  (Apache-2.0) may be newer. Decide which to take; since neither a build-file comment nor a
  markdown note is allowed, put the reasoning in the commit message instead.
- Community edition and SaaS both need the same Keycloak pin; this story ships the pin only, story
  1.2 wires the runtime.
- `client/features` is a sibling of `client/platform`, not a descendant, so Node's upward
  `node_modules` resolution can't see `@angular/core` from a feature file. Fixed with an npm
  workspaces root at `client/package.json` (`"workspaces": ["platform"]`) so install hoists
  `node_modules` to `client/`, an ancestor of both. `client/platform/build.gradle.kts` still owns
  every real npm script, invoked via `--workspace=platform`. A later story adding a second feature
  folder should add it to that workspaces array.

## Verification

**Commands, run to completion this session (cold, then again after the step-04 patch round):**
- `./gradlew build` (from `alpenflight/`) -- BUILD SUCCESSFUL; all 7 modules compiled, all server
  and client tests pass. Cold (first run, one-time downloads): 17m16s. Warm, after this story's
  Docker-caching patch: **1m14s**.
- `docker build -f deploy/Dockerfile -t alpenflight:local .` -- succeeds. No source change:
  **~4.2s** (every layer cached). One real source change: **~40.6s** (BuildKit cache mounts keep
  Gradle/npm dependency downloads out of the critical path; only the changed layers rebuild).
- `docker run` the image, then `GET /` -- 200, HTML contains `app-root`; `GET /api/v1/system/status`
  -- `{"status":"UP","serverTime":"..."}`
- `SystemStatusCard`'s type import -- the generated `system-status-response.ts`, no hand-written
  duplicate; now covered by 4 executed tests (`app.spec.ts`) exercising its loading, success, and
  error branches via `HttpTestingController`
- `alpenflight/AGENTS.md` -- exists, non-empty, written by `bmad-project-context`
- The java-not-on-PATH failure (found and reproduced by the coordinator) is fixed and reproduced
  fixed: `generateApiClient` now prepends the resolved JDK's `bin/` to its own process `PATH`,
  verified against a shell with `java` deliberately absent from `PATH`
- `client/package-lock.json` regenerated and present (correct location for an npm-workspaces
  layout rooted at `client/`; `client/platform` has no lockfile of its own by design)
- Repo-wide comment sweep of hand-authored implementation code -- only exceptions found: 3
  `package-info.java` Javadoc blocks and 2 `http://localhost:...` URL string literals (not
  comments). Unmodified Angular-CLI/VS Code scaffold config (`tsconfig*.json`, `.vscode/*.json`,
  `.editorconfig`, `.prettierrc`) retains its stock comments verbatim -- not implementation code,
  never rewritten; re-checked after the patch round
- Repo-wide version-number sweep of tracked markdown -- no restated pin found; `deploy/VERSIONS.md`
  deleted; re-checked after the patch round

## Suggested Review Order

**The module tree**

- Entry point: the seven subprojects that make up the Structural Seed, in one place.
  [`settings.gradle.kts:16`](../../alpenflight/settings.gradle.kts#L16)

**The OpenAPI pipeline: server publishes, client generates**

- Starts the built jar, polls until it publishes its spec, writes it to disk.
  [`platform/build.gradle.kts:81`](../../alpenflight/server/platform/build.gradle.kts#L81)

- Named helper functions carry what a comment used to say -- the no-comments rule's real test.
  [`platform/build.gradle.kts:38`](../../alpenflight/server/platform/build.gradle.kts#L38)

- The one real endpoint this story ships, purely so the client has a real type to generate.
  [`SystemStatusController.java:17`](../../alpenflight/server/platform/src/main/java/ch/alpenflight/platform/status/SystemStatusController.java#L17)

- The java-not-on-PATH fix: resolves the JDK bin dir independent of the host shell's PATH.
  [`platform/build.gradle.kts:4`](../../alpenflight/client/platform/build.gradle.kts#L4)

- The fix applied to the real task -- reproduced broken, then reproduced fixed.
  [`platform/build.gradle.kts:26`](../../alpenflight/client/platform/build.gradle.kts#L26)

- The reference pattern every later thin slice copies: a generated type as the component's own.
  [`system-status-card.ts:21`](../../alpenflight/client/features/system-status/system-status-card.ts#L21)

**The image and its build speed**

- Cache-mounted Gradle run -- dependency downloads persist across builds, not just within one.
  [`Dockerfile:6`](../../alpenflight/deploy/Dockerfile#L6)

- Dependency-only COPY before `npm install`, so a source-only change skips the download entirely.
  [`Dockerfile:13`](../../alpenflight/deploy/Dockerfile#L13)

- Wires `dockerImage` into the standard `build` task, so one command satisfies AC1.
  [`build.gradle.kts:5`](../../alpenflight/build.gradle.kts#L5)

**Tests**

- The coverage gap review caught: `detectChanges()` now actually renders the status card.
  [`app.spec.ts:30`](../../alpenflight/client/platform/src/app/app.spec.ts#L30)

- The empty test now asserts something real instead of nothing.
  [`PlatformApplicationTests.java:28`](../../alpenflight/server/platform/src/test/java/ch/alpenflight/platform/PlatformApplicationTests.java#L28)
