---
id: S-183
title: Migration-bundle — full mapper coverage + parity oracle harness
epic: E-02
status: todo
depends_on: [S-016]
integration_base: integration/migration
origin: scope-split
origin_story: S-016
refined: true
refined_at: 2026-05-29
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer, performance-engineer]
context7_last_checked: 2026-05-29
github_issue: 167
acceptance:
  - All remaining ~59 mappers across `identity/`, `flight/`, `accounting/` sub-packages — bidirectional `writeNdjson` (export-side, JDBC `ResultSet` → Jackson `JsonGenerator`) + `readEntity` (ingest-side, Jackson `JsonNode` → `PreparedStatement`) per the contract pinned in S-016's `Mapper` interface.
  - `Manifest` typed class — per-entity port-policy, per-entity tombstone-policy, per-FK tenant-bypass flag, `schema_version`, per-table column allow-list. Jackson-serialized JSON on the wire; consumed by both S-139 and S-141.
  - `legacy_id_map_<entity>` COPY-writer byte format owned by this module + a `LegacyIdMapWriter` helper that S-141 wires `PgConnection.getCopyAPI()` to via the `OutputStream` interface pinned in S-016's design notes.
  - Two-tier reference-data resolution: SYSTEM_GLOBAL refs (`country`, `language`, `start_type`, `role`, `club_state`, unit types, `extension_type`, `accounting_rule_filter_type`, `accounting_unit_type`) resolve via `legacy_int_id`; TENANT_SCOPED refs port from bundle via per-bundle map.
  - Audit-log mapper produces rows with `actor_kind='LEGACY_MIGRATED'` + `legacy_actor_user_id` text + NULL `actor_keycloak_sub`. Orphan actor refs → synthetic `legacy_orphan_actor_id` UUID v7 + warning into `migration_run.warnings`.
  - Unmapped-table registry — `LanguageTranslation`, `PersonFlightTimeCredit*`, `Setting`, `SystemData`, `SystemLog`, `SystemVersion`, `UserAccountState`, `PersonPersonCategory` each carry a manifest "WHY not mapped" entry. Parity oracle gates on every legacy table being mapped or explicitly unmapped.
  - **Parity oracle harness.** Testcontainers MSSQL 2022 (per-class reuse) seeded by `LegacyFixtureSeeder` (Faker-only, deterministic seed) + Testcontainers Postgres 17. Round-trip via in-process call to S-139's writer (subprocess via `:migration-tool:shadowJar` once that lands; in-process producer-library call meanwhile) → S-141 ingest → diff. Reports under `build/reports/parity/<run-id>/{summary.json, report.md, deltas/*.json}`. CI asserts `summary.json.passed && totalDeltas==0 && fkOrphans==0`. Gated `@Tag("parity")`, excluded from `./gradlew test`, runs in a dedicated CI job (PR-gated on `migration-bundle/**` + `flsserver/database/**` paths; nightly at 10× scale on `main`).
  - Row-count diff: exact per (Club, table). FK-integrity: post-commit `LEFT JOIN parent WHERE parent.id IS NULL` over every Hibernate-declared FK; orphan count must be zero. Sampled-value: 1% sample SIZE (`TABLESAMPLE BERNOULLI(1)` MSSQL-side); zero tolerance on sentinel columns (every FK + status enum + monetary + timestamp + generated column). Free-text + denormalized caches excluded via per-mapper `@ParitySentinel` / `@ParityIgnore` annotations.
  - Soft-delete invariant: per soft-deletable entity, `count(legacy.IsDeleted=1) == count(new.deleted_on IS NOT NULL)` per Club. Hard fail if tombstones lost.
  - `MapperVsSchemaCompatibilityTest` — introspects Hibernate metadata + each mapper's `COLUMNS[]`; fails CI if a mapper writes a column the live schema no longer has, or omits a non-nullable column without a default.
  - **ArchUnit rules** in this module: no dependency on `alpenflight.server.person.PersonRepository` or any `@Repository` from `alpenflight.server.*`; no `Files.createTempFile` / `File.createTempFile` / `FileOutputStream`; no `Statement.executeQuery(String)` or `createNativeQuery` with string concatenation.
  - **JMH microbench** on the FlightCrew mapper — 1M synthetic rows through `map(JsonNode, PreparedStatement)`. Pass: ≥ 200K rows/sec single-thread; ≤ 50 MB allocation/sec (`-prof gc`). Regression threshold: -20% throughput OR +50% alloc-rate vs baseline.
  - **CI workflow wiring** — `.github/workflows/ci.yml` builds the `alpenflight/migration-bundle/` module on every PR that touches `alpenflight/migration-bundle/**`. Parity job is the separate path-filtered workflow above.
  - **S-027 + S-024 cross-story hand-offs land** — S-027's test plan adds read-back coverage for the `LEGACY_MIGRATED` actor_kind variant; S-024's cross-tenant leakage CI exemption list adds Person + audit_log + system tables.
estimate: L
adr_refs: [0002, 0003, 0008, 0019, 0022, 0023]
parity_test: tests/migration/schema-parity.spec.ts (new)
---

## Context

Scope-split from [S-016](implemented/S-016-data-migration-script.md). S-016 shipped the walking skeleton (Gradle module + `Mapper` interface + `EntityType` + `LegacyIdMapTables` + `Coercions` + one concrete `CountryMapper`). This story fills in the remaining ~59 mappers, the parity oracle harness, ArchUnit rules, the JMH bench, and the CI workflow wiring.

All the load-bearing design decisions live in S-016's refinement — see the `<!-- modernize-refine: start --> / end -->` block of the implemented story body. This story is implementation-only; no separate refinement pass is required unless a fork surfaces mid-build.

## Cross-story contracts

- **Consumes:** S-016's `Mapper` interface + `EntityType` + `LegacyIdMapTables` + `Coercions` skeleton; ADR 0018 aggregate boundaries; ADR 0019 UUID v7 strategy.
- **Produces:** the full mapper coverage that **S-141** (encrypted-bundle ingest pipeline) and **S-139** (legacy-export JAR) both depend on at runtime. Once this lands, S-141 implement is unblocked.
- **Hand-offs:** S-027 (audit infra) test plan must add `LEGACY_MIGRATED` read-back coverage. S-024 (cross-tenant leakage CI) exemption list must add Person + audit_log + system tables.

## Tasks

- [ ] Manifest typed class + Jackson wiring.
- [ ] ~59 mappers + their column lists + coercion tables + FK declarations.
- [ ] `LegacyIdMapWriter` (COPY byte format).
- [ ] SYSTEM_GLOBAL ref resolver via `legacy_int_id`.
- [ ] Audit-log mapper with `LEGACY_MIGRATED` discriminator + orphan-actor synthesis.
- [ ] Unmapped-table registry + manifest-coverage gate.
- [ ] Parity oracle harness (MSSQL + Postgres Testcontainers + `LegacyFixtureSeeder` + diff + reporter).
- [ ] ArchUnit rules.
- [ ] `MapperVsSchemaCompatibilityTest`.
- [ ] JMH bench on `FlightCrewMapper`.
- [ ] CI workflow wiring for the new module + parity job.
- [ ] S-141 + S-139 contract verification (in-process producer-library call until S-139 JAR lands).

## Notes

- Estimate is `L` (full scope of S-016's deferred work).
- S-141's implement is blocked on this story.
- A new V17+ Flyway migration may be needed if `MapperVsSchemaCompatibilityTest` surfaces a destination-schema gap — file as a sibling under this story. (V1–V16 already exist; refinement confirmed every ported entity has a `t_*` destination.)

<!-- modernize-refine: start -->

## Design notes

Deltas on top of [S-016's refinement block](implemented/S-016-data-migration-script.md). Load-bearing decisions there are inherited, not re-derived.

- **`Mapper` contract completion.** `writeNdjson(ResultSet, JsonGenerator)` (export, called once per JDBC row, streams Jackson field-tokens — no intermediate `Map`/DTO) + `readEntity(JsonNode, PreparedStatement)` (ingest, once per parsed row, positional `setX` driven by `columns()` order). Both methods must be allocation-free in the hot path beyond Jackson + JDBC inherent; discipline gated by JMH (AC12), not ArchUnit.
- **`Manifest` shape.** Single Jackson record at `ch.alpenflight.migration.bundle.Manifest`: `schemaVersion:int`, `Map<EntityType, EntityPolicy>` (port-policy + tombstone-policy + `Set<String> tenantBypassFks` + `List<String> columnAllowList`), `Map<EntityType, String> unmappedReason`. Bundle-open validates every `EntityType.values()` is either policy-mapped or in `unmappedReason` (AC6 coverage gate).
- **`LegacyIdMapWriter` byte format.** Postgres COPY **binary** format, two-column `(uuid, uuid)`. Binary chosen over text to avoid per-row text parsing at >1M-row scale. S-141 wires `getCopyAPI().copyIn("COPY ... FROM STDIN BINARY", os)`; library never sees the `Connection`.
- **Parity harness.** `@Testcontainers` + `static @Container MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense()` + `static @Container PostgreSQLContainer<>("postgres:17-alpine")` for per-class reuse. `LegacyFixtureSeeder` (Faker, deterministic seed via sysprop `parity.seed`) → in-process producer call to `Mapper.writeNdjson` → in-memory `tar.gz` → consumer-side `readEntity` → diff via reflection over `Mapper.columns()` + schema dump from `MapperVsSchemaCompatibilityTest`. Reports `build/reports/parity/<run-id>/{summary.json, report.md, deltas/*.json}`. `@Tag("parity")`, excluded from `./gradlew test`.
- **In-process producer is the temporary affordance.** Until `:migration-tool:shadowJar` (S-139) lands, harness wires producer side directly. File sibling task referenced from S-139's done-criteria to swap to `ProcessBuilder` invocation once the JAR lands.
- **ArchUnit rules.** One `ArchitectureTest` class with `@AnalyzeClasses(packages = "ch.alpenflight.migration.bundle")` + four `@ArchTest` `ArchRule` fields: the three from AC11 plus S-016's deferred `EntityType` ingest-order invariant (FK-target ordinal < FK-source ordinal, walked via `Mapper.foreignKeys()`).
- **JMH bench.** `me.champeau.jmh` 0.7.3, `src/jmh/java/` source set, one benchmark on `FlightCrewMapper.readEntity`. `fork=1`, `profilers=['gc']`, `resultFormat='JSON'`. Baseline committed at `migration-bundle/jmh/baseline.json`; `jmhCompareBaseline` Gradle task fails on AC12 thresholds. CI flake mitigation: run twice on regression, fail only if both runs exceed the threshold.
- **`MapperVsSchemaCompatibilityTest` lives in `alpenflight/server/`**, not in migration-bundle. Rationale: bundle has no Spring/Hibernate runtime dep (CLAUDE.md cross-cutting); server already boots Hibernate for `generateOpenApiSnapshot`, piggy-back its test context. Asserts `bundleMapper.columns() ⊆ hibernateTable.columns()` + every non-nullable non-defaulted column is in `columns()`. Skips `@Generated`, `legacy_int_id` shadow columns, `@TenantId` discriminator (auto-populated).
- **Audit-actor orphan synthesis.** One synthetic `legacy_orphan_actor_id` UUID v7 per **distinct** `legacy_actor_user_id` string per bundle (bundle-local caching map) — preserves "all rows by this orphan actor" forensic query, vs per-occurrence which would scatter it. Orphan UUIDs are never persisted to a cross-bundle lookup; NULL actor stays NULL.
- **FlightAirState legacy data is dropped.** V13 dropped the destination (air state computed not stored per ADR 0022 D2). The Flight mapper translates legacy `AirStateId == FlightPlanOpen` → `t_flight.flight_plan_opened_on` timestamp; all other legacy air-state values are dropped.
- **CI wiring.** New `migration-bundle-build` job in `.github/workflows/ci.yml` path-filtered on `alpenflight/migration-bundle/**`. Separate `migration-bundle-parity` workflow file path-filtered on `migration-bundle/**` + `flsserver/database/**`, normal scale on PRs, nightly 10× on `main` (`parity.scale=10` sysprop). JMH gate path-filtered on `FlightCrewMapper.java` + `Mapper.java` + `Coercions.java` — only files whose change can move the bench.
- **Schema deviation per ADR 0022 D2.** None. All AC are mapper coverage + harness; no CHECK constraints, no generated columns, no triggers introduced. V17+ may spawn only if `MapperVsSchemaCompatibilityTest` surfaces a destination-table gap.

## Edge cases & hidden requirements

- **Manifest entity declared, mapper missing in this build:** fail-fast pre-COPY with entity name + `schemaVersion`; no partial commit (single-txn per S-141 AC4).
- **Mapper present, entity absent from bundle:** legitimate per port-policy. Distinguish "absent" from "present with zero rows" so soft-delete invariant isn't divided by zero.
- **`schemaVersion` mismatch:** hard reject at manifest parse; distinct error codes `BUNDLE_SCHEMA_UPGRADE_NEEDED` / `BUNDLE_SCHEMA_DOWNGRADE_NEEDED`. Private key wiped per S-141 AC6.
- **In-bundle FK target missing:** hard fail — bundle is corrupt. Cross-tenant FKs (Person.PrimaryClubId-class) are the only legit "outside the bundle" case and must be marked `tenantBypassFks` in the manifest.
- **Legacy `IsDeleted=1` row whose FK parent is hard-deleted:** port as-is; soft-delete invariant counts per-entity, not transitively.
- **Audit-actor orphan repeated N×:** single UUID v7 per distinct legacy actor string per bundle (forensic-preserving); NULL actor stays NULL; orphan actor that ALSO appears as a real `User` row in this bundle resolves to the real `User` UUID (cache populated User-first per `EntityType` ordering).
- **Cross-bundle orphan-actor + Person reuse:** out of scope — same exclusion as S-016's cross-bundle Person dedupe. Manual merge via S-051 lookup if it ever matters.
- **Unmapped legacy tables not in the AC6 registry:** harness fails with `"register a mapper or add to unmapped-registry: <Table>"` — catches "new legacy DBUpdate added a table" drift.
- **MSSQL `datetime2(7)` → Postgres `timestamptz` precision shift:** sentinel comparison normalises to microseconds on both sides; `LegacyFixtureSeeder` pins seed timestamps to UTC offset 00:00 to remove TZ ambiguity.
- **Monetary columns:** legacy `decimal(18,4)`; compare via `numeric` exact equality, not float tolerance — every monetary column is a sentinel.
- **`TABLESAMPLE BERNOULLI(1)` determinism:** requires MSSQL `REPEATABLE(<seed>)` clause; seed pinned in `LegacyFixtureSeeder` and reused for the sample call.
- **`fkOrphans==0` for cross-tenant FKs:** FK sweep scoped to `@TenantId`-bearing FKs **plus** explicit cross-tenant set (Person.PrimaryClubId, Aircraft.ManagingClubId, Location.HomeClubId); the latter walk through the per-bundle Person sub-map.
- **`MapperVsSchemaCompatibilityTest` exclusions:** skip `@Generated`, `legacy_int_id` shadow columns (S-012/013/014), `@TenantId` auto-populated discriminator. Test reads live Hibernate metadata via the server's existing test context — no committed schema.json (V17+ would invalidate it).
- **JMH baseline refresh:** PR commits the new `baseline.json` with rationale in the commit body; reviewed like any code change. No GitHub-artifact cache.
- **Path-filter on integration-branch merges:** a merge touching `migration-bundle/**` + `flsserver/database/**` runs the parity job once (OR-semantics) — confirm `paths-ignore` is not set in tandem.
- **Nightly 10× failure:** opens an issue, does not block `main` (it runs *on* main). PR-gated job stays 1× for cycle-time.
- **Bundle-size upper bound:** S-141 AC1 caps body at 2 GB. At 200K rows/sec × ~125 s = single-txn ingest fits inside `idle_in_transaction_session_timeout` only if S-141 raises that timeout for the ingest connection — flag for S-141 implement.
- **Cancellation contract:** S-141 status states don't enumerate `cancelled`; producer-library entry point honours thread interrupt as defence-in-depth, but no public cancellation token API today.

## Security plan

- **Bulk-ingest tenant validation.** Pre-validate every legacy `ClubId` against the manifest's declared Club set at bundle-open; mismatch fails closed for the whole transaction before any insert. The per-FK `tenantBypassFks` flag is the only escape hatch, valid only for cross-tenant entities (Person, Aircraft, Location per ADR 0008) — the S-024 exemption list (AC14 hand-off) covers exactly those tables.
- **Per-bundle Person sub-map isolation.** Bundle-local resolution only — `legacy_id_map_person` is `ON COMMIT DROP` (S-016 ref + S-141 lifecycle); cross-bundle Person dedupe is explicitly out of scope. No cross-bundle UUID collision possible.
- **Audit-actor orphan UUIDs are bundle-local.** Re-minted UUID v7 per ingest, never persisted to a cross-bundle lookup. Audit row carries both the synthetic UUID and the original `legacy_actor_user_id` text for forensic traceability; S-027's `LEGACY_MIGRATED` read-back coverage asserts both columns present.
- **PII in `migration_run.warnings`.** `legacy_actor_user_id` text is identity-attributable PII. The `warnings` jsonb column is `@AuditRedact` (default-deny per S-027); audit-log snapshots surface column name + warning count only. Raw values stay in jsonb (sysadmin tooling only). S-027's `AuditPayloadTurboFilter` blocks logback-side leakage.
- **No plaintext bundle bytes at rest.** Structural enforcement is AC11's ArchUnit rule banning `Files.createTempFile` / `File.createTempFile` / `FileOutputStream` inside `migration-bundle/`. `readEntity` operates on in-memory `JsonNode`; COPY-writer `OutputStream` is wired by S-141 directly to `getCopyAPI()`. Runtime smoke test in the parity harness: plant a unique marker in a synthetic bundle, assert it never appears under `java.io.tmpdir` after ingest.
- **Ingest authz lives in S-141.** Library has no HTTP surface; caller supplies a tenant-scoped `Connection` + `JsonNode` + `PreparedStatement`. `MapperVsSchemaCompatibilityTest` is defence-in-depth against misconfigured S-141 wiring leaking columns into the wrong destination table.
- **Legacy GUID preservation (ADR 0019).** Accepted forensic-attribution surface: anyone with legacy DB access enumerates new UUIDs. Threat boundary is the legacy DB itself, not the ID format; no audit-search privacy gained by regeneration; parity would be impossible otherwise.

## Test plan

- **Pyramid.** Unit mapper-contract suite (per-mapper, no containers) + ArchUnit (one class in bundle, four `@ArchTest` fields) + `MapperVsSchemaCompatibilityTest` (in `alpenflight/server/` — needs live Hibernate `Metadata`) + parity oracle (in bundle, `@Tag("parity")`, excluded from `./gradlew test` via `useJUnitPlatform { excludeTags("parity") }`) + JMH (separate `jmh` source set, opt-in task only).
- **Mapper contract.** `AbstractMapperContractTest<E, M>` parameterised on mapper + Faker row factory; subclass overrides two methods. Asserts: `columns()` defensive copy, round-trip `writeNdjson → readEntity` row-identical modulo `@ParityIgnore`, FK declarations resolve to entities earlier in `EntityType.INSERT_ORDER`. Port S-016's `CountryMapperTest` onto the contract as task #1 so the other ~58 are derivative.
- **Parity oracle cases.** Happy: 2-Club FLSTest-shape bundle → zero deltas, zero FK orphans, `summary.json.passed=true`. Edge: empty legacy table; manifest declares entity but bundle omits it; `schemaVersion` mismatch (rejects pre-COPY); soft-deleted row whose FK parent was hard-purged. Error: corrupt `LegacyIdMapWriter` stream → COPY raises, txn aborts atomically; orphan actor → `migration_run.warnings` row + `LEGACY_MIGRATED` audit row land; legacy table with no mapper and no unmapped-registry entry → harness fails with explicit "register or unmap" diagnostic.
- **Parity strategy.** Row counts exact per (Club, table). FK integrity via reflective walk of Hibernate-declared FKs; cross-tenant FKs walk via the per-bundle sub-map. Sampled values: MSSQL-side `TABLESAMPLE BERNOULLI(1) REPEATABLE(<seed>)`, zero tolerance on sentinel columns (FK + status enum + monetary + timestamp + generated). `@ParitySentinel` / `@ParityIgnore` opt columns in/out. Soft-delete invariant per soft-deletable entity per Club — hard fail on divergence.
- **Fixtures.** `LegacyFixtureSeeder` Faker-only, seed from sysprop `parity.seed` default `42`, targets post-final-DBUpdate FLSTest schema. ≥ 2 Clubs mandatory (exercises per-bundle Person sub-map). Audit fixture must include three actor shapes: real actor, orphan actor (`AuditLogs.UserId` with no `Users.Id` match), NULL actor. MSSQL container per-class reuse; Postgres container per-method (S-141 ingest is destructive) using ADR 0021's truncate-between pattern.
- **`MapperVsSchemaCompatibilityTest`.** Happy: every mapper's column list ⊆ entity's mapped columns. Edge: mapper writes column the live schema lacks → fail with diff; mapper omits a non-nullable column without a DB default → fail. Schema source = Hibernate `Metadata` via existing Spring Boot test context.
- **JMH gates.** Pass: ≥ 200K rows/sec single-thread + ≤ 50 MB alloc/sec (`-prof gc`). Regression: vs committed `baseline.json`, `-20%` throughput OR `+50%` alloc-rate fails. Refresh procedure documented in module README.
- **Cross-story hand-offs.** S-027 `LEGACY_MIGRATED` read-back lands as a new test method **in the S-027 test file under `alpenflight/server/`** (needs live `AuditLogRepository` + `actor_kind` enum + Keycloak-sub null assertions). S-024 exemption: one-line YAML add to the leakage-exemptions file listing `person`, `audit_log`, `system_*` (path confirmed at implement).

## Performance plan

- **Hot path.** `FlightCrewMapper.readEntity` — highest-row mapper (~5:1 vs flights; ~25M rows at customer scale per ADR 0019 worked example). JMH thresholds: ≥ 200K rows/sec single-thread (gives ~4.3B-row headroom in the 6 h cutover budget); ≤ 50 MB/s allocation (~6 GB total over a 2 GB bundle, fits G1 young-gen).
- **Allocation discipline.** Mappers MUST NOT allocate per-row beyond Jackson + JDBC inherent — no defensive byte copies, no FK boxing, no `String.format`. `Coercions` returns primitives where the column is non-nullable.
- **`legacy_id_map_<entity>` loader.** Postgres COPY binary format (PGCOPY header + per-row int16 field-count + 16-byte uuid × 2) — not text. Avoids per-row text parsing and UUID stringification.
- **N+1 surface.** FK resolution via `LegacyIdMapTables.resolveForeignKeyArrayQuery` — single `WHERE legacy_guid = ANY(?::uuid[])` per 500-row batch. ArchUnit guard against per-row `findByLegacyGuid` from the mapper hot path.
- **Write-path / WAL.** UUID v7 PKs preserve B-tree insert locality (ADR 0019); per-entity ingest follows `EntityType` topological order (FK targets first). S-141 transaction sets `SET LOCAL synchronous_commit = OFF` — WAL fsync overhead drops materially on single-VPS Postgres (ADR 0010). Documented in module README so S-141 wires it.
- **Parity harness cost.** Per-class container reuse (`@Container static`) — one MSSQL + one Postgres start per JUnit class. PR-gated normal-scale; nightly 10× on `main` re-asserts JMH thresholds against larger seed.
- **Latency budget.** N/A — bundle ingest is one-shot batch; 6 h cutover wall-clock is the operative SLO, 200K rows/sec target is its derivative.

<!-- modernize-refine: end -->
