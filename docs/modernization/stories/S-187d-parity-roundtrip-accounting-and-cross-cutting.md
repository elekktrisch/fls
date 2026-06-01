---
id: S-187d
title: Parity round-trip — ACCOUNTING group + sampled diff + soft-delete + negative-path + mutation-smoke + walltime
epic: E-02
status: todo
depends_on: [S-187a, S-186, S-187c]
integration_base: integration/migration
origin: scope-split
origin_story: S-187a
refined: false
acceptance:
  - **ACCOUNTING-group bindings + fixtures** for AIRCRAFT_RESERVATION_TYPE, AIRCRAFT_RESERVATION, PLANNING_DAY, PLANNING_DAY_ASSIGNMENT_TYPE, PLANNING_DAY_ASSIGNMENT, ARTICLE, ACCOUNTING_RULE_FILTER, DELIVERY, DELIVERY_ITEM, including ≥ 1 degenerate `AircraftReservation` range and ≥ 1 row per permitted sparse-enum value per Club.
  - **RESERVATION_NO_PILOT** producer drop folded into row counts (reuses `ProducerDropReconciliation`).
  - **Sparse-enum coverage-gate dimension (d)** wired into `ParityCoverageGate.Inputs` from the per-mapper permitted-value sets.
  - **Sampled-value diff** (1% `TABLESAMPLE BERNOULLI(1) REPEATABLE(<seed>)`, full-scan floor at PR scale) over the sentinel set, emitting through `ParityValueRedactor` (fail-closed). Zero tolerance on sentinels.
  - **PII drift-guard test** (server-side, where both classpaths are present): assert `ParityValueRedactor`'s PII set ⊇ every `tenant-rules.yaml` `pii_columns` entry whose table maps to a `KNOWN_MAPPER`, so the bundle-local mirror cannot silently rot.
  - **Soft-delete invariant** per soft-deletable entity per Club.
  - **Negative-path bundle-reject cases** (`@Tag("parity-reject")`): BUNDLE_LANGUAGE_NOT_SEEDED, BUNDLE_SCHEMA_{UPGRADE,DOWNGRADE}_NEEDED, BUNDLE_AIRCRAFT_SPOT_LINK_NOT_HTTPS, unmapped-table-no-registry, ARTICLE_DUPLICATE_NUMBER — each asserts the error code + a per-table delta of 0 post-rollback.
  - **Mutation smoke** (`@Tag("parity-meta")`) using `ColumnDroppingMapper`: assert `summary.passed=false ∧ totalDeltas>0` and `deltas/*.json` names the dropped column; inverse case for a `@ParityIgnore` column keeps `passed=true`.
  - **Walltime budget** instrumented (`summary.json.timings`); PR-gated full sweep ≤ 5 min (cached schema-applied MSSQL image lever if apply-every-run exceeds budget), nightly 10× ≤ 30 min.
estimate: L
adr_refs: [0008, 0019, 0022, 0023]
parity_test: alpenflight/migration-bundle/src/parity/java/ch/alpenflight/migration/bundle/parity/ParityOracleHarnessTest.java
---

## Context

Scope-split from [S-187a](S-187a-parity-harness-remaining-mappers-and-gates.md). The final round-trip extension (ACCOUNTING group) plus the cross-cutting assertions that need the full 28-mapper seed to be meaningful: sampled-value diff, soft-delete invariant, negative-path reject classes, mutation-smoke, and the walltime-budget instrumentation. All verified in CI's parity job. See S-187a's refinement block for the reject-vs-drop classification and the fail-closed PII polarity.
