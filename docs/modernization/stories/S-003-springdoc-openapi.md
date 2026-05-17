---
id: S-003
title: Wire springdoc-openapi + publish OpenAPI spec
epic: E-01
status: in_progress
started_at: 2026-05-17
depends_on: [S-001]
acceptance:
  - `GET /v3/api-docs` returns a valid OpenAPI 3.1 spec covering the hello endpoint from S-001.
  - `GET /swagger-ui` renders the spec and is reachable in dev.
  - The spec includes `@Operation`/`@Schema` annotations on the hello endpoint as a worked example for future controllers.
  - The spec includes the security scheme placeholder (`bearerAuth`) so codegen output handles auth correctly (S-022 fills it in).
estimate: S
adr_refs: [0005]
parity_test: none
refined: true
refined_at: 2026-05-17
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer]
context7_last_checked: 2026-05-17
folds_in: [S-123]
github_issue: 48
---

## Context
Springdoc is the source of truth for the API contract that the SPA's generated TS client (S-004) consumes. Closes R5 structurally — typed enums and DTOs flow from the server.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Add `springdoc-openapi-starter-webmvc-ui` dependency.
- [ ] Document the convention: every controller method gets `@Operation(summary = ..., description = ...)`; every DTO gets `@Schema(description = ...)`.
- [ ] Add a small `OpenApiConfig` `@Configuration` defining the `bearerAuth` security scheme placeholder.
- [ ] Write a smoke test that asserts `/v3/api-docs` returns 200 and includes the hello operation.

## Notes
The spec-publication mechanism (live `/v3/api-docs` for dev vs. committed snapshot for CI reproducibility) is decided here: **both** — live for dev, snapshot committed under `next/web/openapi/` for codegen reproducibility, refreshed by a script.

**Folds in S-123** (rework follow-up from S-001 to lock down springdoc's off-state). S-123 closes when this story merges.

<!-- modernize-refine: start -->

## Design notes

### File layout

| Path | Purpose |
|---|---|
| `next/server/src/main/java/ch/alpenflight/platform/openapi/OpenApiConfig.java` | `@Configuration` + `@Bean OpenAPI` declaring info + servers + `bearerAuth` scheme. Guarded by `@ConditionalOnProperty("springdoc.api-docs.enabled")` so the bean is absent in prod. |
| `next/server/src/main/java/ch/alpenflight/platform/openapi/OpenApiSnapshotMain.java` | Standalone `main` used by Gradle tasks: boots context on random port, fetches `/v3/api-docs`, normalizes (sorted keys, LF, trailing newline), writes / compares. |
| `next/server/src/main/java/ch/alpenflight/platform/hello/HelloResponse.java` | **Promote** the nested `HelloController.HelloResponse` record to a top-level type. springdoc emits `HelloController.HelloResponse` for nested records — codegen tools choke on dotted names. |
| `next/server/src/main/java/ch/alpenflight/platform/hello/HelloController.java` | Add `@Operation` + `@ApiResponse`; reference `HelloResponse` from new top-level location. The `TODO(S-020)` stays. |
| `next/server/src/main/resources/application.yml` | Pin `springdoc.api-docs.version: openapi_3_1`. Keep both `enabled` flags at `false`. Add `springdoc.show-actuator: false` so `/actuator/*` doesn't pollute the codegen surface. |
| `next/server/src/main/resources/application-dev.yml` | Flip both `enabled: true`. |
| `next/server/src/main/resources/application-test.yml` | `api-docs.enabled: true` (smoke test needs it); `swagger-ui.enabled: false`. |
| `next/server/src/main/resources/application-prod.yml` | Explicit `enabled: false` re-pin even though base inherits — defense in depth + legible for an ops reader. |
| `next/server/build.gradle.kts` | Two tasks: `generateOpenApiSnapshot` (refreshes the file) + `compareOpenApiSnapshot` (fails on drift, wired into `check`). |
| `next/web/openapi/openapi.json` | Committed snapshot. New file the first run lands. S-004 codegen reads from here. |
| `next/server/CONVENTIONS.md` | Add an "API documentation (springdoc)" section: annotation discipline + PII rules for `@Schema(example=...)` + the `bearerAuth` placeholder rule. |

No migrations, no domain code, no Security wiring — that's S-020.

### `OpenApiConfig` shape

```java
@Configuration
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
class OpenApiConfig {
    @Bean
    OpenAPI alpenflightOpenAPI(
            @Value("${spring.application.name}") String appName,
            @Value("${alpenflight.openapi.server-url:http://localhost:8080}") String serverUrl) {
        return new OpenAPI()
            .info(new Info()
                .title("AlpenFlight API")
                .version("0.0.1-SNAPSHOT")
                .description("Glider club operations platform. Source of truth for the SPA-generated TS client."))
            .addServersItem(new Server().url(serverUrl))
            .components(new Components().addSecuritySchemes("bearerAuth",
                new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Placeholder; S-020 wires real principal flow.")));
        // No top-level addSecurityItem — no operation references bearerAuth yet.
    }
}
```

- `bearerAuth` name matches ADR 0005 references; future `@SecurityRequirement(name = "bearerAuth")` annotations attach to it textually.
- Server URL env-overridable via `ALPENFLIGHT_OPENAPI_SERVER_URL` for prod-like environments.

### Profile-gated enablement

| Profile | `api-docs.enabled` | `swagger-ui.enabled` | Rationale |
|---|---|---|---|
| base | `false` | `false` | Safe default; no env-flip surface. |
| dev | `true` | `true` | Local visibility + snapshot generation. |
| test | `true` | `false` | Smoke test asserts `/v3/api-docs` 200; UI unused. |
| prod | `false` (explicit) | `false` (explicit) | Re-pin for legibility; lockdown IT proves it. |

Operator opt-in for a prod-like env: set `SPRINGDOC_API_DOCS_ENABLED=true` env var. Documented in `application-prod.yml` as a comment, not encouraged.

### Worked example — HelloController + HelloResponse

```java
// HelloResponse.java — promoted to top-level
@Schema(description = "Smoke-test payload returned by the hello endpoint.")
public record HelloResponse(
    @Schema(description = "Static greeting string.") String message,
    @Schema(description = "Instant the response was constructed (ISO-8601, UTC).") Instant timestamp
) {}
```

```java
// HelloController.java — annotated
@RestController
@Tag(name = "Hello", description = "Liveness-style smoke endpoint; remove or auth-gate before cutover (S-020).")
public class HelloController {

    @Operation(
        summary = "Return a static greeting and the server timestamp.",
        description = "Anonymous smoke endpoint; worked example for OpenAPI annotation conventions.")
    @ApiResponse(responseCode = "200", description = "Greeting payload.")
    @GetMapping("/api/v1/hello")
    public HelloResponse hello() {
        return new HelloResponse("Hello AlpenFlight", Instant.now());
    }
}
```

Boyscout: flip the message string from `"Hello FLS"` → `"Hello AlpenFlight"` for brand consistency (post-rebrand).

### Snapshot publication

**Gradle tasks** (added to `next/server/build.gradle.kts`):

```kotlin
val generateOpenApiSnapshot by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Refresh next/web/openapi/openapi.json from the live spec."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "ch.alpenflight.platform.openapi.OpenApiSnapshotMain"
    args = listOf("--write", rootProject.projectDir.resolve("../web/openapi/openapi.json").absolutePath)
    systemProperty("spring.profiles.active", "dev")
    systemProperty("server.port", "0")
}

val compareOpenApiSnapshot by tasks.registering(JavaExec::class) {
    /* same shape; --compare instead of --write; non-zero exit on diff */
}
tasks.named("check") { dependsOn(compareOpenApiSnapshot) }
```

`OpenApiSnapshotMain` reuses `SpringApplication.run(AlpenflightApplication.class, ...)`, resolves the random port from `Environment.getProperty("local.server.port")`, fetches `/v3/api-docs` via the JDK 25 `java.net.http.HttpClient`, normalizes the JSON tree (Jackson; sorted keys, LF, trailing newline; strip volatile `info.version` if it embeds a timestamp), and writes or compares.

CI runs `./gradlew check` → `compareOpenApiSnapshot` fails the build when the committed snapshot drifts from live. Failure message must say `run ./gradlew generateOpenApiSnapshot to refresh`.

### Convention discipline (review-time, not enforced by ArchUnit)

Per ADR 0022 directive 1: don't enforce annotation rules with ArchUnit for a 1-controller surface. The snapshot diff IS the enforcement — a missed `@Operation` shows up as `summary: null` in the committed `openapi.json`, visible in PR review. Revisit if controllers grow past ~5 and reviewers start missing missed annotations.

`CONVENTIONS.md` additions:
- Every controller method: `@Operation(summary, description)`.
- Every public DTO record/class: `@Schema(description)` on the type and on each non-self-explanatory field.
- Every non-200 response: `@ApiResponse(responseCode, description)`.
- Auth-gated endpoints (post-S-020): `@SecurityRequirement(name = "bearerAuth")` on the method.
- `bearerAuth` is a placeholder until S-020 — do NOT attach `@SecurityRequirement` to any operation until the controller actually `@PreAuthorize`s.
- Every DTO field gets Jakarta validation (`@NotNull`, `@Size`, `@Pattern`, `@Min`, `@Max`) — these flow into the spec and become client-side constraints.
- `@Schema(example = …)` MUST be synthetic and obviously fake (no realistic Swiss names, emails, phones, licence numbers, or club fixtures).
- No `clubId` in tenant-scoped request bodies/paths (per ADR 0008 — `@TenantId` resolves it from the principal).

### Alternatives considered

- **Chosen: review discipline + CI snapshot diff** for convention enforcement. Rejected ArchUnit (gold-plate for 1 controller). Rejected Spring REST Docs (redundant with springdoc).
- **Chosen: snapshot committed + live `/v3/api-docs`** (both modes). Rejected live-only (breaks S-004 reproducible codegen). Rejected snapshot-only (loses Swagger UI in dev — half the value).
- **Chosen: springdoc-openapi 2.8.17 with Boot 4.0.6.** Risk: springdoc 2.x targets Boot 3.x in docs. Dep already resolves at S-001 (build green). Test 1 (`OpenApiDocsReturns200_Dev`) is the canary — 404 signals autoconfig path broken. Fallback: `springdoc-openapi-starter-webmvc-api` (no UI) or pin springdoc 2.7.x.
- **Chosen: keep dep + prod-profile off-state regression test** (S-123 option 2). Rejected option 1 (remove + re-add): the dep is in place; removing for one story-boundary is needless churn.
- **Chosen: OpenAPI 3.1.** `springdoc.api-docs.version: openapi_3_1` set explicitly. Default is 3.0. Rationale: hey-api / orval produce cleaner TS for 3.1 (especially discriminated unions for `FlightAircraftType`, `FlightProcessState` later). Pin now to avoid churn.
- **Chosen: hand-rolled Gradle `JavaExec` snapshot task.** Rejected `springdoc-openapi-gradle-plugin` — 3rd-party transitive deps for ~30 lines of Kotlin; the plugin does the same boot-and-curl internally.

### Per ADR 0022 directive 2

Zero migration / schema touch. OpenAPI lives entirely above persistence. If the implementer feels a temptation to add a `springdoc_*` migration or persist the snapshot in a table — stop and re-read this section.

## Edge cases & hidden requirements

- **OpenAPI 3.1, not 3.0.** Default is `openapi_3_0`. Must explicitly set `springdoc.api-docs.version: openapi_3_1` in base `application.yml`. Without it, the spec is 3.0 and codegen tools that distinguish 3.0 `nullable: true` from 3.1 `type: ["string", "null"]` produce incorrect TS.
- **`springdoc.show-actuator: false`** must be set; default includes `/actuator/health` + `/actuator/info` in the spec, polluting the S-004 codegen surface with non-API methods.
- **Nested record schema names.** springdoc emits `HelloController.HelloResponse` for the current nested record — codegen tools choke on dotted identifiers. **Promote `HelloResponse` to a top-level class in this story** (cheap pre-S-004 fix).
- **`HelloResponse` is unannotated.** Without `@Schema(description = ...)` on the type and `@Schema(format = "date-time")` on the `Instant` field, generated docs are anaemic. The worked-example is the convention seed for every future DTO.
- **`bearerAuth` placeholder applied globally would force codegen to send Authorization on the anonymous hello endpoint.** Declare in `Components.securitySchemes` only — no global `addSecurityItem` and no per-operation `@SecurityRequirement` until S-020.
- **Spring Boot 4 + springdoc 2.x autoconfig.** Boot 4 restructured autoconfiguration packages (`@WebMvcTest` moved). springdoc 2.8.17 was added at S-001 without runtime validation — if it doesn't pick up the new format, `/v3/api-docs` returns 404 even when enabled. Test 1 (`OpenApiDocsReturns200_Dev`) is the canary; fallback is `springdoc-openapi-starter-webmvc-api` (no UI).
- **`@WebMvcTest` slice + springdoc.** springdoc auto-config is `@ConditionalOnWebApplication`, included in `@WebMvcTest`. If a test gets 404, add `@ImportAutoConfiguration(SpringDocConfiguration.class)` explicitly.
- **Swagger UI bookmarkable path.** Default is `/swagger-ui.html` redirecting to `/swagger-ui/index.html`. If "reachable in dev" means `http://localhost:8080/swagger-ui`, set `springdoc.swagger-ui.path: /swagger-ui` explicitly — without it, the bare `/swagger-ui` URL is 404.
- **Snapshot file location** is `next/web/openapi/openapi.json` — directory does not exist yet; the Gradle task creates it.
- **Snapshot stale + S-004 generates against stale spec.** Drift between live and snapshot must fail CI (`compareOpenApiSnapshot` wired into `check`). Failure message names the fix command.
- **JSON-node comparison, not string diff.** Whitespace + key-ordering churn → false-positive failures. Use Jackson `JsonNode` structural equality. Strip volatile fields (e.g. `info.version` if it embeds a build timestamp).
- **Boot version metadata.** `BuildProperties` requires `springBoot { buildInfo() }` in `build.gradle.kts`. Not in scope here — `OpenApiConfig` defaults `version` to a literal `"0.0.1-SNAPSHOT"`. Wire `buildInfo()` when versioning matters (S-041 / deploy story).
- **`server.error.*` suppression masks `ProblemDetail` shape.** Base profile suppresses error detail; without explicit `@ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))` on each non-200 path, codegen emits no typed error response. Worked-example shows just 200 for now; document the pattern in CONVENTIONS.md as a forward-looking rule.
- **`server.port` collision.** AlpenFlight backend default is 8080. Keycloak (post-S-039) is on 8090. `OpenApiConfig` server URL defaults to `http://localhost:8080` — correct, but document the env override `ALPENFLIGHT_OPENAPI_SERVER_URL` for prod-like environments.
- **NullAway interaction with `@Schema` fields.** Optional DTO fields need explicit JSpecify `@Nullable`. The convention "every optional field is `@Nullable`; required fields are non-null by default" goes into CONVENTIONS.md so the first domain DTO doesn't hit an unexpected NullAway compile error.
- **CORS for `/v3/api-docs` from dev SPA origin.** S-002 has the SPA on a different dev port. The SPA does not fetch the live spec at runtime (codegen runs at build time against the snapshot), so no CORS exposure is needed in S-003. Out of scope; revisit if a dev tool ever wants direct browser access.
- **Relaxed binding env-var attack surface.** `SPRINGDOC_API_DOCS_ENABLED=true` flips it on. Lockdown IT (test 6) runs with a scrubbed env; document the env-var matrix in `next/server/README.md`.
- **Snapshot generation must NOT run under prod profile.** A prod-profile snapshot run writes an empty/404 body. The Gradle task pins `spring.profiles.active=dev` explicitly via `systemProperty`.
- **Path versioning.** `/api/v1/` is in route annotations (`@GetMapping("/api/v1/hello")`), not a global prefix. springdoc shows it verbatim. Don't add `springdoc.paths-to-match` filtering until a non-`/api/**` controller appears (none today; YAGNI).

## Security plan

### Threat model

| Risk | Severity | Mitigation in S-003 |
|---|---|---|
| API-shape leak via swagger-ui in prod (operator discovers `/swagger-ui/index.html`, maps the attack surface). | Medium | Base `application.yml` keeps `springdoc.swagger-ui.enabled: false`; `application-dev.yml` flips it on; `application-prod.yml` explicit re-pin to `false`. Lockdown IT (test 6) regression-locks. |
| API-shape leak via `/v3/api-docs` in prod (machine-readable spec → endpoint enumeration). | Medium | Same dormant-by-default pattern + explicit prod re-pin + lockdown IT. |
| Sensitive paths/params/examples leaking secrets via `@Schema(example=...)`. | Medium | CONVENTIONS.md rule: synthetic examples only (no real Swiss names, emails, phones, licence numbers, club fixtures). Optional CI grep guard (warning, not blocker). |
| Stale snapshot under `next/web/openapi/` drifts from live; S-004 codegen produces a client whose auth requirements don't match runtime. | Medium | `compareOpenApiSnapshot` wired into `check`. Drift = build failure. |
| Confused-deputy: `bearerAuth` advertised globally but no operation `@PreAuthorize`s. Spec says "auth required"; controller accepts anonymous. | Medium | Declare scheme in `Components` only; do NOT attach `@SecurityRequirement` until S-020. Documented in CONVENTIONS.md. |
| Stray env var flips it on in prod (`SPRINGDOC_API_DOCS_ENABLED=true` via Spring relaxed binding). | Medium | Lockdown IT runs under prod profile + scrubbed env; documents env-var matrix in `next/server/README.md`. |
| CORS misconfiguration if swagger-ui ever turned on in a prod-like environment. | Low (today) | Out of scope for S-003; documented as a constraint for a hypothetical "prod-on" — would require IP allowlist + bearer auth on `/swagger-ui/**` + `/v3/api-docs`. |

### Authorization

springdoc does not gate the endpoints — Spring Security does. Today there is no Spring Security on the classpath (S-020). Matrix:

- **Dev:** open (no auth — loopback convenience).
- **Test:** open (`@WebMvcTest` invokes controllers directly).
- **Prod:** disabled (404, not 401 — a 401 confirms the endpoint exists).

The hello endpoint stays unauthenticated in S-003 (out of scope to change — that's S-020).

### Input validation

N/A for the spec endpoint itself (static-ish JSON, no input). Forward-looking: springdoc transcribes Jakarta Bean Validation onto DTO field schemas (`required`, `minLength`, `pattern`, etc.) — codegen surfaces them as client-side constraints. The CONVENTIONS.md rule "every DTO field gets Jakarta validation" makes this the end-to-end input-validation contract.

### PII handling

- `@Schema(example=...)` is the only PII surface in S-003. Convention pinned in CONVENTIONS.md: synthetic placeholders only (`"example@example.test"`, `"+41 00 000 00 00"`, `"CH-XX-LICENCE-PLACEHOLDER"`). Hello endpoint response has no PII.
- The committed snapshot (`next/web/openapi/openapi.json`) is a public artifact (repo + post-squash history). Treat it as if it ships to customers.

### Audit-log events

N/A — no mutation in this story. S-027 audit-log infrastructure is unaffected.

### OWASP applicability

- **A01 Broken Access Control** — applies. Mitigation: profile-gated disable + lockdown IT.
- **A04 Insecure Design** — applies. Mitigation: dormant-by-default IS the design control.
- **A05 Security Misconfiguration** — **dominant risk surface.** Stray env vars flip via relaxed binding. Mitigation: lockdown IT + env-var matrix doc.
- **A06 Vulnerable & Outdated Components** — low. springdoc pinned to 2.8.17; Renovate / Dependabot covers cadence.
- **A07 Identification & Authentication Failures** — `bearerAuth` is a placeholder until S-020. Confused-deputy avoided by Components-only declaration.
- **A08 Software & Data Integrity Failures** — low. Snapshot-drift CI check covers spec/client integrity.

### CI / pre-commit guards

- **Lockdown regression IT** (folds in S-123): prod-profile + scrubbed env asserts `/v3/api-docs` and `/swagger-ui/index.html` both 404.
- **Snapshot-drift detection:** `compareOpenApiSnapshot` wired into `check`.
- **Lint / grep guard (nice-to-have, not blocker):** ripgrep over `next/server/src/main/java` for `@Schema(...example...)` matching email regex / `+41` / known legacy fixture names. Warning, not fail.

## Test plan

### Pyramid
- Unit: 0 — `OpenApiConfig` is wiring, not logic.
- Integration: 6 — five `@WebMvcTest` slices (dev) + one `@SpringBootTest` (prod lockdown).
- Snapshot drift: 1 — `@SpringBootTest` boots app, fetches live spec, compares to committed file.
- E2E: 0 — swagger-ui reachability is dev convenience, not a user flow.
- Parity: 0 — `parity_test: none`; spec itself is the artifact.

### Tests

All under `ch.alpenflight.platform.openapi` (new package).

1. **`OpenApiDocsReturns200_Dev`** — `@WebMvcTest(HelloController.class)` + `@TestPropertySource("springdoc.api-docs.enabled=true")`. Asserts `GET /v3/api-docs` → 200, `Content-Type: application/json`. Canary for Boot 4 + springdoc 2.x autoconfig compat.

2. **`OpenApiSpecIsOpenApi31_Dev`** — same slice; asserts `$.openapi` starts with `"3.1"`. Catches a silent downgrade to 3.0.

3. **`OpenApiSpecContainsHelloOperation_Dev`** — same slice; asserts `$.paths['/api/v1/hello'].get` and `$.paths['/api/v1/hello'].get.responses['200']` exist. Does NOT assert exact `$ref` name (avoid codifying DTO-rename brittleness).

4. **`OpenApiSpecContainsBearerAuthScheme_Dev`** — same slice; asserts `$.components.securitySchemes.bearerAuth.{type, scheme, bearerFormat}` = `{"http", "bearer", "JWT"}`. Zero-tolerance — a missing field is a silent S-004 codegen regression.

5. **`SwaggerUiReturns200_Dev`** — `@WebMvcTest` + both properties enabled; asserts `GET /swagger-ui/index.html` → 200.

6. **`OpenApiAndSwaggerUiBothReturn404_ProdProfile`** (folds S-123) — `@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "spring.profiles.active=prod")` + `TestRestTemplate` + `SharedPostgresContainer` guard (mirrors `ActuatorHealthIT`). Asserts both endpoints 404. Test must run with no `SPRINGDOC_*` env vars — document in test's Javadoc.

7. **`OpenApiSnapshotMatchesLive`** — `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@ActiveProfiles("test")` (api-docs enabled). Fetches live `/v3/api-docs`, parses as `JsonNode`, compares to committed `next/web/openapi/openapi.json`. Strip volatile `info.version` before compare. Failure message says `run ./gradlew generateOpenApiSnapshot to refresh`.

### Fixtures

- `@TestPropertySource` inline on the slice tests — overrides base off-state without a separate file.
- `SharedPostgresContainer` per-class on tests 6 + 7 (mirrors `ActuatorHealthIT` pattern).
- `next/web/openapi/openapi.json` — committed once after first successful implementation run via `./gradlew generateOpenApiSnapshot`.

### Snapshot management policy

**Chosen path: snapshot-stale fails the build.** Developer runs `./gradlew generateOpenApiSnapshot` after a controller / DTO change and commits the updated file. PR that touches a controller but omits the snapshot update is caught by test 7 in CI.

Rejected alternative: auto-regenerate in CI + commit back. Requires CI write permissions, introduces races on parallel PR builds, hides the "did I update the client contract?" question the developer should answer consciously.

### Coverage gaps (deferred)

- Security scheme actually enforces auth on protected endpoints → S-020.
- Codegen output quality (TS types, discriminated unions, `FlightProcessState` enum) → S-004.
- Multi-tenant spec correctness (no `clubId` in paths/bodies) → first tenant-scoped endpoint (S-049 area).
- Proffix `/api/v1/deliveries/*` shape parity → S-080.

### Risks

- springdoc 2.8.17 + Boot 4.0.6 autoconfig path may be broken (springdoc 2.x docs target Boot 3.x). Test 1 is the canary. Fallback documented.
- `@WebMvcTest` slice may exclude springdoc beans. Mitigation: `@ImportAutoConfiguration(SpringDocConfiguration.class)` if needed.
- Snapshot JSON-node comparison may need additional volatile-field exclusions if springdoc emits future `x-generated-at` extensions. Extend the exclusion list — do NOT add `.gitattributes` diff drivers.

## Performance plan

(N/A — story has no performance signal: no DB queries, no hot paths, no indexes, no large data. The `/v3/api-docs` endpoint is dormant in prod and served from a Spring-cached spec object in dev. springdoc has internal caches for its scrape; we touch neither.)

<!-- modernize-refine: end -->
