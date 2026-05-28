---
id: S-016
title: Legacy schema-mapping library + parity oracle
epic: E-02
status: done
started_at: 2026-05-28
done_at: 2026-05-28
depends_on: [S-012, S-013, S-014]
integration_base: integration/migration
github_issue: 157
scope_split: S-183
acceptance:
  - `alpenflight/migration-bundle/` walking-skeleton Gradle module (Java 25, library project) in place.
  - `Mapper` bidirectional-per-entity interface published with `entityType()` + `columns()` metadata; concrete `writeNdjson` / `readEntity` signatures land with S-183.
  - `EntityType` enum carries topological `INSERT_ORDER` + V2/V3/V4 sub-package routing via `Group`.
  - `LegacyIdMapTables` exposes per-entity temp-table naming + the parameterised batch-prefetch SQL contract S-141 will wire against.
  - `Coercions` static helpers shared by future mappers (boolean → tri-state enum tag).
  - One concrete `identity.CountryMapper` as the walking-skeleton sample, demonstrating the SYSTEM_GLOBAL ref path via `legacy_int_id` per S-012.
  - Unit tests on the concrete mapper assert the `Mapper.columns()` contract (`legacy_int_id` present; defensive copy invariant).
  - Remaining scope — full mapper coverage, manifest typed class, COPY-writer byte format, parity oracle harness, Faker fixtures, ArchUnit rules, JMH bench, CI wiring — tracked under **S-183**.
estimate: L
adr_refs: [0002, 0003, 0019]
refined: true
refined_at: 2026-05-28
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer, performance-engineer]
github_pr: 158
---

## Context

S-016 owns the schema-mapping content + parity oracle that the legacy-to-new transport (JAR exporter S-139 + upload pipeline S-141) consumes. This story shipped the **walking-skeleton** subset (Gradle module + interfaces + one sample mapper); the full ~60-mapper coverage + parity oracle harness + ArchUnit rules + JMH bench + CI wiring lives in [S-183](S-183-migration-bundle-mappers-and-parity-oracle.md).

<!-- modernize-refine: start -->

## Design notes (walking-skeleton load-bearing)

- **Module shape: standalone Gradle library at `alpenflight/migration-bundle/`** — Java 25, Gradle 9.4.1 wrapper, no Spring Boot. Two future consumers (S-139 JAR exporter + S-141 server ingest) take this as a project dependency. CI wiring lives in S-183.
- **`Mapper` interface** publishes the per-entity routing surface (`entityType()` + `columns()`) + the column-list defensive-copy invariant. The `writeNdjson` / `readEntity` method signatures are deferred-by-design until S-183 brings Jackson + JDBC into the consumer-side dependency closure.
- **`LegacyIdMapTables.resolveFkArrayQuery` returns two columns** (`legacy_guid, new_uuid`), not one. Postgres `= ANY(?)` predicates do not preserve result-row order, so a single-column return would force callers to re-issue or re-order per-row — the two-column shape lets the caller batch-map a 500-item input array in one query.
- **`Coercions.boolToEnumTag` is tri-state** (`YES` / `NO` / `UNKNOWN`); null input maps to `UNKNOWN` so a legacy nullable bit column round-trips through the S-129 string-enum encoding without losing the third state.
- **`EntityType` enum declaration order IS the ingest order.** The FK-target-precedes-source invariant is honor-system today; an ArchUnit rule under S-183 makes it structural. The `Group` field's V2/V3/V4 sub-package routing is convention-only until S-183's ArchUnit rule lands.

## Operator-grilled decisions (carry forward to S-183)

These calls landed before scope-split; S-183 inherits them:

- **Mapper granularity: one class per legacy entity, grouped into 3 sub-packages aligned with V2/V3/V4 Flyway boundaries** (`.identity.*`, `.flight.*`, `.accounting.*`). Not ~12 cluster mappers — small per-class test surface, PRs touching one entity touch one file.
- **Tombstones port-all by default; manifest skip-overrides per-entity.** Audit continuity + Swiss OR Art. 957a invoice-retention rationale. Reference tables skip tombstones (noise).
- **Cross-bundle Person dedupe = out of scope** (carried over from S-141 grill). Each bundle's legacy GUIDs are local; collisions across uploads are accidental, not by design. Manual merge via S-051 lookup if it ever matters.

## Cross-story contracts S-183 implements against

- **`legacy_id_map_<entity>` byte format** — S-016 owns the format; S-141 owns the temp-table lifecycle (`ON COMMIT DROP`).
- **NDJSON wire schema = new-schema column names (snake_case)**; manifest carries `schema_version: 1` so ingest rejects mismatched bundles up-front.
- **Reference-data resolution is two-tier:** SYSTEM_GLOBAL refs resolve via the `legacy_int_id` column S-012/013/014 added on reference tables; TENANT_SCOPED refs port from the bundle via the per-bundle `legacy_id_map_*` temp tables. S-138's per-Club bootstrap runs as no-op for ported-from categories.
- **Cross-tenant Person sub-map is per-bundle**, not per-Club — S-141's User.PersonId rewrite (its AC #10) flows through this single shared sub-map.
- **Audit-log actor remap** produces `actor_kind='LEGACY_MIGRATED'` + `legacy_actor_user_id` text + NULL `actor_keycloak_sub`. **S-027 test plan must cover read-back of this row variant** (cross-story hand-off owned by S-183 implementation). Orphan audit refs synthesize a UUID v7 + warning in `migration_run.warnings`; the txn does not fail.
- **S-024 cross-tenant-leakage CI exemption list** must add Person + audit_log + system tables (cross-story hand-off owned by S-183 implementation).

## Schema deviation from ADR 0022 D2

None. S-016 is a Java module; no Flyway migrations originate here. The `legacy_int_id SMALLINT UNIQUE` columns introduced by S-012/013/014 are reference-table identity hooks, not business logic.

<!-- modernize-refine: end -->
