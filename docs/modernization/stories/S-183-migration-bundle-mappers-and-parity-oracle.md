---
id: S-183
title: Migration-bundle — mapper-contract scaffolding + Manifest + LegacyIdMapWriter + ArchUnit
epic: E-02
status: in_progress
started_at: 2026-05-29
depends_on: [S-016]
integration_base: integration/migration
origin: scope-split
origin_story: S-016
scope_split: [S-184, S-185, S-186, S-187, S-188]
refined: true
refined_at: 2026-05-29
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer, performance-engineer]
context7_last_checked: 2026-05-29
github_issue: 167
github_pr: 168
acceptance:
  - **`Mapper` interface completion** — `writeNdjson(ResultSet, JsonGenerator)` (export-side) + `readEntity(JsonNode, PreparedStatement)` (ingest-side) + `foreignKeys()` (FK target list, used by the ArchUnit ingest-order rule + parity oracle) lands on the interface, with the allocation-discipline contract documented (no per-row allocation beyond Jackson + JDBC inherent).
  - **`Manifest` typed record** — per-entity port-policy, per-entity tombstone-policy, per-FK tenant-bypass flag, `schemaVersion`, per-table column allow-list. Jackson-serialized JSON on the wire; bundle-open coverage gate fails if `EntityType.values()` isn't either policy-mapped or in `unmappedReason`.
  - **`legacy_id_map_<entity>` COPY-binary byte format** owned by this module + a `LegacyIdMapWriter` helper that S-141 wires `PgConnection.getCopyAPI()` to via the `OutputStream` interface (PGCOPY header + per-row int16 field-count + 16-byte uuid × 2).
  - **Unmapped-table registry** — `LanguageTranslation`, `PersonFlightTimeCredit*`, `Setting`, `SystemData`, `SystemLog`, `SystemVersion`, `UserAccountState`, `PersonPersonCategory` each carry a manifest "WHY not mapped" entry surfaced via a `static UnmappedTables.REGISTRY` so the parity oracle (S-187) can gate on every legacy table being mapped or explicitly unmapped.
  - **`@ParityIgnore` / `@ParitySentinel` annotations** — column-level opt-out / opt-in markers, retained at runtime, consumed by the parity oracle (S-187).
  - **`AbstractMapperContractTest<E, M>`** base class + Faker row factory hook; subclass overrides two methods. Asserts: `columns()` defensive copy invariant; round-trip `writeNdjson → readEntity` row-identical modulo `@ParityIgnore`; FK declarations resolve to entities earlier in `EntityType` declaration order. Country sample mapper passes.
  - **ArchUnit rules** in this module: (1) no dependency on `alpenflight.server.person.PersonRepository` or any `@Repository` from `alpenflight.server.*`; (2) no `Files.createTempFile` / `File.createTempFile` / `FileOutputStream`; (3) no `Statement.executeQuery(String)` or `createNativeQuery` with string concatenation; (4) `EntityType` ingest-order invariant — every `foreignKeys()` target's ordinal must be less than the source entity's ordinal.
  - **Country mapper expanded** to the full bidirectional contract — `writeNdjson(ResultSet, JsonGenerator)` + `readEntity(JsonNode, PreparedStatement)` + `foreignKeys()` returning empty. Passes the contract suite.
  - **Follow-up stories filed** under `origin: scope-split`, `origin_story: S-183`: S-184 (identity mappers), S-185 (flight mappers), S-186 (accounting + audit mappers), S-187 (parity oracle harness + `LegacyFixtureSeeder` + `MapperVsSchemaCompatibilityTest`), S-188 (JMH bench + CI workflow). Each carries `integration_base: integration/migration`.
estimate: M
adr_refs: [0002, 0003, 0008, 0019, 0022, 0023]
---

## Context

Scope-split from [S-016](implemented/S-016-data-migration-script.md). S-016 shipped the walking skeleton (Gradle module + `Mapper` routing surface + `EntityType` + `LegacyIdMapTables` + `Coercions` + one concrete `CountryMapper`). This story ships the **mapper-contract scaffolding** the per-package mapper stories (S-184/S-185/S-186) and the parity harness (S-187) build on: the bidirectional `writeNdjson` / `readEntity` / `foreignKeys` signatures, `Manifest`, `LegacyIdMapWriter`, `@ParityIgnore` / `@ParitySentinel`, `AbstractMapperContractTest`, ArchUnit rules, and the unmapped-table registry. The single fully-fleshed mapper (`Country`) demonstrates the contract end-to-end so the per-package stories drop into a known shape.

The scope-split happened mid-implement when the operator chose to defer the per-package mapper bodies + the parity oracle harness + JMH + CI into follow-ups rather than ship the original ~13-AC scope in one PR. The follow-ups (S-184–S-188) all inherit `integration_base: integration/migration` and chain through the same integration branch.

All load-bearing design decisions still live in S-016's refinement (`<!-- modernize-refine: start --> / end -->` block) and are inherited here. The refinement block below carries only the deltas relevant to the scaffolding scope.

## Cross-story contracts

- **Consumes:** S-016's `Mapper` interface + `EntityType` + `LegacyIdMapTables` + `Coercions` skeleton; ADR 0018 aggregate boundaries; ADR 0019 UUID v7 strategy.
- **Produces:** the contract surface that S-184 / S-185 / S-186 mappers conform to; the `Manifest` + `LegacyIdMapWriter` + `UnmappedTables` infrastructure that S-141 wires; the `AbstractMapperContractTest` framework the per-mapper unit tests subclass.
- **Hand-offs (deferred to the per-package follow-ups):** S-027's `LEGACY_MIGRATED` read-back coverage lands with S-186 (where the audit-log mapper does). S-024's leakage exemption list update lands with S-186 (same reasoning).

## Notes

- Estimate trimmed to `M` after the scope-split.
- S-141's implement is blocked on this story **plus** S-184–S-186 + S-187.
- A new V17+ Flyway migration may be needed if `MapperVsSchemaCompatibilityTest` (S-187) surfaces a destination-schema gap — file as a sibling under S-187 when that lands.

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
