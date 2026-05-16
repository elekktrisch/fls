# fls-server

The Spring Boot service for the FLS modernization. Sibling to `flsweb/` (legacy)
and `flsserver/` (legacy) inside this repo. Story scope is tracked in
[`docs/modernization/stories/`](../../docs/modernization/stories/); this
skeleton was set up by [S-001](../../docs/modernization/stories/S-001-scaffold-server-skeleton.md).

## Run

```bash
./gradlew bootRun
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/v1/hello
```

The server binds to `0.0.0.0:8080` by default (all network interfaces; no
`server.address` is pinned). In dev you reach it via `localhost`; in prod it
sits behind a reverse proxy (S-044). If startup fails with `BindException:
Address already in use`, override the port with `SERVER_PORT=8081 ./gradlew
bootRun` (or set it in `.env`).

**JDK requirements:** the Gradle wrapper auto-provisions JDK 25 via Foojay,
so you don't need to install it manually. You DO need a working JDK 17+ on
your `PATH` to bootstrap Gradle itself — any modern OS-provided JDK works
(e.g. `sdk install java 21-tem`, `brew install temurin@21`, or
`apt install openjdk-21-jdk`). Once Gradle starts, Foojay handles the
JDK 25 toolchain download on first build.

## Build + test

```bash
./gradlew check                          # compile + unit + integration tests
./gradlew bootJar                        # produces build/libs/fls-server-0.0.1-SNAPSHOT.jar
./gradlew verifyNullAwayFailsOnViolation # asserts that null-safety is enforced (AC3)
```

## Build tool: Gradle Kotlin DSL — why

Both Maven and Gradle work identically with Spring Boot at this scale. We chose
**Gradle Kotlin DSL** for four concrete reasons:

1. **Spring Boot 4.x docs + `start.spring.io` default emit Gradle Kotlin DSL.**
   Maven samples still exist but Kotlin DSL is the lead. Picking Maven means
   hand-translating every snippet for the foreseeable future.
2. **NullAway integration is two plugin IDs in `build.gradle.kts`** via
   `net.ltgt.errorprone` + `net.ltgt.nullaway`. The Maven equivalent requires
   `error-prone-javac` workarounds, `<annotationProcessorPaths>` plumbing, and
   `-XDcompilePolicy=simple` shims.
3. **Type-safe accessors** catch typos at IDE-edit time, which matters for a
   Java-comfortable solo operator who isn't living in build scripts daily.
4. **Single-module fat JAR via `bootJar`** is identical in both; tipping factor
   is the NullAway plumbing + docs alignment.

The decision lives here rather than as a separate ADR because it's reversible
and contained — both tools build the same artifact.

## Java 25 + Spring Boot 4 conventions

- **`@SpringBootApplication`** at `ch.fls.FlsApplication`. Component scan covers
  every `ch.fls.<domain>` package added later (e.g. `ch.fls.flight`,
  `ch.fls.aircraft`).
- **Package by domain, not by layer.** Cross-cutting non-domain code lives
  under `ch.fls.platform` (this is where `HelloController` sits).
- **`application.yml`**, not `application.properties`. Profile overrides go in
  `application-{dev,test,prod}.yml`.
- **JSpecify `@NullMarked` at every package root.** Every new package needs a
  `package-info.java` with `@NullMarked` — NullAway then enforces non-null
  defaults for the whole package. Use `org.jspecify.annotations.Nullable`
  (not `javax.annotation.*` or `jakarta.annotation.*`) when a field/parameter
  is genuinely nullable.
- **No Lombok.** Prefer Java 25 records for DTOs.

## Conventions resolved in this skeleton

| Question | Answer |
|---|---|
| Servlet vs. reactive | Servlet (`spring-boot-starter-webmvc`). MVC + virtual threads. JPA needs a session-bound thread; reactive R2DBC-Hibernate is not production-ready. |
| Virtual threads | Enabled (`spring.threads.virtual.enabled=true`). JEP 491 (Java 25) eliminated the `synchronized` pinning footgun. Re-audit when S-018 ShedLock + S-022 `@TenantId` `ThreadLocal` land. |
| CORS | **Not wired here.** Will land with the first frontend integration story (S-021). The legacy `*` open-cors baseline is **not** inherited. |
| Spring Security | **Not on the classpath.** S-020 owns adding the starter + first `SecurityFilterChain`. `GET /api/v1/hello` and `GET /actuator/{health,info}` are deliberately unauthenticated until then. |
| Flyway | Not wired. S-009. |
| Lombok | No. |

## File layout

```
next/server/
├── build.gradle.kts                                       # Gradle Kotlin DSL
├── settings.gradle.kts                                    # Foojay auto-toolchain
├── gradle/wrapper/                                        # pinned Gradle distro
├── gradlew[.bat]                                          # checked in
├── src/main/java/ch/fls/
│   ├── FlsApplication.java                                # @SpringBootApplication
│   ├── package-info.java                                  # @NullMarked
│   ├── config/                                            # placeholder; cross-cutting beans land here
│   └── platform/hello/HelloController.java                # demo endpoint; remove at S-020
├── src/main/resources/
│   ├── application.yml                                    # base config (pinned: open-in-view=false,
│   ├── application-dev.yml                                #   strict Jackson, narrow Actuator surface,
│   ├── application-test.yml                               #   virtual threads on, springdoc dormant)
│   ├── application-prod.yml
│   └── logback-spring.xml                                 # stub; S-031 swaps in JSON encoder. MDC keys
│                                                          #   request_id / tenant_id / actor_user_id reserved.
├── src/test/java/ch/fls/                                  # unit + integration tests
└── src/nullawayDemo/java/ch/fls/nullaway/                 # source set the verify task expects to fail
```

## NullAway false-positives

If NullAway flags a legitimate non-null case (typically library reflection),
suppress narrowly and explain why:

```java
@SuppressWarnings("NullAway")  // <library> reflection returns non-null per docs
```

## Useful endpoints

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/hello` | Demo. Returns `{"message":"Hello FLS","timestamp":"<ISO-8601>"}`. Auth-gate or remove at S-020. |
| GET | `/actuator/health` | Liveness/readiness composite. Includes `db` indicator once Flyway autoconfig is wired. |
| GET | `/actuator/info` | Empty until `info.*` keys land. |

## Database migrations (Flyway)

Schema lives in `src/main/resources/db/migration/V<n>__<desc>.sql`. Flyway
runs `migrate()` at every Spring Boot startup. **Never amend a migration that
has shipped to any environment** — its checksum is locked. Add a new
`V<n+1>__…sql` instead. See [S-009](../../docs/modernization/stories/implemented/S-009-wire-flyway.md)
for the rationale; see [CONVENTIONS.md](CONVENTIONS.md) for the rules.

### Adding a new migration

1. Pick the next version (`V2__…`, `V3__…`, …). Description should be
   `snake_case` and read naturally: `V2__identity_and_reference.sql`.
2. Drop the file under `src/main/resources/db/migration/`.
3. `./gradlew test` boots the integration test suite against a Docker-CLI
   Postgres container; this validates the new migration applies cleanly.
4. For ad-hoc local validation without booting Spring:
   ```bash
   docker run -d --name fls-pg \
     -e POSTGRES_PASSWORD=fls -e POSTGRES_USER=fls -e POSTGRES_DB=fls \
     -p 5432:5432 postgres:17.4-alpine
   ./gradlew flywayMigrate flywayValidate flywayInfo
   docker rm -f fls-pg
   ```
   The Gradle plugin reads `DATASOURCE_URL` / `DATASOURCE_USER` /
   `DATASOURCE_PASSWORD` env vars with loopback defaults.

### Recovering from a botched migration

If a migration fails mid-flight, `flyway_schema_history` records the failure
with `success=false`. Spring Boot will refuse to start until the history is
clean.

**Operator-only manual recovery:**

```bash
DATASOURCE_URL=... ./gradlew flywayInfo    # see the failed entry
DATASOURCE_URL=... ./gradlew flywayRepair  # remove failed entries
# Fix the migration content (or add a new V<n+1>__fix.sql)
DATASOURCE_URL=... ./gradlew flywayMigrate # retry
```

`flywayClean` is disabled by `application.yml` (`spring.flyway.clean-disabled=true`)
and CANNOT be enabled from CI. Drop a schema by hand if you need to — and
only in dev.

### DataSource env vars

| Env var | Purpose |
|---|---|
| `DATASOURCE_URL` | JDBC URL (e.g. `jdbc:postgresql://localhost:5432/fls`) |
| `DATASOURCE_USER` | Postgres username |
| `DATASOURCE_PASSWORD` | Postgres password |

The `dev` profile provides loopback defaults (`fls`/`fls` at
`localhost:5432`); `prod` requires all three to be set explicitly. The
`test` profile uses an in-memory H2 (MODE=PostgreSQL) DataSource for
non-Flyway tests; the Flyway integration test overrides via a real Postgres
container.
