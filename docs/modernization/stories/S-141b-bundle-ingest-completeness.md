---
id: S-141b
title: Bundle ingest — parity round-trip + negative-path ITs + perf + multi-Club fidelity
epic: E-15
status: in_progress
started_at: 2026-05-30
depends_on: [S-141]
integration_base: integration/migration
origin: implementation-followup
kind: completeness
acceptance:
  - Java IT `MigrationBundleParityRoundTripIT` round-trips through S-187's in-process producer (`LegacyFixtureSeeder → KnownMappers.all() → POST /bundle`) with zero row-count delta on the 3 SYSTEM_GLOBAL + 2 FULL_PORT mappers shipped by S-187. (Playwright variant deferred to a separate follow-up — operator pick during refine; the value here is server-side parity, not browser orchestration.)
  - Negative-path ITs added (or parameterised) for the 5 most-frequent SPA-facing error codes: `BUNDLE_HEADER_MALFORMED`, `BUNDLE_TRUNCATED`, `BUNDLE_ALREADY_CONSUMED`, `BUNDLE_INGEST_IN_PROGRESS`, `DEPLOYMENT_EXISTS`, plus the rollback-trail test (inject `MapperFailureFault`, assert exactly one `MIGRATION_INGEST_FAILED` audit + funnel + DB cleanup).
  - Concurrency IT: two threads `CountDownLatch`-gated against the same `uploadId`; assert exactly one 200 + one 409 `BUNDLE_INGEST_IN_PROGRESS` outcome and exactly one Deployment row. Requires hoisting `INGEST_GATE` to an injectable `IngestConcurrencyGate` bean (permits=1 prod, permits=100 in IT profile) — see Design notes.
  - Plaintext-leak runtime smoke: 32-byte marker `ALPENFLIGHT-PLAINTEXT-MARKER-<uuid>` planted in a synthetic bundle's NDJSON destination row (e.g. `t_user.username`); post-ingest grep `java.io.tmpdir`, `/var/tmp`, container `$PGDATA` excluding `pg_wal/`, Logback `ListAppender` all return zero hits; marker present only in the expected destination row.
  - `BUNDLE_TIMEOUT` wall-clock cap (default 15 min, `alpenflight.migration.bundle-timeout` property): ingest runs on Spring's shared `taskExecutor`, servlet thread `orTimeout`s; on timeout the worker is interrupted and Postgres `statement_timeout` (re-enabled — currently `SET LOCAL = 0`) drains in-flight SQL, `try-with-resources` closes `SecureBytes`, `recordFailure` flips the upload to `FAILED`.
  - `MigrationRun.noteCurrent` records the actual Club id being written: `provisioned.primaryClubId()` for tenant-scoped (FULL_PORT) entities, `null` for SYSTEM_GLOBAL entities (per `EntityPolicy.PortPolicy`). Save frequency stays per-entity (28 saves/bundle). Per-row Club fidelity is NOT a goal.
  - `MigrationBundleIngestService` split per maintainability-reviewer suggestion: orchestration (this class), `BundleStreamReader` (header + manifest + tar dispatch + the `NonClosing*` helpers + `rejectUnsafeTarName`), `EntityStreamIngestor` (NDJSON + COPY + temp-table DDL + INSERT builder). Package-private constructors + ArchUnit ban on cross-package construction.
  - Reach destination INSERT through a column-name allowlist (`^[A-Za-z0-9_]+$`) validated at Spring boot via `@PostConstruct` on `EntityStreamIngestor`, NOT in the hot ingest loop. Boot fails with the offending mapper + column name on violation.
  - Hidden bugs flagged by refinement land here: (a) `rejectUnsafeTarName` runs **before** the `legacy_id_map/` prefix dispatch (currently after — path-traversal bypass via `legacy_id_map/../...`); (b) `recordFailure` skips upload-flipping when `errorCode == DATABASE_CAPACITY_EXCEEDED` (pre-txn rejection should not burn the upload); (c) `MANIFEST_EMPTY_CLUBS` (400) is thrown when `manifest.clubs().isEmpty()` before `provisionDeployment`.
estimate: M
refined: true
refined_at: 2026-05-30
refined_specialists: [requirements, solution, qa, security, performance]
github_issue: 177
github_pr: 178
---

## Context

S-141 shipped a walking-skeleton bundle-ingest pipeline (1-Club happy path + 403 cross-user + status round-trip), reviewer-panel-cleared except for completeness scope explicitly carved out. This story rolls up the deferred tests, the design-notes gaps the reviewer panel surfaced, and three hidden bugs the S-141b refine pass found.

The reviewer findings that motivated each AC item are in PR #164's auto-fix discussion; relevant call-sites live in `alpenflight/server/src/main/java/ch/alpenflight/migrations/{application,web}/`.

## Cross-story contracts

- **Consumes:** S-187's `ProducerHarness` + `LegacyFixtureSeeder` (already on the classpath via `includeBuild("../migration-bundle")`) for the parity round-trip.
- **Produces:** the `IngestConcurrencyGate` bean (consumed by S-141 ingest; future jobs may also subscribe). The `BundleStreamReader` / `EntityStreamIngestor` package-private split. The 15-min `BUNDLE_TIMEOUT` default + `alpenflight.migration.bundle-timeout` property surface.
- **Defers:** a Playwright e2e parity spec lands in a follow-up story (to be filed before S-141b finalize); the Java IT in this story is the load-bearing parity proof.

<!-- modernize-refine: start -->

## Design notes

**Service split.** Three classes in `ch.alpenflight.migrations.application`:
- `MigrationBundleIngestService` (orchestration) keeps `ingest()`, gate acquisition, txn boundary via `TransactionTemplate`, principal/upload-row gating, `provisionDeployment()`, post-commit audit/funnel synchronization, `IngestOutcome`, `applySingleTxnSettings()`. Calls `run.noteCurrent()` between `EntityStreamIngestor` invocations so the FSM stays in one place.
- `BundleStreamReader` (package-private) owns `readHeader()`, `readExactly()`, `readManifestOrThrow()`, `rejectUnsafeTarName()`, `NonClosingInputStream`, `NonClosingBufferedReader`, `buildHardenedJsonMapper()`. **`rejectUnsafeTarName` runs as the FIRST call inside the entry loop**, before any prefix-based dispatch — fixes the `legacy_id_map/../...` bypass.
- `EntityStreamIngestor` (package-private) owns `createTemporaryIdMapTables()`, `seedClubLegacyIdMap()`, `copyLegacyIdMap()`, `ingestEntityNdjson()`, `destinationTableFor()`, the `INSERT` builder, and the `@PostConstruct` allowlist validator. ArchUnit rule bans construction from outside `ch.alpenflight.migrations.application`.

**Fork 1 — Concurrency IT (operator pick: hoist gate).** `INGEST_GATE` becomes a `@Component IngestConcurrencyGate` bean with `tryAcquire()` / `release()` and a constructor-time `permits` field (defaults to 1, IT `@TestConfiguration` overrides to 100). Production behaviour unchanged. AC3 IT asserts the per-upload `PESSIMISTIC_WRITE` 409 path; the gate's 429 path is covered by a unit test against the gate component directly.

**Fork 2 — `noteCurrent` fidelity (operator pick: per-entity actual Club).** Call site passes `provisioned.primaryClubId()` for tenant-scoped entities and `null` for SYSTEM_GLOBAL (driven by `EntityPolicy.PortPolicy`). Save frequency stays per-entity (28 saves/bundle). Per-row fidelity rejected — 280k single-hot-row UPDATEs ≫ SPA poll cadence (~1s).

**Fork 3 — `BUNDLE_TIMEOUT` (operator pick: orTimeout + statement_timeout + interrupt, 15 min default).** `ingest()` runs on Spring's shared `taskExecutor`; servlet thread `future.orTimeout(BUNDLE_TIMEOUT, MIN).join()`. On timeout: cancel + interrupt; `applySingleTxnSettings` flips `statement_timeout` from `0` to `${BUNDLE_TIMEOUT} - 1 min` (so SQL drains before the wall clock fires); existing `try-with-resources` wipes `SecureBytes`; `recordFailure` flips upload to `FAILED` with `BUNDLE_TIMEOUT`. Property: `alpenflight.migration.bundle-timeout` (Duration, default 15m).

**Column-name allowlist.** `EntityStreamIngestor.@PostConstruct` iterates `KnownMappers.all()`, regex-checks every `mapper.columns()` entry against `^[A-Za-z0-9_]+$` (precompiled static `Pattern`), fails Spring boot on violation with `IllegalStateException("Mapper " + entityType + " column " + column + " violates [A-Za-z0-9_]+ allowlist")`. All 28 current mapper columns are snake_case (verified during refine) — no false positives. ArchUnit covers the static surface; this covers the runtime registry.

**Parity round-trip.** `MigrationBundleParityRoundTripIT` under `alpenflight/server/src/test/java/ch/alpenflight/migrations/web/`. Seeds via `LegacyFixtureSeeder` (2 Clubs × 3 Users), runs `ProducerHarness.produceTarGz(KnownMappers.all())`, encrypts via the test `MigrationBundleCipher`, POSTs to `/api/v1/migrations/{uploadId}/bundle`, asserts: HTTP 200, response `clubIds.length == 2`, and per-mapper row-count via `JdbcTemplate` matches the seeded fixture across the 5 S-187 mappers. Playwright follow-up filed before finalize.

**ADR 0022 D2 check.** No new CHECK constraints / triggers / generated columns. The `noteCurrent(null)` semantics for SYSTEM_GLOBAL stays a Java method on `MigrationRun` — already nullable via `@Nullable UUID currentClubId`.

## Edge cases & hidden requirements

- **Parity round-trip needs per-(table, tenant) counts, not just per-table** — a row landing under the wrong Club would still pass a global `SELECT count(*)`. Seed ≥ 2 Clubs in `LegacyFixtureSeeder`; assert counts grouped by `operating_club_id` for tenant-scoped tables.
- **`MapperFailureFault` injection seam.** No fault hook exists today; ship as a `@TestConfiguration` swapping one `Mapper` for a throw-on-sentinel wrapper. Production `KnownMappers.all()` stays untouched (no `@Profile` gate in prod).
- **Concurrency IT flake avoidance.** Both threads race on `lockUpload(timeout=0)`; the loser surfaces `BUNDLE_INGEST_IN_PROGRESS` only if it hits the lock *after* the winner has acquired it but *before* the winner commits. Synchronize with a `CountDownLatch` released *inside* the winner's txn callback (post-`lockUpload`), not pre-txn.
- **`BUNDLE_ALREADY_CONSUMED` IT requires a real prior ingest** to drive the upload to `CONSUMED` (state-setter doesn't satisfy the `consumed_at` post-condition the second call's `nonAwaitingUploadException` keys on).
- **`noteCurrent(null)` for SYSTEM_GLOBAL implies `currentClubId` accepts null on the status view DTO.** `MigrationRunStatusView` already has `@Nullable UUID currentClubId`; the OpenAPI snapshot must regenerate to reflect this is intentionally nullable (not "always present for tenant-scoped progress").
- **`recordFailure` over-eagerly burns an upload on `DATABASE_CAPACITY_EXCEEDED`.** Gate `recordFailure` to skip pre-txn rejection codes (`DATABASE_CAPACITY_EXCEEDED`, `BUNDLE_TOO_LARGE`) — those leave the upload row in `AWAITING_UPLOAD` so the client can retry without a fresh handshake. AC9(b).
- **`MANIFEST_EMPTY_CLUBS` enum exists with no producer.** Currently `provisionDeployment` will NPE on `clubIds().get(0)`. Add `if (manifest.clubs().isEmpty()) throw new BundleIngestException(MANIFEST_EMPTY_CLUBS, ...)` before `provisionDeployment`. AC9(c).
- **Tar-name traversal bypass.** `rejectUnsafeTarName` is called *after* the `legacy_id_map/` prefix dispatch — `legacy_id_map/../../etc/passwd.pgcopy` reaches `copyLegacyIdMap` before rejection. Move the check to the loop's first statement. AC9(a).
- **Post-commit audit failure surfaces as 500 with the Deployment present.** Wrap the `afterCommit` audit + telemetry in a try/catch that logs but does not rethrow — the ingest has already committed; surfacing the audit failure as a 500 invites the client to retry and hit `DEPLOYMENT_EXISTS` (409). Log via `LOG.error` for ops visibility.

## Security plan

- **`BUNDLE_TIMEOUT` cleanup invariant.** On timeout, the interrupted worker propagates through the existing `try-with-resources` — `SecureBytes` closes, RSA private key + AEAD session key wiped. Postgres `legacy_id_map_*` temp tables are `ON COMMIT DROP` so rollback self-cleans. IT asserts: post-timeout `migration_upload.private_key_ciphertext IS NULL`, run flipped to `FAILED`, no Deployment row.
- **Plaintext-leak grep policy (operator pick C).** Recurse `java.io.tmpdir`, `/var/tmp`, container `$PGDATA` excluding `pg_wal/`, Logback `ListAppender`. WAL is an expected/legitimate plaintext sink (encrypted at disk layer per ADR 0019 — out of scope for app-level invariant); query logs, core dumps, tmp scratch ARE in scope. Allow exactly one hit in the destination `t_user.username` row.
- **409 vs 429 stay semantically distinct.** Document in `MigrationBundleExceptionHandler`: `BUNDLE_INGEST_IN_PROGRESS` = per-upload race, no `Retry-After`; `DATABASE_CAPACITY_EXCEEDED` = global gate, `Retry-After: 60`. The split-out `IngestConcurrencyGate` bean makes the gate path independently testable.
- **Column-allowlist at Spring boot, not first-request.** Boot fails on malformed column — pod restart > user-request failure. Defense-in-depth against a future mapper PR (or a malicious bundle producer who somehow influences mapper registration) injecting via `INSERT INTO ... (col, col)` string-build.
- **Service split keeps the authz gate at the orchestrator only — structurally.** `BundleStreamReader` + `EntityStreamIngestor` get package-private constructors; ArchUnit rule bans construction outside `ch.alpenflight.migrations.application`. Prevents a future cron / batch from bypassing the principal-owns-upload check by wiring `EntityStreamIngestor` directly.
- **`MIGRATION_INGEST_FAILED` audit redacts bundle PII.** On `MapperFailureFault` the audit row's `before/after` carries `{uploadId, errorCode, entityType, rowsProcessed}` only — never the failing row payload. `tenant_deployment_id` is `null` (Deployment rolled back); `actor_kind = 'NORMAL'` with the caller's `actor_keycloak_sub`.

## Test plan

### Integration tests (`alpenflight/server/src/test/java/ch/alpenflight/migrations/web/`)
- `bundle_header_malformed` — `wrappedKeyLen=0` / `>MAX` in fixed prefix → 400 `BUNDLE_HEADER_MALFORMED`. Factory extension: `buildHeaderOnlyBundle(wrappedKeyLen)`.
- `bundle_truncated_mid_ciphertext` — happy bundle minus last 256 bytes → 400 `BUNDLE_TRUNCATED`.
- `bundle_already_consumed_on_second_post` — POST happy bundle, re-POST same uploadId → 409 `BUNDLE_ALREADY_CONSUMED`.
- `manifest_empty_clubs_rejected` — manifest with `clubs: []` → 400 `MANIFEST_EMPTY_CLUBS` (proves AC9(c)).
- `tar_entry_traversal_rejected` — entry name `legacy_id_map/../etc/passwd.pgcopy` → `BUNDLE_TAR_PARSE_FAILED` (proves AC9(a) — reject *before* dispatch).
- `deployment_exists_pre_decrypt_guard` — seed active `t_deployment` for `userSub`, POST fresh bundle → 409 `DEPLOYMENT_EXISTS`. Spy on `MigrationCryptoService` (`@MockitoSpyBean`) to assert RSA unwrap was NOT called.
- `mapper_failure_rolls_back_cleanly` — `@TestConfiguration` wraps one `Mapper` to throw on sentinel row → no `t_deployment`/`t_club`, one `MIGRATION_INGEST_FAILED` audit, run `state=FAILED`, funnel `ingestFailed` count==1.
- `plaintext_leak_smoke` — plant marker in `t_user.username`; post-ingest grep tmpdir + `/var/tmp` + `postgresContainer.execInContainer("grep", "-rl", marker, "/var/lib/postgresql/data/base")` + Logback `ListAppender`; allow exactly the destination-row hit.
- `concurrency_row_lock_409` — IT profile injects `IngestConcurrencyGate(permits=100)`; two threads `CountDownLatch`-gated, latch released inside winner's `runInsideTransaction` post-`lockUpload`; assert 1×200 + 1×409 `BUNDLE_INGEST_IN_PROGRESS` + exactly one Deployment row.
- `multi_club_progress_records_actual_club` — manifest with 2 Clubs; assert `t_migration_run.current_club_id` reflects the actual Club for tenant-scoped entities, NULL for SYSTEM_GLOBAL.
- `MigrationBundleParityRoundTripIT` — `LegacyFixtureSeeder(2 Clubs, 3 Users)` → `ProducerHarness` → encrypted POST → assert per-(table, tenant) row-count delta = 0 across the 5 S-187 mappers.
- `bundle_timeout_interrupts_in_flight` — `@TestConfiguration` swaps in a `MapperStub` returning rows via `Thread.sleep(60_000)`; assert `BundleIngestException(BUNDLE_TIMEOUT)`, `recordFailure` invoked, no Deployment row, no SecureBytes leak.

### Service-level (no Spring)
- `IngestConcurrencyGateTest` — second concurrent `tryAcquire` returns false; `release` re-permits. Pure JUnit.
- `column_allowlist_at_boot` — `EntityStreamIngestor` constructed with a malformed-column `Mapper` fixture → `IllegalStateException` with column name in message. Pure JUnit.

### Out of scope (filed forward)
- Playwright e2e parity spec — follow-up story to be filed before S-141b finalize.

## Performance plan

- **`BUNDLE_TIMEOUT = 15 min` default.** Sized so a 200 MB typical FLS database (~3 min ingest) has 5× headroom; a 2 GB worst case (~25 min CPU) is hard-capped — client should split, not request more. Property `alpenflight.migration.bundle-timeout` allows operator override per VPS.
- **Reuse Spring's shared `taskExecutor`** for the async ingest wrapper. S-016 already configured it (core=2, max=4). Migration ingests are 1-per-user globally (S-140 state machine), <5 concurrent ever; no dedicated pool needed. Queue capacity 8, `CallerRunsPolicy` so a hypothetical flood back-pressures the HTTP worker instead of dropping uploads.
- **`noteCurrent` save stays per-entity (28 writes/bundle).** Per-row would be ~280k single-hot-row UPDATEs against `t_migration_run` inside the outer txn → WAL bloat + autovacuum lag for invisible-to-SPA fidelity (poll cadence ~1s).
- **Service split is throughput-neutral.** Two extra virtual calls per Tar entry (~28/bundle); JIT inlines. `TarArchiveInputStream` + `Connection` passed through, not copied.
- **Column allowlist amortized to once per JVM boot** via `@PostConstruct`. Regex `Pattern` precompiled. INSERT-string `String.join` is unchanged (already once-per-entity).
- **Concurrency IT budget ~10s on testcontainers.** Acceptable for a single safety-critical test. Mark `@Tag("slow")` so the inner-loop `./gradlew test` doesn't block on it; CI runs everything.
- **Parity round-trip wall-clock smoke: <30s on CI** (record the wall-clock in the IT's assertion as a sanity guard, not a strict threshold — S-188's JMH owns hard numbers).

<!-- modernize-refine: end -->
