---
id: S-141
title: Encrypted-bundle upload + streaming decrypt + ingest pipeline
epic: E-15
status: in_progress
started_at: 2026-05-30
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
parity_test: tests/migration/upload-and-ingest.spec.ts (new; round-trip via S-187 in-process producer until S-139a swaps in the JAR)
refined: true
refined_at: 2026-05-30
refined_specialists: [requirements, solution, qa, security, performance]
context7_last_checked: 2026-05-30
github_issue: 155
github_pr: 164
---

## Context
Vision C28 + C32 + §2 NFR (plaintext-at-rest exposure) define the security posture. C34 specifies the data model: one upload → one Deployment containing 1..N Clubs.

This story owns the server-side pipeline; S-139 owns the client-side write; S-016 owns the schema-mapping logic shared between them.

The streaming requirement is load-bearing: a 2 GB plaintext bundle decrypted to a temp file is both a memory-class risk on a single-VPS deployment AND a security risk. Streaming hybrid decrypt avoids both.

## Cross-story contracts
- **Consumes:** S-140's `MigrationUpload` + `MigrationCryptoService` (extended with a `SecureBytes` AutoCloseable port — see Design notes); S-138's `DeploymentProvisioningService.provision` (joins the ingest txn) + `reconcileKeycloak` (post-commit `REQUIRES_NEW`); S-183's `Mapper` / `Manifest` / `LegacyIdMapWriter` / `EntityType` + `KnownMappers.all()`; S-187's in-process producer + `LegacyFixtureSeeder` as the parity oracle until S-139's JAR ships.
- **Produces:** `t_migration_run` row that S-187a reads (`warnings` jsonb + `error_code` enum); the three new `MIGRATION_INGEST_*` audit actions; the `t_deployment.primary_club_id` column that resolves S-138's open question (both `provision` and `reconcileKeycloak` read it).

<!-- modernize-refine: start -->

## Design notes

- **Hybrid wire format.** Big-endian header: `MAGIC "ALPF"` (4 B) | `header_version=1` (1 B) | `wrapped_key_len uint16` (2 B) | RSA-OAEP-SHA256-wrapped 32 B AES-256 session key (~512 B for RSA-4096) | Tink `StreamingAead` (`AES256_GCM_HKDF_4KB`) framed body wrapping gzip(tar). Both the RSA-OAEP wrap and the StreamingAead bind `associatedData = uuidBytes(uploadId)`, mirroring S-140's binding pattern. No JDK `Cipher` on the streaming body path (per S-140 pickup note re: deferred-tag footgun).
- **Streaming pipeline.** `HttpServletRequest.getInputStream()` (multipart disabled on this endpoint) → `BoundedInputStream(2 GiB)` → one-shot header parse → RSA-OAEP unwrap of session key (small `Cipher` op) → `StreamingAead.newDecryptingStream` → `GzipCompressorInputStream` → `TarArchiveInputStream` → per-entry dispatcher. **Tar order: entry 0 is `manifest.json` (fail-fast); each `legacy_id_map_<entity>.pgcopy` stream precedes its FK-source NDJSON; `<entityType>.ndjson` in `EntityType` declaration order.**
- **Transaction shape — whole-bundle single txn, per AC4.** One Hikari connection borrowed for the upload's lifetime; `SET LOCAL idle_in_transaction_session_timeout = 0`, `SET LOCAL statement_timeout = 0`, `SET LOCAL synchronous_commit = OFF`, `SET LOCAL lock_timeout = '30s'`. Provision + per-Club ingest + `legacy_id_map_*` COPY + `markConsumed` commit atomically. Per-Club partial-success rejected: cross-Club FK refs (cross-tenant Person / Aircraft / Location) make splits ugly + AC4 already pins single-txn. `reconcileKeycloak(deploymentId)` runs post-commit (`REQUIRES_NEW`); its failure is non-fatal (S-138 hourly job retries).
- **`SELECT ... FOR UPDATE NOWAIT` on `t_migration_upload(id)` at ingest start.** Defends against supersede-mid-ingest (would leave the row `SUPERSEDED`; `markConsumed` would throw at txn end and roll back the whole ingest). Lock held inside the ingest txn; S-140's `MigrationHandshakeService.issue` either waits or fails-fast with `HANDSHAKE_INGEST_IN_PROGRESS`. Do NOT auto-abort the in-flight ingest on a fresh handshake attempt.
- **`t_migration_run` table (V20).** `id uuid PK`, `upload_id uuid NOT NULL REFERENCES t_migration_upload`, `state varchar(32) NOT NULL` (Java enum `MigrationRunState { decrypting, provisioning, ingesting, completing, completed, failed }`), `current_entity varchar(64) NULL`, `current_club_id uuid NULL`, `started_at timestamptz NOT NULL`, `completed_at timestamptz NULL`, `deployment_id uuid NULL REFERENCES t_deployment`, `error_code varchar(64) NULL`, `error_detail text NULL` (`@AuditRedact` — may capture failing-row PII), `warnings jsonb NOT NULL DEFAULT '[]'` (`@AuditRedact`). Partial UNIQUE `(upload_id) WHERE state IN ('decrypting','provisioning','ingesting','completing')` — catches double-POST race. State machine in Java per ADR 0022 D2 — no CHECK. `warnings` shape: `[{code, entityType, clubId, legacyGuid?, detail}]`; codes from S-185/S-186 producer + S-141 consumer (`MANIFEST_PRIMARY_NOT_IN_BUNDLE`, etc.). S-187a reads this column.
- **`t_deployment.primary_club_id uuid NULL` (V21)** — resolves S-138's open question on the divergence between `provision` and `reconcileKeycloak`. Both paths read this column. `provision` writes `ProvisioningRequest.primaryClubId` (manifest hint, fallback lowest UUID); `reconcileKeycloak` reads it and writes the Keycloak user-attribute. The lowest-UUID fallback inside `reconcileKeycloak` is dropped — single source of truth lives on the row.
- **Ingest hot path.** `Mapper.readEntity(JsonNode, PreparedStatement)` per S-183 contract — sets parameters; ingest loop calls `addBatch()`; `executeBatch()` every 4096 rows. JDBC: `reWriteBatchedInserts=true`, `binaryTransfer=true`. `LegacyIdMapTables.resolveForeignKeyArrayQuery` is the only FK-resolution path (S-183 contract); ArchUnit ban on `findByLegacyGuid` from `migrations.ingest.*`. **COPY-binary on destination tables would be faster but breaks the S-183 Mapper contract — surface as a deferred follow-up if the 15-min NFR misses.**
- **Plaintext-leak structural guarantee.** New ArchUnit rule in `alpenflight/server` — classes under `ch.alpenflight.migrations.ingest..` MUST NOT reference `Files.createTempFile` / `File.createTempFile` / `FileOutputStream` / `FileChannel` / `RandomAccessFile` / `Files.write*` / `Files.newOutputStream` / `Files.newBufferedWriter` / `ByteArrayOutputStream` / `InputStream.readAllBytes` / `IOUtils.toByteArray`. Mirrors and tightens S-183's bundle-side ban. **Disable Spring multipart on this endpoint** (default resolver would spool oversize bodies to `java.io.tmpdir` — leaks ciphertext, breaks the no-disk invariant).
- **`SecureBytes` AutoCloseable around `unwrap`.** Extend `MigrationCryptoService` with `unwrapInto(uploadId, ciphertext, Consumer<SecureBytes>)`; caller closure runs with the unwrapped key inside try-with-resources; `SecureBytes.close()` zeros the byte[] on every exit path (happy, decrypt-fail, manifest-reject, ingest-fail, txn-rollback, interrupt). Same wrapper for the AES session key. Vision NFR ≤ 60 s wipe satisfied inline (typically µs).
- **Status endpoint.** `GET /api/v1/migrations/{uploadId}/status` → `{ uploadId, state, currentEntity?, currentClubId?, deploymentId?, clubIds?, errorCode?, occurredAt }`. SPA composes the "ingesting <entity> <club>" label from `currentEntity` + Club lookup; no progress percentage (per-poll row-count would dwarf the ingest). Same `@PreAuthorize` as upload; 404 on cross-user lookup. PK fetches only; p95 < 100 ms.
- **Funnel telemetry.** New `IngestFunnelTelemetry` port mirroring S-140's `HandshakeFunnelTelemetry` — `uploadStarted`, `ingestStarted`, `ingestCompleted(clubCount)`, `ingestFailed(errorCode)`. ids + timestamps + bounded enum codes only — never PII / sizes / display names. Default logging adapter; S-147 swaps later.
- **Concurrency cap.** Global `Semaphore(1)` on ingest at the application layer — single-VPS RAM cannot afford two parallel 2 GB ingests. Second caller gets 429 + `Retry-After`. Per-user uniqueness still enforced by S-140's partial UNIQUE.
- **Audit events.** Three actions added to `AuditAction`: `MIGRATION_INGEST_STARTED` (payload `{uploadId}`, pre-decrypt, inside txn), `MIGRATION_INGEST_COMPLETED` (`{uploadId, deploymentId, clubCount}`, post-commit), `MIGRATION_INGEST_FAILED` (`{uploadId, errorCode, phase}`, post-commit). Actor = Keycloak user. New `MigrationIngestAuditSnapshot` record + allow-list entry under `audit.redaction.entities.*` per S-140 reminder.
- **Schema deviation per ADR 0022 D2.** None. No CHECK, no generated columns, no triggers.

## Edge cases & hidden requirements

- **Body smaller than the cryptographic header** (client crash / TLS truncation): `BUNDLE_TRUNCATED`; row → `FAILED`; private key wiped.
- **Upload arrives at `expires_at + ε`**: distinct `BUNDLE_HANDSHAKE_EXPIRED` (S-140 hand-off); row already wiped by hourly job; 410.
- **Second `POST .../bundle` against the same `uploadId` while first is mid-stream**: `FOR UPDATE NOWAIT` → 409 `BUNDLE_INGEST_IN_PROGRESS`; `t_migration_run(upload_id)` partial UNIQUE is the structural backstop.
- **`uploadId` owned by a different user**: 403 `BUNDLE_FORBIDDEN`. Never echo `user_id`.
- **`uploadId` already CONSUMED / FAILED / SUPERSEDED / EXPIRED**: distinct codes per state, never 500. CONSUMED case includes the existing `deploymentId` so the SPA can deep-link.
- **Manifest declares `clubCount=0`**: reject pre-COPY with `MANIFEST_EMPTY_CLUBS` (S-138 requires ≥ 1 Club).
- **Caller already owns an active/trial Deployment** (S-138 `ux_deployment_owner_active`): 409 `DEPLOYMENT_EXISTS` + existing `deploymentId` — don't even unwrap RSA.
- **`Content-Length > 2 GB` OR chunked body exceeds the cap**: 413 — counting `InputStream` decorator raises mid-stream; header check alone is insufficient for chunked transfer.
- **Mid-stream AEAD tag failure** (truncation): Tink's per-segment tag verifies on the next `read()`; row → `FAILED` with `BUNDLE_TRUNCATED`.
- **NDJSON row's FK points outside the manifest-declared Club set**: hard fail `BUNDLE_CROSS_TENANT_FK_LEAK` (S-183 contract).
- **Bundle declares an entity whose mapper is absent in this server build**: `MAPPER_NOT_AVAILABLE` listing the entity; txn rollback (S-183 fail-fast).
- **Tar entries in vs out of manifest**: missing → `BUNDLE_MISSING_ENTRIES`; extras → `BUNDLE_EXTRA_ENTRIES`. Both hard-fail.
- **Tar entry names with `..` / leading `/` / `\`**: reject — defense-in-depth even though we never touch FS.
- **Decompression bomb**: wrap `GzipCompressorInputStream` in counting stream; cap = `min(manifest.declaredUncompressedSize × 1.1, 20 × bundleSize, 20 GB)`.
- **Jackson per-row hardening**: `StreamReadConstraints.builder().maxStringLength(1_000_000).maxNumberLength(1000).maxNestingDepth(50)` on the parser.
- **Concurrent handshake-by-same-user during ingest**: `FOR UPDATE NOWAIT` blocks supersede; handshake fails with `HANDSHAKE_INGEST_IN_PROGRESS`. In-flight ingest unaffected.
- **`reconcileKeycloak` failure** (Keycloak temporarily down): commit already done → 200 with `deploymentId`; SPA polls `kc_state` until READY; S-138 hourly job converges.
- **No HTTP request/response body logging on this endpoint** — Spring `CommonsRequestLoggingFilter` and any access log that snippets the body must skip this URL.
- **`MIGRATION_INGEST_*` emission ordering**: `STARTED` inside the open txn (pre-decrypt); `COMPLETED` / `FAILED` post-commit. Audit-write failure post-commit does NOT roll back ingest — ops alert, not user-visible blast radius.
- **`Mapper.readEntity` may need server-side columns the mapper doesn't expose** (e.g. `created_by_migration_run_id`): set on the PreparedStatement after the mapper returns. `MapperVsSchemaCompatibilityTest` (S-187) gates the inverse direction.

## Security plan

- **AuthN/AuthZ.** Same `@PreAuthorize` as S-140 (`isAuthenticated() and principal.claims['email_verified']`); plus row-ownership check (`MigrationUpload.user_id == jwt.sub`) BEFORE reading body bytes. Distinct error codes per row state. Non-owner → 403 `BUNDLE_FORBIDDEN`; never echo `user_id`.
- **Pre-decrypt Deployment guard.** Re-check `ux_deployment_owner_active` before allocating crypto. If caller already owns a non-terminal Deployment: 409 `DEPLOYMENT_EXISTS` + existing `deploymentId`; don't even unwrap RSA.
- **Key lifecycle.** `SecureBytes` AutoCloseable wraps both the unwrapped RSA private key (PKCS#8 DER) and the AES session key; try-with-resources from the pipeline's outermost frame; arrays zeroed on every exit path (happy, decrypt-fail, manifest-reject, ingest-fail, txn-rollback, interrupt). Vision NFR ≤ 60 s wipe satisfied inline (typically µs). Server-crash-mid-ingest: wipe is best-effort; an hourly sweep NULLs `private_key_ciphertext` on rows stuck in `decrypting`/`ingesting` past 30 min wall (sibling of S-140's `MigrationHandshakeExpiryJob`).
- **Plaintext-leak structural gate.** ArchUnit ban (above) inside `ch.alpenflight.migrations.ingest..`. Runtime smoke: 32-byte marker fixture asserts absent from `java.io.tmpdir`, `/dev/shm`, `/var/tmp`, request/response logs, log capture, Postgres `$PGDATA` (`pg_ls_dir` / `pg_read_binary_file`), and every tenant-scoped table outside the expected destination. Belt-and-braces: `jcmd GC.heap_dump` post-wipe grep for a missed `byte[]`.
- **PII redaction.** `MigrationIngestAuditSnapshot` payload is ids + ints + bounded enum codes only — no display names, no error-detail prose. `migration_run.warnings` jsonb is `@AuditRedact` (S-183 precedent); `error_detail` is `@AuditRedact`.
- **Bundle is UNTRUSTED.** Encrypted under our pubkey, but the JAR ran on the customer's host. Treat manifest + NDJSON as adversarial: tar-name reject (above), decompression-bomb cap (above), Jackson `StreamReadConstraints` (above), `SCHEMA_VERSION_MISMATCH` → 400 not 500.
- **C14 reaffirmation.** `t_user` has no `password_hash`; a hostile bundle planting `legacy_password_hash` is structurally blocked (no field binding in S-183 mapper). S-141 does not validate.
- **Cross-tenant smuggling.** S-183's `Manifest(...)` constructor validates `TENANT_BYPASS_ALLOW_LIST` — S-141 just calls it. Bundle-local Person / audit-actor sub-maps `ON COMMIT DROP` inside the single ingest txn.
- **DoS.** 2 GB body cap + `Content-Length` check pre-read; global `Semaphore(1)` ingest gate (429 otherwise); wall-clock cap via `orTimeout` on the ingest future (`BUNDLE_TIMEOUT`); Tomcat `connectionUploadTimeout` ≥ 30 min total / 60 s idle. Caddy per-IP cap is the interim layer-7 defence (S-041 deferred).
- **Failed-retry intentional.** `FAILED` + private key wiped forces a fresh handshake — bounds crypto-iteration to one decrypt attempt per handshake.
- **Error-code taxonomy** (bounded enum, structured 4xx never 500): `BUNDLE_TOO_LARGE`, `BUNDLE_FORBIDDEN`, `BUNDLE_HANDSHAKE_EXPIRED`, `BUNDLE_ALREADY_CONSUMED`, `BUNDLE_PRIOR_RUN_FAILED`, `BUNDLE_INGEST_IN_PROGRESS`, `BUNDLE_HEADER_MALFORMED`, `BUNDLE_DECRYPT_RSA_UNWRAP_FAILED`, `BUNDLE_DECRYPT_AEAD_TAG_FAILED`, `BUNDLE_TRUNCATED`, `BUNDLE_TAR_PARSE_FAILED`, `BUNDLE_MISSING_ENTRIES`, `BUNDLE_EXTRA_ENTRIES`, `BUNDLE_CROSS_TENANT_FK_LEAK`, `BUNDLE_TIMEOUT`, `MANIFEST_INVALID`, `MANIFEST_EMPTY_CLUBS`, `SCHEMA_VERSION_MISMATCH`, `MAPPER_NOT_AVAILABLE`, `MAPPER_CONSTRAINT_VIOLATION`, `NDJSON_PARSE_FAILED`, `DEPLOYMENT_EXISTS`, `HANDSHAKE_INGEST_IN_PROGRESS`, `DATABASE_CAPACITY_EXCEEDED`, `INGEST_INTERNAL_ERROR`.

## Test plan

- **Pyramid.** Unit (~12 — decrypt-pipeline state, header parser, `SecureBytes`, transition guards, status formatter, funnel emit). Integration (~10 — Spring + `SharedPostgresContainer`: happy-path against in-process producer, txn rollback, concurrency, status-poll, audit allow-list, multi-Club provisioning). ArchUnit (~4 — disk-sink ban in `migrations.ingest.*`; `findByLegacyGuid` ban; no logger calls on decrypt frames; no `@Transactional(REQUIRES_NEW)` on the ingest path). Playwright parity (1 — `tests/migration/upload-and-ingest.spec.ts`).
- **Parity strategy.** E2e runs against S-187's in-process producer (`LegacyFixtureSeeder` → `KnownMappers.all()` → bundle bytes piped to `POST /bundle`); asserts zero-delta + per-Club row-count equality on S-187's 3 SYSTEM_GLOBAL + 2 FULL_PORT mappers. S-139's JAR is not shipped; gating on it would block S-141. [S-139a](S-139a-parity-harness-processbuilder-swap.md) swaps to true legacy oracle later — same diff harness, different producer. Remaining 25 mappers stay on S-187a's harness (explicitly out of scope). Row-level faithfulness (FK orphans, sentinel-value parity, soft-delete invariant) is S-183/S-187a's domain — do NOT duplicate.
- **Plaintext-leak assertion.** 32-byte marker `ALPENFLIGHT-PLAINTEXT-MARKER-<uuid>` planted inside one row's JSON string field. Post-ingest asserts recursive grep of `java.io.tmpdir` + `/var/tmp` + JVM working dir + Postgres `$PGDATA` (`pg_ls_dir` / `pg_read_binary_file`) = 0; marker present in expected destination row only; absent from every other tenant-scoped table; Logback `ListAppender` capture = 0. Structural gate is the ArchUnit ban; this is runtime defense-in-depth.
- **Negative paths.** Corrupt header byte → `BUNDLE_HEADER_MALFORMED`. POST as wrong user → 403 `BUNDLE_FORBIDDEN`. POST to CONSUMED row → 409 with existing `deploymentId`. Mid-stream tag failure (truncate at 75 %) → `BUNDLE_TRUNCATED`. `SCHEMA_VERSION_MISMATCH`. Bundle entity without server-side mapper → `MAPPER_NOT_AVAILABLE`. `Content-Length > 2 GB` → 413 pre-read; chunked oversize → 413 mid-stream. Decompression bomb (small ciphertext, large declared uncompressed) → cap raises mid-inflate. Tar entry with `..` → reject pre-COPY.
- **Txn rollback (AC6).** Inject a `MapperFailureFault` bean on the 2nd Club's 2nd entity stream. Assert: `t_deployment` + `t_club` + every tenant-scoped table empty; `migration_upload.state = failed`; `private_key_ciphertext IS NULL`; `migration_run.error_code` set; `MIGRATION_INGEST_FAILED` audit row + funnel event emitted exactly once. Fault bean lives behind `@Profile("test-fault-injection")`.
- **Concurrency.** Parallel `POST /bundle` to same `uploadId` → one 200 (with `deploymentId`), one 409 `BUNDLE_INGEST_IN_PROGRESS`; exactly one Deployment. Handshake-by-same-user during ingest → handshake fails with `HANDSHAKE_INGEST_IN_PROGRESS`; in-flight ingest unaffected.
- **Cross-story hand-off tests.** `migration_run.warnings` JSON-schema fixture committed (S-187a consumes). `MigrationUpload.markConsumed` / `markFailed` aggregate transition tests. Audit allow-list integration: `MigrationIngestAuditSnapshot` fields surface unredacted post-success; adding a field without an allow-list update surfaces `[redacted]`. S-138 `provision` + `reconcileKeycloak` invocation order spy (don't re-test S-138's retry).
- **Fixtures.** Default integration: S-187's `LegacyFixtureSeeder` at `scale=small` (~50 KB ciphertext). Streaming-decrypt edge: `scale=streaming` ~80 MB ciphertext (one test, `parityTest` task only; asserts peak heap < 4× bundle with `-Xmx256m`). 2 GB oversize: lazy counting `InputStream` wrapper — never materialise; runs in `parityTest`. `MigrationBundleTestFactory` (deterministic encrypted-bundle producer, not checked in); `MigrationLeakGrepHelper`; `RecordingTelemetrySink` reusable with S-140.
- **Tmpdir grep race** mitigated by filtering on files-created-since-test-t0; runs after response returns.

## Performance plan

- **Memory budget under streaming decrypt.** StreamingAead segment 4 KB (default); gzip window 32 KB; tar record 512 B; one `JsonNode` per row (~4–16 KB typical); `LegacyIdMapWriter` COPY-binary buffer 64 KB. Cap servlet input buffer at 256 KB. **Peak heap ≤ 256 MB above JVM baseline**, asserted by JFR gate in the perf test. No `ByteArrayOutputStream` on the decrypt path — review blocker + ArchUnit ban.
- **Single-txn settings on the ingest connection.** `SET LOCAL idle_in_transaction_session_timeout = 0`, `SET LOCAL statement_timeout = 0`, `SET LOCAL lock_timeout = '30s'`, `SET LOCAL synchronous_commit = OFF` (S-183 hand-off). Per-connection only — never touch global.
- **Temp-table planner trap.** `CREATE TEMP TABLE legacy_id_map_<entity> ... ON COMMIT DROP PRIMARY KEY(legacy_guid)`. **`ANALYZE legacy_id_map_<entity>`** after each COPY, before the dependent FK-resolution `INSERT … SELECT … JOIN`. Postgres does NOT auto-analyze temp tables → planner would see 0 rows → nested-loop disaster.
- **Destination INSERTs via `Mapper.readEntity` + `addBatch` / `executeBatch` every 4096 rows.** `reWriteBatchedInserts=true`, `binaryTransfer=true`. COPY-binary on destination tables (faster, contract-breaking against S-183 Mapper interface) is out of scope for S-141 — file follow-up if NFR misses.
- **`legacy_id_map_<entity>` loaders.** Postgres COPY-binary via `PGCopyOutputStream` (S-183 contract); 4096-row flush boundary.
- **FK resolution.** `LegacyIdMapTables.resolveForeignKeyArrayQuery` only (S-183 contract). ArchUnit ban on `findByLegacyGuid` inside `migrations.ingest.*`.
- **Concurrency.** Global `Semaphore(1)` ingest gate; 429 + `Retry-After` for the second concurrent ingest (any user). HikariCP pool ≥ 10 (status polling + handshake endpoints continue serving while ingest holds its connection).
- **Progress writes.** `migration_run.{current_entity, current_club_id}` updated once per (entity, club) transition — not per row. Same connection / same txn.
- **AES-NI assumption.** Boot-time WARN if AES-GCM provider is `SunJCE` on a host with no `aes` in `/proc/cpuinfo`. Don't 503 — log loudly.
- **Status endpoint p95 < 100 ms.** PK fetches only; no eager-load of Deployment.
- **Perf test.** k6 single-VU upload of a 2 GB synthetic bundle to a prod-class profile (4 vCPU / 8 GB). Pass: wall ≤ 15 min, RSS peak ≤ baseline + 512 MB, old-gen peak ≤ baseline + 256 MB, GC pause ≤ 500 ms, status endpoint p95 < 100 ms during ingest. Excluded from `./gradlew test`; `@Tag("slow")`.

<!-- modernize-refine: end -->
