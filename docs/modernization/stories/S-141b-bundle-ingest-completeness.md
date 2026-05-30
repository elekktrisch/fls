---
id: S-141b
title: Bundle ingest — parity e2e + negative-path ITs + perf + multi-Club fidelity
epic: E-15
status: todo
depends_on: [S-141]
integration_base: integration/migration
origin: implementation-followup
kind: completeness
acceptance:
  - Playwright parity spec `tests/migration/upload-and-ingest.spec.ts` exists and round-trips through S-187's in-process producer (`LegacyFixtureSeeder → KnownMappers.all() → POST /bundle`) with zero row-count delta on the 3 SYSTEM_GLOBAL + 2 FULL_PORT mappers shipped by S-187.
  - Negative-path ITs added (or parameterised) for the 5 most-frequent SPA-facing error codes: `BUNDLE_HEADER_MALFORMED`, `BUNDLE_TRUNCATED`, `BUNDLE_ALREADY_CONSUMED`, `BUNDLE_INGEST_IN_PROGRESS`, `DEPLOYMENT_EXISTS`, plus the rollback-trail test (inject `MapperFailureFault`, assert exactly one `MIGRATION_INGEST_FAILED` audit + funnel + DB cleanup).
  - Concurrency IT: two threads `CountDownLatch`-gated against the same `uploadId`; assert exactly one 200 + one 409 `BUNDLE_INGEST_IN_PROGRESS` outcome and exactly one Deployment row.
  - Plaintext-leak runtime smoke: 32-byte marker `ALPENFLIGHT-PLAINTEXT-MARKER-<uuid>` planted in a synthetic bundle; post-ingest grep `java.io.tmpdir`, `/var/tmp`, Postgres `$PGDATA`, Logback `ListAppender` all return zero hits; marker present only in the expected destination row.
  - `BUNDLE_TIMEOUT` wall-clock cap wired via `orTimeout` on the ingest future (Security/DoS plan).
  - `MigrationRun.noteCurrent` records the actual Club id being written (not always `primaryClubId`); SPA status poll reflects per-Club progress.
  - `MigrationBundleIngestService` split per maintainability-reviewer suggestion: orchestration (this class), `BundleStreamReader` (header + manifest + tar dispatch), `EntityStreamIngestor` (NDJSON + COPY) — keeps each class focused.
  - Reach destination INSERT through a column-name allowlist (`[A-Za-z0-9_]+`) validated at mapper registration time, not in the hot ingest loop.
estimate: M
refined: false
---

## Context

S-141 shipped a walking-skeleton bundle-ingest pipeline (1-Club happy path + 403 cross-user + status round-trip), reviewer-panel-cleared except for completeness scope explicitly carved out. This story rolls up the deferred tests + the two design-notes gaps the reviewer panel surfaced.

The reviewer findings that motivated each AC item are in PR #164's auto-fix discussion; relevant call-sites live in `alpenflight/server/src/main/java/ch/alpenflight/migrations/{application,web}/`.

## Cross-story contracts

- **Consumes:** S-187's `ProducerHarness` + `LegacyFixtureSeeder` (already on the classpath) for the parity round-trip.
- **Produces:** the parity-test contract at `tests/migration/upload-and-ingest.spec.ts` — S-139a swaps the in-process producer for the JAR.
