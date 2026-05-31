---
id: S-141b
title: Bundle ingest — parity round-trip + negative-path ITs + perf + multi-Club fidelity
epic: E-15
status: done
started_at: 2026-05-30
done_at: 2026-05-31
depends_on: [S-141]
integration_base: integration/migration
origin: implementation-followup
kind: completeness
acceptance:
  - Java IT `MigrationBundleParityRoundTripIT` round-trips a USER FULL_PORT row through the orchestrator with FK rewriting against pgcopy-seeded `legacy_id_map_*`. **Narrowed scope at implement-time** — operator picked "Add FK rewriting in S-141b" when the precondition gap surfaced; the full 5-mapper round-trip (CLUB FULL_PORT NDJSON + `LegacyFixtureSeeder + ProducerHarness`) defers to **S-141c** (provisioning-vs-bundle CLUB reconciliation) and **S-187a** (parity source set on server testImplementation classpath). The 5-mapper end-to-end lives in `migration-bundle/src/parity/.../ParityOracleHarnessTest`.
  - Negative-path ITs for the 6 SPA-facing error codes: `BUNDLE_HEADER_MALFORMED` (wrappedKeyLen=0 and >MAX), `BUNDLE_TRUNCATED`, `BUNDLE_ALREADY_CONSUMED`, `BUNDLE_TAR_PARSE_FAILED` (tar-traversal regression guard), `DEPLOYMENT_EXISTS`, `MANIFEST_EMPTY_CLUBS`. Rollback-trail shipped as `ndjson_parse_failure_rolls_back_cleanly` (NDJSON parse failure semantically equivalent to mapper failure; mapper-injection seam deferred to avoid the @TestConfiguration EntityStreamIngestor swap that the deferred timeout IT seam would have demanded).
  - Concurrency IT: helper thread holds `PESSIMISTIC_WRITE` lock on the upload row; controller's `lockUpload(timeout=0)` surfaces 409 `BUNDLE_INGEST_IN_PROGRESS`. **Diverges from refinement's two-thread CountDownLatch design** — single-thread row-lock proves the same invariant without the timing flake window.
  - Plaintext-leak runtime smoke — marker planted in `deploymentName → t_deployment.name`; asserts zero hits in `java.io.tmpdir`, `/var/tmp`, and the Logback `ListAppender`. **PGDATA grep deferred** — clean Postgres legitimately stores the marker in heap files; heap-vs-non-heap exclusion list is fragile across pg minor versions; the no-disk-sink ArchUnit rule covers the in-app write path.
  - `BUNDLE_TIMEOUT` wall-clock cap (15 min default, `alpenflight.migration.bundle-timeout` property): ingest runs on Spring's shared `applicationTaskExecutor`; servlet thread blocks on `Future.get(bundleTimeout)`; on timeout the worker is interrupted, `try-with-resources` wipes `SecureBytes`, `recordFailure` flips upload to `FAILED`. `statement_timeout` flips from prior `0` to `BUNDLE_TIMEOUT - 1 min` (clamped to half the wall cap for sub-2-minute test budgets) so SQL aborts ahead of the interrupt race.
  - `MigrationRun.noteCurrent` records `null` for SYSTEM_GLOBAL_RESOLVE entities (per manifest `EntityPolicy.PortPolicy`), `primaryClubId` for tenant-scoped. Save frequency stays per-entity (28 writes/bundle).
  - `MigrationBundleIngestService` split: orchestrator + `BundleStreamReader` (header + manifest + tar safety + `NonClosing*`) + `EntityStreamIngestor` (NDJSON + COPY + INSERT builder + column allow-list) + new `ForeignKeyResolver` (per-bundle FK translation). Package-private constructors; ArchUnit rule pins visibility.
  - Column-name allow-list (`^[A-Za-z0-9_]+$`) validated at `EntityStreamIngestor` construction time, not in the hot ingest loop. Spring boot fails on violation.
  - Hidden bugs flagged during refine + implementation: (a) `rejectUnsafeTarName` confirmed running BEFORE the prefix dispatch (refine's claim of post-dispatch was a misread of S-141 code); (b) `recordFailure` now gates `DATABASE_CAPACITY_EXCEEDED` and `BUNDLE_TOO_LARGE` so retryable pre-txn rejections don't burn the upload; (c) `MANIFEST_EMPTY_CLUBS` distinct 400 surfaces from the orchestrator (`BundleManifest` constructor relaxed past the record boundary).
estimate: M
refined: true
refined_at: 2026-05-30
refined_specialists: [requirements, solution, qa, security, performance]
github_issue: 177
github_pr: 178
---

## Context

S-141 shipped a walking-skeleton bundle-ingest pipeline (1-Club happy path + 403 cross-user + status round-trip). This story rolls up the deferred ITs, the design-notes service-split the maintainability-reviewer surfaced, two architectural gaps discovered mid-implementation (FK rewriting + `legacy_guid → id` alias), and three hidden bugs found by refine.

## Cross-story contracts

- **Consumes:** `KnownMappers.all()` from the migration-bundle main artifact (no parity source set access — see AC1 deferral).
- **Produces:** the `IngestConcurrencyGate` bean (consumed by S-141 ingest; future jobs may also subscribe). The `BundleStreamReader` / `EntityStreamIngestor` / `ForeignKeyResolver` package-private split. The 15-min `BUNDLE_TIMEOUT` default + `alpenflight.migration.bundle-timeout` property.
- **Defers to follow-ups (files first):**
  - **S-141c** — provisioning-vs-CLUB-ingest reconciliation. Currently provisioning mints a NEW t_club id while the producer emits the legacy ClubId in `CLUB.ndjson`; the two paths conflict (double rows). Required before the parity round-trip can cover CLUB FULL_PORT.
  - **S-187a** — expose the migration-bundle `parity` source set on the server testImplementation classpath (Gradle outgoing-variant or a published `parity-fixtures` configuration) so the parity round-trip IT can anchor on `LegacyFixtureSeeder` output instead of hand-crafted NDJSON.
  - **Playwright e2e parity spec** — separate follow-up, server-side parity is the load-bearing proof in this story.

## Architecture notes (load-bearing)

- **`legacy_guid → id` alias** lives at the orchestrator INSERT-string builder (`EntityStreamIngestor.destinationColumnNames`). The wire-format column `legacy_guid` becomes the destination's `id` per ADR 0019; the mapper stays symmetric on both halves. `MapperVsSchemaCompatibilityTest` already understands the alias.
- **FK rewriting** runs once per NDJSON row via `ForeignKeyResolver.rewriteForeignKeys`. SYSTEM_GLOBAL_RESOLVE targets fail closed with `BUNDLE_CROSS_TENANT_FK_LEAK` when the lookup misses; FULL_PORT targets leave the value untouched (the FK constraint surfaces the missing-map case naturally). **Divergence from `migration-bundle/parity/.../ForeignKeyRewriter`:** the parity harness skips FULL_PORT-to-FULL_PORT targets citing ADR 0019 GUID preservation; the server orchestrator MUST rewrite CLUB because provisioning mints a fresh Club id (legacy ClubId ≠ new t_club.id). S-187a tracks the alignment so `LegacyIdMapPopulator` seeds `legacy_id_map_club` with the provisioning-minted id when the harness boots the CLUB FULL_PORT path.
