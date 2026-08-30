---
id: S-141
title: Encrypted-bundle upload + streaming decrypt + ingest pipeline
epic: E-15
status: done
started_at: 2026-05-30
done_at: 2026-05-30
merged: true
merged_at: 2026-05-30
depends_on: [S-016, S-138, S-140, S-183]
integration_base: integration/migration
acceptance:
  - `POST /api/v1/migrations/{uploadId}/bundle` (authenticated, `Content-Type: application/octet-stream`, streaming upload) accepts an encrypted bundle in the format defined by ADR 0019. Max body size 2 GB (vision §2 NFR). 413 on oversize.
  - Endpoint streams the upload directly into the decrypt pipeline: header parse → unwrap session key with the per-upload private key (S-140) → AES-GCM-decrypt the archive stream → tar-extract per-entity NDJSON streams → call S-016's schema-mapping ingest function per entity stream → commit-on-success.
  - **No plaintext touches disk.** Decryption happens in-memory in fixed-size chunks; the only persisted output is the resulting Postgres rows. Plaintext-leak test fixture plants a unique marker in a synthetic bundle and asserts the marker never appears in the post-ingest disk + tmpfs greps. (Vision §2 NFR.)
  - The bundle manifest declares N Clubs (the legacy FLS install may host 1..N). The pipeline provisions a fresh Deployment (S-138) plus one Club per manifest entry, hangs each entity stream off its declared Club, and commits the whole thing in a single Postgres transaction.
  - On success: `migration_upload.consumed_at = now`; private key wiped; response carries the new `deploymentId` + the list of provisioned `clubIds`.
  - On failure (decrypt mismatch / corrupt bundle / schema-mismatch / partial ingest): transaction rolls back; no Deployment is provisioned; `migration_upload` flipped to `failed` with error code; private key wiped (failed upload requires a fresh handshake to retry).
  - Progress reporting: SPA polls `GET /api/v1/migrations/{uploadId}/status` and shows a per-phase progress bar. States: `awaiting_upload`, `decrypting`, `ingesting <entity-name> <club-name>`, `provisioning`, `complete`, `failed`.
  - Funnel-telemetry events: `migration.upload_started`, `migration.ingest_started`, `migration.ingest_completed` (with `club_count`), `migration.ingest_failed`.
  - Concurrency: one upload per user at a time (enforced by `migration_upload` state machine from S-140).
estimate: L
adr_refs: [0018, 0019, 0022]
parity_test: tests/migration/upload-and-ingest.spec.ts (deferred to S-141b — see Parity scope below)
refined: true
refined_at: 2026-05-30
refined_specialists: [requirements, solution, qa, security, performance]
context7_last_checked: 2026-05-30
github_issue: 155
github_pr: 164
---

## Context

Vision C28 + C32 + §2 NFR define the security posture (no plaintext at rest). C34 specifies the data model: one upload → one Deployment containing 1..N Clubs.

Streaming hybrid decrypt is load-bearing: a 2 GB plaintext bundle decrypted to a temp file is both a memory-class risk on a single-VPS deployment AND a security risk. Hence the no-disk invariant — enforced structurally by an ArchUnit ban on disk-sink primitives inside `ch.alpenflight.migrations..` and at runtime by disabling Spring multipart on this endpoint.

## Cross-story contracts

- **Consumes:** S-140's `MigrationUpload` + `MigrationCryptoService` (extended with a `SecureBytes` AutoCloseable port); S-138's `DeploymentProvisioningService.provision` (joins the ingest txn) + `reconcileKeycloak` (post-commit `REQUIRES_NEW`); S-183's `Mapper` / `Manifest` / `LegacyIdMapWriter` / `EntityType` + `KnownMappers.all()`; S-187's in-process producer + `LegacyFixtureSeeder` for the parity oracle (when S-141b lands).
- **Produces:** the `t_migration_run` row that S-187a reads (`warnings` jsonb + `error_code` enum); the three new `MIGRATION_INGEST_*` audit actions; the `t_deployment.primary_club_id` column that resolves S-138's open question (both `provision` and `reconcileKeycloak` read it).

## Parity scope

Walking-skeleton only: 1-Club happy path + 403 cross-user + status round-trip. Deferred to **[S-141b](S-141b-bundle-ingest-completeness.md)**:

- Playwright parity spec against S-187's in-process producer (zero-delta on 3 SYSTEM_GLOBAL + 2 FULL_PORT mappers).
- Negative-path ITs (`BUNDLE_HEADER_MALFORMED`, `BUNDLE_TRUNCATED`, `BUNDLE_ALREADY_CONSUMED`, `BUNDLE_INGEST_IN_PROGRESS`, `DEPLOYMENT_EXISTS`, mapper-fault rollback trail).
- Concurrency IT (two-thread `CountDownLatch` against the same `uploadId`).
- Plaintext-leak runtime smoke (32-byte marker + post-ingest grep of `java.io.tmpdir` / `/var/tmp` / `$PGDATA` / Logback `ListAppender`).
- `BUNDLE_TIMEOUT` wall-clock cap (`orTimeout` on the ingest future).
- Per-Club progress (`MigrationRun.noteCurrent` writing the active Club, not always `primaryClubId`).
- Service split (`BundleStreamReader` + `EntityStreamIngestor`) per maintainability-reviewer follow-up.
- Column-name allowlist validated at mapper registration time (not in the hot ingest loop).

Row-level faithfulness (FK orphans, sentinel parity, soft-delete invariant) stays out of scope — S-183/S-187a's domain.

## Schema deviation per ADR 0022 D2

None. State machine is enforced in Java on the `MigrationRun` aggregate; the partial UNIQUE on `(upload_id) WHERE state IN ('DECRYPTING','PROVISIONING','INGESTING','COMPLETING')` is identity-bearing, not a business rule.
