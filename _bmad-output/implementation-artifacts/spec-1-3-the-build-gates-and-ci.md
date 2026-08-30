---
title: 'Story 1.3: The build gates and CI'
type: 'feature'
created: '2026-08-30'
status: 'done'
review_loop_iteration: 0
context: ['{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md', '{project-root}/_bmad-output/implementation-artifacts/spec-1-2-the-runtime-the-first-migration-and-the-id-strategy.md']
baseline_commit: '05f33438ebbf296db238a1035147f658115eec23'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The repository has no CI. Nothing proves the nine architecture rules the spine names as
build gates, so a later story can violate one and nobody finds out until it costs more to fix.

**Approach:** Add CI that runs eight of the nine gates on every commit, each with a checked-in
fixture that deliberately breaks the rule and a test proving the gate catches it. Gate 4 (the
data-kind declaration and its row-level-security check) is carved off to
`deferred-work.md`, because it needs its own migration and its own database-backed check; the other
eight already form one complete, working pipeline.

## Boundaries & Constraints

**Always:** CI runs on GitHub Actions, in `.github/workflows/ci.yml`, on every push and pull request.
Gates 1, 2, 3, 5, 6, 8, and 9 are enforced by compiled-class checks in a new Gradle module,
`server/build-gates`, using ArchUnit; this module depends on every server module purely to read their
compiled classes, and it is never a runtime dependency of anything else. Gate 7 (a cross-tenant query
without a filter returns zero rows) is already proven by `ClubTenantIsolationIT` in `server/core`'s
`integrationTest` source set from story 1.2; this story wires that task into CI, and adds no new test
for it. Each gate's fixture violates the rule inside its own gate's test scope only, and the gate's
test asserts the violation is caught and names the gate; that test is green exactly as long as the
gate works. The standalone-build gate (3: core plus the open modules build and test without the pro
modules) is a Gradle-level check over the resolved dependency graph, not a compiled-class scan. Every
new dependency version is pinned in `libs.versions.toml` and verified against its release notes,
never guessed.

**Ask First:** None — the CHECKPOINT after this spec is the approval gate for the module name and the
eight-of-nine scope proposed above.

**Never:** No gate weakens or removes an existing check from story 1.1 or 1.2. No fixture that
violates a gate ships anywhere the normal build compiles or runs it — a violation lives only inside
its own gate's test, never in `main`-reachable source. No CI job needs a secret or an external
service; every gate runs against code already in the repository, plus Testcontainers-managed
PostgreSQL for gate 7. This story does not touch data-kind declaration or the RLS-verification check
— that is gate 4, deferred.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Clean commit | No rule violation | All eight in-scope gates pass; CI is green | N/A |
| One gate's fixture | A checked-in deliberate violation | That gate's test fails and names the gate | The failure message states which gate is violated |
| A gate stops detecting its violation | The gate's rule is weakened by a later change | The fixture's test turns red | CI fails, catching the regression before merge |
| Standalone build | `:server:platform`, `:server:core`, `:server:modules-open` only | Builds and tests succeed with no reference to `:server:modules-pro` | N/A |

</frozen-after-approval>

## Code Map

- `alpenflight/gradle/libs.versions.toml` -- add the ArchUnit-JUnit5 pin -- gates 1, 2, 3, 5, 6, 8, 9
- `alpenflight/settings.gradle.kts` -- add `include("server:build-gates")` -- new verification-only module
- `alpenflight/server/build-gates/build.gradle.kts` -- new; test-only deps on `platform`, `core`,
  `modules-open`, `modules-pro`, plus ArchUnit -- hosts the compiled-class gates
- `alpenflight/server/build-gates/src/test/java/ch/alpenflight/buildgates/` -- new; one test class per
  ArchUnit-backed gate, each with a fixture sub-package under `.../fixtures/`
- `alpenflight/server/core/src/integrationTest/java/ch/alpenflight/core/club/ClubTenantIsolationIT.java`
  -- existing; already proves gate 7; no change expected, wired into CI only
- `alpenflight/server/modules-open/build.gradle.kts` -- add a Gradle-property-gated (default off)
  dependency on `modules-pro` -- gate 3's deliberate-violation fixture
- `.github/workflows/ci.yml` -- new; the CI entry point, does not exist today
- `alpenflight/README.md` -- remove the "No CI workflow -- story 1.3" line; document the CI commands

## Tasks & Acceptance

**Execution:**
- [x] `alpenflight/gradle/libs.versions.toml` -- pin `archunit-junit5`, verified against its release
  notes -- the compiled-class gates need one shared library
- [x] `alpenflight/settings.gradle.kts` -- include `server:build-gates` -- registers the new module
- [x] `alpenflight/server/build-gates/build.gradle.kts` -- new module; test-scope dependencies on all
  four server modules plus ArchUnit -- needs every module's compiled classes to scan them
- [x] `.../buildgates/SliceShapeGateTest.java` (+ fixtures) -- gate 1: a slice package under
  `core`, `modulesopen`, or `modulespro` contains all four of `domain/application/web/infra`, or none
- [x] `.../buildgates/CoreNeverReferencesProGateTest.java` (+ fixture) -- gate 2: no class under
  `ch.alpenflight.core` depends on a class under `ch.alpenflight.modulespro`
- [x] `.../buildgates/NoBinaryFloatInMoneyTypeGateTest.java` (+ fixture) -- gate 5: no `double`/`float`
  field on a class whose package name contains `charging`, `delivery`, or `invoice`
- [x] `.../buildgates/NoCrossSliceReachGateTest.java` (+ fixture) -- gate 6: no class outside a slice's
  own package depends on that slice's `domain`, `application`, or `infra` sub-packages
- [x] `.../buildgates/ClockInjectedGateTest.java` (+ fixture) -- gate 8: no class calls the no-arg
  `now()` overload on `Instant`, `LocalDate`, `LocalDateTime`, or `ZonedDateTime`; `SystemStatusController`
  is the existing pass-case
- [x] `.../buildgates/VersionColumnGateTest.java` (+ fixture) -- gate 9: every `@Entity` class carries
  exactly one `@Version` field; `Club` is the existing pass-case
- [x] `alpenflight/server/modules-open/build.gradle.kts` -- a Gradle property, default off, that adds a
  dependency on `modules-pro` when set -- proves the standalone-build gate (3) would fail if the
  reference were real, without ever shipping the reference
- [x] a Gradle task verifying `:server:modules-open`'s resolved runtime classpath never includes
  `:server:modules-pro` with the property off, and does with it on -- gate 3;
  `verifyStandaloneBuildGate`, wired into `:server:modules-open:check`
- [x] `.github/workflows/ci.yml` -- jobs: the full `./gradlew build` (unit tests, the build-gates
  module, the client, the image); `./gradlew :server:core:integrationTest` (Docker-backed, gate 7);
  the standalone build (`:server:platform:build :server:core:build :server:modules-open:build`,
  property off) -- one command per job, matching the README's existing commands
- [x] `alpenflight/README.md` -- remove the "No CI workflow" gap note; add the CI job list; note gate 4
  ships separately -- keeps the doc accurate, per the spine's working-software directive

**Unplanned, required during verification** (not in the original Code Map; each one blocked this
story's own Verification section and had to be fixed to satisfy it):
- `.../buildgates/NoBinaryFloatInMoneyTypeGateTest.java` -- the first draft built gate 5's rule as
  `noFields().should(customCondition)`, with the custom `ArchCondition` reporting a `violated` event
  for the bad case (a `double`/`float` field), matching the convention every ArchUnit built-in
  condition uses under `classes().should(...)`. Under a `no...()`-prefixed given, ArchUnit inverts
  that interpretation for a *custom* condition, so the fixture's deliberate violation went
  undetected and the production (empty-package) case threw ArchUnit's own "failed to check any
  classes" error instead of passing. Fixed by using the positive `fields()` given (matching gates 8
  and 9's `classes()` pattern, both already correct) and adding `.allowEmptyShould(true)`, since no
  `charging`/`delivery`/`invoice` package exists in production code yet.
- `alpenflight/server/modules-open/build.gradle.kts` -- the fixture property's dependency direction
  (`:server:modules-open` on `:server:modules-pro`) is a real Gradle circular dependency, because
  `:server:modules-pro` already depends on `:server:modules-open` (the module tiers, unchanged from
  story 1.1). Running `:server:modules-open:build -PgateFixtureViolateStandaloneBuild=true` fails
  with Gradle's own "Circular dependency between the following tasks" error before any gate code
  runs, because `build`/`assemble`/`jar`/`compileJava` all need `:server:modules-open`'s classpath
  configuration's actual files, which now need `:server:modules-pro` built, which needs
  `:server:modules-open` built. `verifyStandaloneBuildGate` reads only the resolved dependency
  graph's metadata (`configurations.runtimeClasspath.incoming.resolutionResult`), never its files,
  so invoked directly (not through `build`) it proves both directions cleanly. The Verification
  section below and the README were updated to invoke the task directly for the fixture-on case.

**Acceptance Criteria:**
- Given a commit with no violation, when CI runs, then gates 1, 2, 3, 5, 6, 7, 8, and 9 all pass and
  the workflow reports green.
- Given each in-scope gate's checked-in fixture, when its own test runs, then the test proves the gate
  detects the violation, and the assertion message names the gate.
- Given the standalone-build job, when it runs, then `:server:platform`, `:server:core`, and
  `:server:modules-open` build and test with no `:server:modules-pro` task in the graph.
- Given `ARCHITECTURE-SPINE.md`'s nine build gates, when a developer reads `server/build-gates` and
  `ClubTenantIsolationIT`, then eight of the nine are traceable to exactly one test, and the ninth
  (gate 4) is traceable to its `deferred-work.md` entry.

## Design Notes

**The self-testing fixture pattern.** Each gate is one rule plus one fixture that violates it, kept in
a package the normal build never treats as production code (a `fixtures` sub-package under the gate's
own test source). The gate's test runs the rule against the fixture and asserts a violation is
reported. This test is green exactly when the gate still works — weakening the rule turns it red,
which is the "stays in the repository, so a gate that stops working is found" requirement from story
1.3's acceptance criteria.

**Why a new module for the compiled-class gates.** Gates 1, 2, 5, 6, 8, and 9 need to see the compiled
classes of every server module at once — including `modules-pro`, so gate 2 can prove `core` never
reaches into it, and the others can scan every module's own code for their own rule. No existing
module can host that without inverting the dependency graph (e.g. `platform`'s test scope depending
on the modules built on top of it). A dedicated, test-only module keeps the inspection code separate
from the code it inspects, and it never ships in the image.

**Gates 2 and 3 together cover the pro boundary, checked two different ways for two different
edges.** Gate 2 is a static proof over compiled classes, and as implemented it checks only one edge:
no class reference crosses from `core` into `modules-pro`. Gate 3 is an operational proof over the
Gradle task graph: a scoped build of `core` plus the open modules never touches `:server:modules-pro`
at all, which also covers the `modules-open` → `modules-pro` edge gate 2 does not check. They can
never disagree while `modules-open`'s build file carries no real dependency on `modules-pro` — the
Gradle-property fixture is what lets gate 3 prove it would notice if that ever changed.

**Gate 4 is deferred, not skipped.** It needs a new migration (`V3__club_data_kind.sql`) declaring
every existing table's data kind, plus a Testcontainers-backed check in `server/core`'s
`integrationTest` source set. Both are self-contained and land as a follow-up patch to the same
`.github/workflows/ci.yml` this story creates, tracked in `deferred-work.md`.

## Verification

**Commands:**
- `./gradlew build` (from `alpenflight/`) -- expected: BUILD SUCCESSFUL; runs gates 1, 2, 3, 5, 6, 8,
  9 and their fixtures, Docker-free except the image step
- `./gradlew :server:core:integrationTest` (from `alpenflight/`) -- expected: BUILD SUCCESSFUL; runs
  gate 7 against a real Testcontainers PostgreSQL
- `./gradlew :server:modules-open:verifyStandaloneBuildGate -PgateFixtureViolateStandaloneBuild=true`
  (from `alpenflight/`) -- expected: BUILD SUCCESSFUL; the task's own metadata-only classpath check
  now finds `:server:modules-pro` on `:server:modules-open`'s resolved runtime classpath, proving
  gate 3's fixture would have been caught. (Running the property through `:server:modules-open:build`
  instead fails earlier, with Gradle's own circular-dependency error -- see Unplanned bullet above.)
  This command is also its own CI job (`standalone-build-gate-fixture`), added during review so
  gate 3's fixture is proven automatically on every commit, not only when run by hand.

**Manual checks (if no CLI):**
- Open `.github/workflows/ci.yml` and confirm four jobs, none needing a secret.

## Review Findings

- [x] [Review][Patch] Gate 3's fixture-violation proof never ran automatically anywhere -- it was
  only a documented manual command [`.github/workflows/ci.yml`, `alpenflight/README.md`]. Applied:
  added a fourth CI job, `standalone-build-gate-fixture`, running
  `./gradlew :server:modules-open:verifyStandaloneBuildGate -PgateFixtureViolateStandaloneBuild=true
  --no-daemon`; README's CI section updated to describe it as a CI job, not a manual-only command.
- [x] [Review][Patch] `gateFixtureViolateStandaloneBuild` checked only property presence, so
  `-PgateFixtureViolateStandaloneBuild=false` would still activate the fixture
  [`alpenflight/server/modules-open/build.gradle.kts`]. Applied: parses the property's value via
  `(project.findProperty(...) as String?)?.toBoolean() ?: false`.
- [x] [Review][Patch] Gate 8's owner-type set covered only `Instant`, `LocalDate`, `LocalDateTime`,
  and `ZonedDateTime`, missing several other `java.time` types with a no-arg `now()`
  [`.../buildgates/ClockInjectedGateTest.java`]. Applied: added `LocalTime`, `OffsetDateTime`,
  `OffsetTime`, `Year`, and `YearMonth`.
- [x] [Review][Patch] Gate 5 flagged only primitive `double`/`float` fields, missing the boxed and
  array forms AD-14 also forbids [`.../buildgates/NoBinaryFloatInMoneyTypeGateTest.java`]. Applied:
  the condition now also flags `Double`, `Float`, `double[]`, and `float[]`; added a boxed-`Double`
  fixture (`fixtures/invoice/InvoiceTotalWithBoxedBinaryFloat.java`) alongside the existing primitive
  one, each scanned by its own dedicated test.
- [x] [Review][Patch] Gate 9 used `getFields()` (declared-only), which would false-positive on an
  `@Entity` whose `@Version` field comes from a `@MappedSuperclass`
  [`.../buildgates/VersionColumnGateTest.java`]. Applied: switched to `getAllFields()` (declared +
  inherited); added a passing fixture (`fixtures/inheritedversion/`, a `@MappedSuperclass` carrying
  `@Version` plus an `@Entity` extending it) and a dedicated test proving it is not flagged; moved
  the existing violating fixture into its own `fixtures/missingversion/` sub-package so the two
  scans stay isolated.
- [x] [Review][Patch] `SliceShapeGateTest` and `NoCrossSliceReachGateTest` each declared an identical
  `PRODUCTION_MODULE_ROOTS` array literal. Applied: moved the constant into the shared
  `support/Slices.java` helper both tests already import; both now reference
  `Slices.PRODUCTION_MODULE_ROOTS`.
- [x] [Review][Patch] `.github/workflows/ci.yml` triggered on both `push` (any branch) and
  `pull_request`, double-running CI on a branch with an open PR. Applied: scoped `push` to `branches:
  [main]`; also keyed `concurrency.group` on the PR number when present
  (`github.event.pull_request.number || github.ref`) so same-PR runs still cancel each other.
- [x] [Review][Patch] `.github/workflows/ci.yml` had no `permissions:` block, defaulting to a
  broader-than-needed `GITHUB_TOKEN` scope. Applied: added a top-level `permissions: contents: read`.
- [x] [Review][Patch] `.github/workflows/ci.yml` had no `timeout-minutes` on any job, so a hang would
  run to GitHub's multi-hour default. Applied: 30 minutes on `build`, 20 on the other three jobs.
- [x] [Review][Patch] `epic-1-context.md`'s nine-build-gates note called out gates 2 and 3 alone as
  "vacuous until real code exists," but the same is true today of gates 1, 5, 6, 8, and 9 too, since
  almost no real slice code exists yet. Applied: broadened the note to cover all in-scope gates.
- [x] [Review][Patch] This spec's Design Notes said gate 2 proves "no class reference crosses from
  core/**open** into pro," but gate 2 as implemented (and as `ARCHITECTURE-SPINE.md` states it)
  checks only `core`; the `modules-open` → pro edge is gate 3's job alone. Applied: reworded the
  "Why a new module" and "Gates 2 and 3" Design Notes paragraphs (both outside the frozen block) for
  accuracy; no code change.
- [x] [Review][Patch] `NoCrossSliceReachGateTest`'s fixture proved only the violation case; nothing
  proved the rule does not false-positive on a class reaching its *own* slice's hidden package.
  Applied: added a passing fixture (`fixtures/someslice/application/InsiderService.java`, inside the
  slice, depending on the slice's own `domain` package) and a dedicated test asserting it is not
  flagged.

## Suggested Review Order

**CI entry point**

- The trigger and permission surface: push scoped to `main`, PR keys the concurrency group, least-privilege token.
  [`ci.yml:3`](../../.github/workflows/ci.yml#L3)

- Four jobs at a glance: build (gates 1,2,3,5,6,8,9), integration-test (gate 7), standalone-build (gate 3 off-case), the new fixture self-test (gate 3 on-case).
  [`ci.yml:19`](../../.github/workflows/ci.yml#L19)

**Gate 3 — the standalone-build check, the only Gradle-graph gate**

- The property now parses its actual value, not just presence — the bug a review patch fixed.
  [`build.gradle.kts:25`](../../alpenflight/server/modules-open/build.gradle.kts#L25)

- The task reads only resolved dependency-graph metadata, so it can prove the fixture without triggering the real circular-dependency crash a full build would hit.
  [`build.gradle.kts:40`](../../alpenflight/server/modules-open/build.gradle.kts#L40)

- The fixture-on job: proves gate 3's own detection logic still fires, on every commit, closing the self-test gap a review patch found.
  [`ci.yml:59`](../../.github/workflows/ci.yml#L59)

**The compiled-class gates — a new verification-only module**

- Test-only dependencies on all four server modules, including `modules-pro`, purely to read their compiled classes.
  [`build.gradle.kts:25`](../../alpenflight/server/build-gates/build.gradle.kts#L25)

- The shared slice-detection helper both gate 1 and gate 6 use, after a review patch removed their duplicated constant.
  [`Slices.java:12`](../../alpenflight/server/build-gates/src/test/java/ch/alpenflight/buildgates/support/Slices.java#L12)

- Gate 1: a slice carries all four of domain/application/web/infra, or none — the all-or-nothing rule.
  [`SliceShapeGateTest.java:46`](../../alpenflight/server/build-gates/src/test/java/ch/alpenflight/buildgates/SliceShapeGateTest.java#L46)

- Gate 2: core never depends on pro — the static half of the core/open-vs-pro invariant gate 3 completes.
  [`CoreNeverReferencesProGateTest.java:36`](../../alpenflight/server/build-gates/src/test/java/ch/alpenflight/buildgates/CoreNeverReferencesProGateTest.java#L36)

- Gate 5: no binary float in a money-adjacent type, now covering boxed and array forms too, after a review patch.
  [`NoBinaryFloatInMoneyTypeGateTest.java:21`](../../alpenflight/server/build-gates/src/test/java/ch/alpenflight/buildgates/NoBinaryFloatInMoneyTypeGateTest.java#L21)

- Gate 6: only a slice's own code may reach its `domain`/`application`/`infra`; a passing in-slice fixture now proves no false positive too.
  [`NoCrossSliceReachGateTest.java:49`](../../alpenflight/server/build-gates/src/test/java/ch/alpenflight/buildgates/NoCrossSliceReachGateTest.java#L49)

- Gate 8: no no-arg `now()` on any java.time type — the owner-type set a review patch completed.
  [`ClockInjectedGateTest.java:23`](../../alpenflight/server/build-gates/src/test/java/ch/alpenflight/buildgates/ClockInjectedGateTest.java#L23)

- Gate 9: every `@Entity` carries exactly one `@Version` field, now walking inherited fields too so a `@MappedSuperclass` pattern won't false-positive.
  [`VersionColumnGateTest.java:22`](../../alpenflight/server/build-gates/src/test/java/ch/alpenflight/buildgates/VersionColumnGateTest.java#L22)

**Wiring and docs**

- The new module joins the build.
  [`settings.gradle.kts:23`](../../alpenflight/settings.gradle.kts#L23)

- The ArchUnit-JUnit5 pin, verified against its release notes.
  [`libs.versions.toml:1`](../../alpenflight/gradle/libs.versions.toml#L1)

- README replaces the "no CI" gap note with the job list.
  [`README.md:1`](../../alpenflight/README.md#L1)

- Gate 4 (data-kind declaration and its RLS check) tracked as a follow-up, not silently dropped.
  [`deferred-work.md:71`](deferred-work.md#L71)
