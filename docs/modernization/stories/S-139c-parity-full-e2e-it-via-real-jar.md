---
id: S-139c
title: Parity full e2e IT — real export jar → MSSQL → upload → ingest → diff (Docker-gated)
epic: E-02
status: todo
depends_on: [S-139a, S-188]
integration_base: integration/migration
origin: scope-split
origin_story: S-139a
refined: false
acceptance:
  - **Cross-module fixture classpath share.** `migration-bundle`'s parity-source-set `LegacyFixtureSeeder` (+ MSSQL apply helpers) is reachable from `server` tests — the composite-include exposes only the main artifact today, so the server IT cannot seed MSSQL. Pick a mechanism (publish the seeder to a shared test-fixtures artifact, or relocate it) and wire it.
  - **Full producer→consumer e2e IT** in `server` (`@Tag("slow")`, Docker-gated, not in `check`): seed MSSQL (LegacyFixtureSeeder) → `ExportJarProducer.produce(ExportJarProducer.exportCommand(ExportJarLocator.locate(), …), password, out.enc)` → POST `.enc` to the real `/api/v1/migrations/{uploadId}/bundle` → real `MigrationBundleIngestService` ingests → structural per-entity diff vs legacy MSSQL passes (zero row-content delta).
  - **Gradle wiring.** Server test task depends on `:migration-tool:shadowJar` and passes its output path as `-Dmigration.tool.jar=<path>` (consumed by `ExportJarLocator`). Cross-build dependency via the existing `includeBuild`.
  - **`-Dparity.producer=process|inprocess`** gates the swap; default `inprocess` until the jar is green nightly, then flip (the flip is the AC-closing step, not silent).
  - **Walltime:** PR-gated ≤ 5 min, nightly ≤ 30 min at `-Dparity.scale=10`; producer step asserted against a generous ceiling (catch ~2× regression, not container noise).
estimate: M
adr_refs: [0022]
parity_test: alpenflight/server/src/test/java/ch/alpenflight/migrations/parity/
---

## Context

Scope-split from [S-139a](S-139a-parity-harness-processbuilder-swap.md). S-139a shipped + unit-tested the producer half — `ExportJarProducer` (spawn / off-argv password / stderr-drain / timeout / exit-mapping / empty-bundle guard) and `ExportJarLocator` (shadowJar resolution). The Docker-gated full e2e IT was deferred here because it needs three things absent from the dev sandbox: an MSSQL container, a migration CI job to run it (**S-188** — migration-bundle/server migration ITs are not built or tested in any CI workflow today), and the cross-module fixture classpath share (S-187a deferred it; S-141b's IT comment documents the gap). All three are CI/infra prerequisites, so the IT lands here once **S-188** gives migration a CI lane.

See S-139a's refinement block for the load-bearing decisions (A1 producer contract, structural-not-byte-wise diff, one-spawn-per-run, off-argv password).
