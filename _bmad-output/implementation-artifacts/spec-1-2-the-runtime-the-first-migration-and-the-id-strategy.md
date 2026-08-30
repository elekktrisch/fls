---
title: 'Story 1.2: The runtime, the first migration, and the id strategy'
type: 'feature'
created: '2026-08-30'
status: 'done'
review_loop_iteration: 0
context: ['{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md', '{project-root}/_bmad-output/implementation-artifacts/spec-1-1-the-build-tree-and-the-image.md']
baseline_commit: '6439324474302b4275344de65001601dc376789e'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The tree from story 1.1 has no runtime, no database, and no id type. No later slice
can start a table without one id type fixed first.

**Approach:** Wire one Compose command that starts Postgres, Keycloak, and the app. Add the first
Flyway migration. Fix UUID v7 as the one id type for every table, and prove it end to end with the
real `club` table.

## Boundaries & Constraints

**Always:** One id type for every table: UUID v7, the PostgreSQL 18 native `uuidv7()` default at
the column, the matching Hibernate id generator in Java. Every club-scoped table carries a
row-level-security policy plus `FORCE ROW LEVEL SECURITY`; the app connects as a non-owner
database role that cannot bypass a policy. `docker compose up` in `deploy/` starts Postgres 18.6,
Keycloak, and the app image together; Keycloak imports its realm from a file in the repository. The
first migration creates the real `club` table (id, name, a version column, soft delete) and seeds
exactly one row, matching AD-13's one-club community install. A minimal `Club` JPA entity proves
Hibernate mints and reads a UUID v7 id through the non-owner role. Record the fixed id type in
`ARCHITECTURE-SPINE.md` and in `epic-1-context.md`, replacing the "not yet chosen" language. Verify
every new version pin (Postgres driver, Keycloak Compose image) against its release notes; no
pinned number in a markdown file.

**Ask First:** Whether the non-owner role's grants and the RLS policy on `club` need a name
convention beyond `app_user` / `club_isolation`, if research surfaces a project-specific pattern.

**Never:** No `core/club` repository, service, or controller — later stories build the deep slice
on this table. No Keycloak realm content beyond what proves import works; the identity port and
sign-in flow are story 1.7. No CI workflow; story 1.3 owns it. No edit to `flsserver/`, `flsweb/`,
or the repository-root `docker-compose.yml`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Matching club | Session variable set to the seeded club's id | The one `club` row returns | N/A |
| No club set | Session variable unset | Zero rows return, no error | N/A |
| Wrong club | Session variable set to a different id | Zero rows return | N/A |

</frozen-after-approval>

## Code Map

- `alpenflight/gradle/libs.versions.toml` -- `flyway` and `keycloak` versions already pinned but
  unconsumed; add the Postgres JDBC driver pin
- `alpenflight/server/platform/build.gradle.kts` -- add Flyway, the Postgres driver, and Spring
  Data JPA; no Flyway/JPA dependency exists yet
- `alpenflight/server/platform/src/main/resources/application.yml` -- 13-line file today, no
  datasource; add datasource and Flyway config
- `alpenflight/server/platform/src/main/resources/db/migration/` -- new; `V1__create_app_role.sql`
  then `V2__create_club.sql`: non-owner role + grants, then the `club` table, RLS policy, and seed
- `alpenflight/server/core/src/main/java/ch/alpenflight/core/club/` -- today an empty
  `package-info.java` stub; add the `Club` entity only
- `alpenflight/deploy/Dockerfile` -- exists (story 1.1); reference only, no change expected
- `alpenflight/deploy/docker-compose.yml` -- new; Postgres, Keycloak, the app image
- `alpenflight/deploy/keycloak/` -- new; the realm import file
- `_bmad-output/planning-artifacts/architecture/architecture-fls-2026-08-29/ARCHITECTURE-SPINE.md:235,340`
  -- the "Ids" convention row and the "Id strategy" deferred bullet; name UUID v7
- `_bmad-output/implementation-artifacts/epic-1-context.md:67,129-130`
  -- the "not yet chosen" line and the story 1.6 note about a temporary club source

## Tasks & Acceptance

**Execution:**
- [x] `alpenflight/gradle/libs.versions.toml` -- add the Postgres JDBC driver version, verified
  against its release notes -- every other pin this story needs already exists
- [x] `alpenflight/server/platform/build.gradle.kts` -- add Flyway, the Postgres driver, Spring
  Data JPA -- the runtime and the migration both need them
- [x] `alpenflight/server/platform/src/main/resources/application.yml` -- add the datasource
  (non-owner role) and Flyway settings -- the app must connect as the restricted role, never the
  migration owner
- [x] `alpenflight/server/platform/src/main/resources/db/migration/V1__create_app_role.sql` --
  create the non-owner role and its grants -- AD-2 requires the app to run as a role that cannot
  bypass RLS
- [x] `alpenflight/server/platform/src/main/resources/db/migration/V2__create_club.sql` -- create
  `club`
  (`id uuid primary key default uuidv7()`, `name`, `version`, soft-delete column), its RLS policy,
  `FORCE ROW LEVEL SECURITY`, and seed the one row -- AD-13's one-club install, proven for real
- [x] `alpenflight/server/core/src/main/java/ch/alpenflight/core/club/Club.java` -- a JPA entity
  with a UUID id and the matching Hibernate generator -- proves the id strategy through the ORM,
  not only through SQL
- [x] `alpenflight/deploy/docker-compose.yml` -- Postgres, Keycloak (realm import mounted),
  the app image, one network -- "one command starts the whole system"
- [x] `alpenflight/deploy/keycloak/realm-alpenflight.json` -- a minimal realm the Compose file
  imports on start -- proves the import path, not the sign-in flow
- [x] `ARCHITECTURE-SPINE.md` -- name UUID v7 in the "Ids" convention row; mark the "Id strategy"
  deferred bullet fixed, dated
- [x] `epic-1-context.md` -- replace "not yet chosen" with the fixed type; correct the story 1.6
  note now that `club` exists from this story on
- [x] An integration test against the running Postgres container -- proves the three I/O Matrix
  rows: matching, unset, and wrong club session variable

**Unplanned, required during verification** (not in the original Code Map; each one blocked
`./gradlew build` or `docker compose up` and had to be fixed to satisfy this story's own
Verification section):
- `alpenflight/server/core/build.gradle.kts`, `server/modules-open/build.gradle.kts`,
  `server/modules-pro/build.gradle.kts` -- Spring Boot 4.1 split its autoconfiguration into
  per-feature artifacts and no longer resolves versions for nested BOM imports the way the
  starters implied; each downstream module needed its own `io.spring.dependency-management` BOM
  import to resolve the JPA starter it inherits from `platform`
- `alpenflight/gradle/libs.versions.toml` -- Testcontainers 2.x renamed every module with a
  `testcontainers-` prefix; the catalog had the old 1.x names. Added `spring-boot-flyway` (the
  Spring Boot 4.1 module that carries `FlywayAutoConfiguration`; the raw `flyway-core` jar alone
  does not register it) and `junit-platform-launcher` (Gradle 9's test executor needs it explicit)
- `alpenflight/server/platform/src/main/java/ch/alpenflight/platform/PlatformApplication.java` --
  added `@EntityScan("ch.alpenflight")`; the default scan only covers the main class's own package,
  missing `Club` in the sibling `core.club` package
- `alpenflight/server/platform/src/main/resources/db/migration/V2__create_club.sql` -- the RLS
  policy's unset-session-variable guard needed `CASE WHEN` rather than `AND`, since Postgres does
  not guarantee `AND` operand evaluation order; and `INSERT ... RETURNING` requires the new row to
  pass the SELECT-equivalent policy, which a database-generated id can never do on first insert
- `alpenflight/server/platform/src/main/java/ch/alpenflight/platform/id/UuidV7Generator.java` and
  `UuidV7.java` (new) -- the story's Boundaries text calls for "the matching Hibernate id generator
  in Java"; a plain `@Generated(event = INSERT)` under-implemented that and hit the RETURNING/RLS
  conflict above. A `BeforeExecutionGenerator` calls `uuidv7()` before the insert, so the id is
  known (and can be set as the session variable) before the row is written
- `alpenflight/server/core/src/main/java/ch/alpenflight/core/club/Club.java` -- switched from
  `@Generated(event = EventType.INSERT)` to `@UuidV7` for the reason above
- `alpenflight/server/platform/build.gradle.kts` (`exportOpenApiSpec` task) and
  `server/platform/src/test/java/ch/alpenflight/platform/PlatformApplicationTests.java` -- both
  excluded datasource/JPA/Flyway autoconfiguration using Spring Boot 2/3-era class names that no
  longer exist in 4.1; corrected to the new `org.springframework.boot.{hibernate,jdbc,flyway}
  .autoconfigure.*` packages, or the exclude silently did nothing and the app tried (and failed) to
  reach a real Postgres it doesn't have at build/test time
- `alpenflight/deploy/docker-compose.yml` -- Postgres 18+ images refuse to start against a volume
  mounted at `/var/lib/postgresql/data`; changed the mount to `/var/lib/postgresql` per the image's
  own documented convention for 18+

**Acceptance Criteria:**
- Given a clean checkout, when the supplier runs `docker compose up` in `deploy/`, then Postgres,
  Keycloak, and the app start together and the app connects to Postgres.
- Given the app's datasource role, when it queries `club` with no session variable set, then the
  query returns zero rows, never an error and never another club's row.
- Given the session variable set to the seeded club's id, when the app queries `club`, then exactly
  one row returns, and its id is a valid UUID v7.
- Given the migration runs on a clean database, when it completes, then `club` carries
  `FORCE ROW LEVEL SECURITY` and exactly one seeded row.
- Given `ARCHITECTURE-SPINE.md` and `epic-1-context.md`, when this story completes, then neither
  file still says the id strategy is unchosen.

## Design Notes

UUID v7 was chosen over UUID v4 and a bigint identity: the offline flight queue (AD-8 onward) must
mint an id on a device with no server round trip, which a bigint identity cannot do; UUID v7's
time-ordered prefix avoids the index fragmentation a random UUID causes on a heavy-insert table
such as `flight`; PostgreSQL 18 (the pinned version) generates it natively via `uuidv7()`, so no
extension and no client-side library are needed for the server-side default.

`club` is the tenant root, not a club-scoped table in the AD-3 sense — it carries no `club_id`
column. Its RLS policy still applies AD-2: a session variable names the one club a request may
read, and the seed migration is the only path that ever writes a second row in v1, since PRD
question 15 (who creates a club) stays open.

## Verification

**Commands:**
- `./gradlew build` (from `alpenflight/`) -- expected: BUILD SUCCESSFUL, all modules; Docker-free
- `./gradlew :server:core:integrationTest` (from `alpenflight/`) -- expected: BUILD SUCCESSFUL; runs
  the Docker-backed `ClubTenantIsolationIT` in its own source set, separate from `test`
- `docker compose up` (from `alpenflight/deploy/`) -- expected: Postgres, Keycloak, and the app
  report healthy; the app logs a successful datasource connection
- `docker compose exec` a `psql` check of `club` -- expected: `rowsecurity` and `forcerowsecurity`
  both `t`, one row present

**Manual checks (if no CLI):**
- Confirm no markdown file in this story's diff restates a version number.

## Review Findings

- [x] [Review][Patch] Split Docker-requiring integration tests into a dedicated `integrationTest` Gradle source set/task [`alpenflight/server/core/build.gradle.kts`, `ClubTenantIsolationIT.java`] — resolved decision: `ClubTenantIsolationIT` (Testcontainers-backed) currently runs under the default `test` task, forcing a Docker daemon on every `./gradlew build`/`test`. Move it to its own source set/task so fast unit tests stay Docker-free; story 1.3's CI decides how (and whether) the new task runs in the pipeline. Applied: new `src/integrationTest/java` source set and `:server:core:integrationTest` task; `ClubTenantIsolationIT` moved there; verified `./gradlew build` is Docker-free and `./gradlew :server:core:integrationTest` passes (5/5).

- [x] [Review][Patch] Doc/code mismatch on the id-generator mechanism [`_bmad-output/implementation-artifacts/epic-1-context.md:67-69`, `ARCHITECTURE-SPINE.md:235`, `alpenflight/server/platform/src/main/java/ch/alpenflight/platform/id/UuidV7Generator.java:18-29`] -- both docs say the Hibernate generator "reads the minted value back after insert"; the shipped `UuidV7Generator` is a `BeforeExecutionGenerator` that mints via `select uuidv7()` *before* the insert, so the column's `DEFAULT uuidv7()` never fires for a JPA-created row. Both docs call this the skeleton every future slice copies -- as written, a future author would implement the wrong (RETURNING/RLS-conflicting) approach. Applied: both docs now say the generator mints via `uuidv7()` before the insert.

- [x] [Review][Patch] `FORCE ROW LEVEL SECURITY` on `club` has no automated verification [`alpenflight/server/platform/src/main/resources/db/migration/V2__create_club.sql:9`, `ClubTenantIsolationIT.java`] -- `app_user` is not the table owner, so `ENABLE ROW LEVEL SECURITY` alone already produces identical results in all three RLS tests; deleting the `FORCE` line leaves `./gradlew build` green with no automated signal. Applied: added `clubTableHasForceRowLevelSecurityEnabled` asserting `pg_class.relforcerowsecurity`; verified passing.

- [x] [Review][Patch] `club_isolation` policy has no `FOR` clause and `WITH CHECK (true)`, a no-op for INSERT/UPDATE [`alpenflight/server/platform/src/main/resources/db/migration/V2__create_club.sql:11-18`] -- intentional and documented as correct for `club` as the tenant root (Design Notes), but nothing warns that a future club-scoped table must not copy `WITH CHECK (true)` verbatim. Add that caveat next to the Ids/RLS convention in `epic-1-context.md`/`ARCHITECTURE-SPINE.md`. Applied: caveat added to both docs' tenant-isolation/AD-2 text.

- [x] [Review][Patch] Health signal can't detect a broken datasource connection [`alpenflight/server/platform/src/main/resources/application.yml:8-9`, `alpenflight/server/platform/src/main/java/ch/alpenflight/platform/status/SystemStatusController.java:17-20`] -- `spring.datasource.hikari.initialization-fail-timeout: -1` plus a static `{"status":"UP"}` health endpoint means `docker compose up`'s app healthcheck can report healthy even when Postgres is unreachable, undermining the AC that the app "connects to Postgres." Applied: removed the `initialization-fail-timeout` override; verified `docker compose up` still reports the app healthy with a real datasource connection.

- [x] [Review][Patch] Spec file restates a pinned version number outside the frozen block [`_bmad-output/implementation-artifacts/spec-1-2-the-runtime-the-first-migration-and-the-id-strategy.md` Code Map and Tasks & Acceptance sections] -- "Postgres 18.6" appears three times; two occurrences sit outside `<frozen-after-approval>`, violating this story's own "no pinned number in a markdown file" boundary and the spine's Versions convention. Applied: both occurrences reworded to "Postgres" with no version number.

- [x] [Review][Patch] Unescaped password substitution in a migration [`alpenflight/server/platform/src/main/resources/db/migration/V1__create_app_role.sql:1`] -- the Flyway placeholder `${appPassword}` is substituted directly into a SQL string literal; a password containing a single quote breaks or corrupts the migration. Applied: password now bound to a `text` variable and passed through `format('%L', ...)`, which escapes it correctly regardless of content; verified via `docker compose up` and the integration test.

- [x] [Review][Patch] `CREATE ROLE app_user` has no existence guard [`alpenflight/server/platform/src/main/resources/db/migration/V1__create_app_role.sql:1`] -- roles are cluster-wide in Postgres; re-running against a second database in the same cluster, or after a partial reset that keeps the role, fails with "role already exists." Applied: wrapped in `IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'app_user')`.

- [x] [Review][Patch] Tenant session variable set via raw string concatenation [`alpenflight/server/core/src/integrationTest/java/ch/alpenflight/core/club/ClubTenantIsolationIT.java`] -- the only worked example of setting `app.current_club_id` in the repo builds the `SET LOCAL` statement by concatenating `UUID.toString()`; story 1.7's real transaction opener is likely to copy this pattern once the value comes from a JWT claim. Use a parameterized `select set_config('app.current_club_id', ?, true)` instead. Applied at both call sites; verified passing.

- [x] [Review][Patch] RLS guard's empty-string branch is dead code [`alpenflight/server/platform/src/main/resources/db/migration/V2__create_club.sql:13-16`] -- `current_setting(name, true)` returns `NULL`, not `''`, when unset, so the `WHEN ... = ''` branch never fires; the correct zero-rows result happens only because the `ELSE` branch's `id = NULL::uuid` evaluates to `NULL`. Harmless today, misleading for anyone copying this pattern. Applied: guard now checks `IS NULL OR = ''` explicitly.

- [x] [Review][Patch] AC "valid UUID v7" not directly asserted for the seeded-row scenario [`alpenflight/server/core/src/integrationTest/java/ch/alpenflight/core/club/ClubTenantIsolationIT.java`] -- `matchingClubSessionVariableReturnsTheOneSeededRow` only checks row count; the `.version() == 7` assertion lives in a separate test against a different, Hibernate-inserted row. Applied: added a `.version() == 7` assertion on the seeded row's id in that test.

- [x] [Review][Patch] Compose healthcheck condition inconsistency [`alpenflight/deploy/docker-compose.yml`] -- `app` depends on `keycloak` with `condition: service_started` even though `keycloak` declares its own healthcheck; `postgres` correctly uses `service_healthy`. Applied: changed to `condition: service_healthy`.

- [x] [Review][Defer] No isolated unit test for `UuidV7Generator` [`alpenflight/server/platform/src/main/java/ch/alpenflight/platform/id/UuidV7Generator.java`] -- deferred, pre-existing test-coverage gap; only indirect coverage today through one integration test.

- [x] [Review][Defer] `UuidV7Generator` reads the raw physical JDBC connection for an extra round-trip query per insert [`alpenflight/server/platform/src/main/java/ch/alpenflight/platform/id/UuidV7Generator.java:18-29`] -- deferred; acceptable for `club`'s low insert volume today, worth revisiting before a high-insert table (e.g. `flight`, per this story's own Design Notes) adopts the same generator.

- [x] [Review][Defer] RLS predicate would raise a raw cast error on a non-UUID session value [`alpenflight/server/platform/src/main/resources/db/migration/V2__create_club.sql:11-18`] -- deferred; not reachable today since only test code sets the variable, always with a valid UUID. Revisit when story 1.7's real transaction opener starts setting it from a JWT claim.

- [x] [Review][Defer] No documented local-dev reset procedure [`alpenflight/deploy/docker-compose.yml`, `alpenflight/README.md`] -- deferred, cosmetic/DX; dropping the `postgres-data` volume and re-importing the Keycloak realm during iteration isn't documented.
