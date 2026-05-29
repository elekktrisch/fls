---
id: S-141
title: Encrypted-bundle upload + streaming decrypt + ingest pipeline
epic: E-15
status: todo
depends_on: [S-016, S-138, S-140, S-183]
integration_base: integration/migration
acceptance:
  - `POST /api/v1/migrations/{uploadId}/bundle` (authenticated, `Content-Type: application/octet-stream`, streaming upload) accepts an encrypted bundle in the format owned cooperatively by S-139 (writer) + S-183 (`Manifest` typed class). Max body size 2 GB (vision §2 NFR). 413 on oversize.
  - Endpoint streams the upload directly into the decrypt pipeline: header parse → unwrap session key with the per-upload private key (S-140) → AES-GCM-decrypt the archive stream → tar-extract per-entity NDJSON streams → call S-183's `Mapper.readEntity` per entity stream → commit-on-success.
  - **No plaintext touches disk.** Decryption happens in-memory in fixed-size chunks; the only persisted output is the resulting Postgres rows. Plaintext-leak test fixture plants a unique marker in a synthetic bundle and asserts the marker never appears in the post-ingest disk + tmpfs greps. (Vision §2 NFR.)
  - The bundle manifest declares N Clubs (the legacy FLS install may host 1..N). The pipeline provisions a fresh Deployment (S-138) plus one Club per manifest entry, hangs each entity stream off its declared Club, and commits **per-Club** in dependency order — refined from "single txn" per operator pick; see [Design notes](#design-notes).
  - On success: `migration_upload.consumed_at = now`; private key wiped; response carries the new `deploymentId` + the list of provisioned `clubIds`.
  - On failure (decrypt mismatch / corrupt bundle / schema-mismatch / partial ingest): rollback the in-flight Club txn + DELETE-pass over any already-committed Clubs + DELETE the Deployment row; `migration_upload` flipped to `failed` with structured error code; private key wiped (failed upload requires a fresh handshake to retry). A reaper job catches orphans if the cleanup pass itself crashes.
  - Progress reporting: SPA polls `GET /api/v1/migrations/{uploadId}/status` and shows a per-phase progress bar. States: `awaiting_upload`, `decrypting`, `ingesting <entity-name> <club-name>`, `provisioning`, `complete`, `failed`.
  - Funnel-telemetry events: `migration.upload_started`, `migration.ingest_started`, `migration.ingest_completed` (with `club_count`), `migration.ingest_failed`.
  - Concurrency: one upload per user at a time (enforced by `migration_upload` state machine from S-140).
  - **User↔Person link preservation.** Each ingested `t_user` row carries the matching new `t_person.id` via the per-bundle cross-tenant Person sub-map S-183 produces (S-016 refinement contract). The bulk INSERT into `t_user` resolves `person_id` via the per-bundle sub-map, NOT via per-Club temp tables (Person is cross-tenant).
estimate: L
adr_refs: [0018]
parity_test: tests/migration/upload-and-ingest.spec.ts (new; round-trip from S-139 JAR through this pipeline)
refined: true
refined_at: 2026-05-29
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer, performance-engineer]
---

## Context
Vision C28 + C32 + §2 NFR (plaintext-at-rest exposure) define the security posture. C34 specifies the data model: one upload → one Deployment containing 1..N Clubs.

This story owns the server-side pipeline; S-139 owns the client-side write; S-183 owns the schema-mapping library (and inherits the `Manifest` typed-class contract from S-016).

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Endpoint scaffold (Spring `@PostMapping` consuming `application/octet-stream` via `HttpServletRequest.getInputStream()`).
- [ ] Decrypt pipeline: header parse, RSA-OAEP unwrap, AES-GCM stream-decipher, GZIP + tar inflate.
- [ ] Manifest reader: enumerate Clubs + `primaryClubId` hint, hand off to provisioning (S-138).
- [ ] Per-entity ingest dispatch using the shared `alpenflight/migration-bundle/` library (S-183).
- [ ] Transactional boundary: per-Club txn + bundle-failure cleanup pass + reaper backstop.
- [ ] Status-polling endpoint + throttled progress updates.
- [ ] Plaintext-leak test fixture + assertion.
- [ ] Idempotency: re-uploading after `failed` requires a fresh handshake.

<!-- modernize-refine: start -->

## Design notes

- **Module placement:** new `alpenflight/server/src/main/java/ch/alpenflight/migrations/` (sibling of `deployments/`, `tenancy/`) following ADR 0023 hexagonal layering — `migrations.{domain,application,web,infra}`. The `MigrationUpload` aggregate started by S-140 grows here with phase + failure + result fields.
- **Bundle-format ownership.** The bundle header (magic, version, RSA-OAEP wrapped session key, GCM IV) is a struct shared 1:1 by S-139 (writer) + S-141 (reader). The post-decrypt structure (tar of gzip of [`manifest.json`, per-entity NDJSON]) is owned by S-183's `Manifest` typed class. **There is no bundle-format ADR** — the frontmatter's prior `0019` cite was stale; ADR 0019 is the unrelated entity-ID strategy.
- **Streaming pipeline (assembled top-down):** `HttpServletRequest.getInputStream()` → `BoundedInputStream(2 GiB)` → header-parse + RSA-OAEP unwrap → `CipherInputStream(AES/GCM/NoPadding)` → `GZIPInputStream` → `TarArchiveInputStream` (Commons Compress) → per-tar-entry dispatch (`manifest.json` MUST be the first entry; rest are NDJSON). No `readAllBytes()`, no bundle-sized `ByteArrayOutputStream`, no `MultipartFile`. Spring multipart is disabled on this endpoint.
- **AEAD-tag deferral gotcha.** JDK `CipherInputStream` only validates the GCM tag on `close()`. The orchestrator MUST drain to EOF + close the cipher stream BEFORE committing the per-Club txns' last commit. The whole ingest must observe close() returning without `AEADBadTagException` (or `IOException` wrapping it); any other ordering ships unauthenticated plaintext into the DB.
- **Manifest-first.** Parse + validate manifest BEFORE Phase 2 provisions Deployment. Reject `SCHEMA_VERSION_MISMATCH` / `MANIFEST_INVALID` / `manifest_empty_clubs` up-front.
- **Transaction shape — per-Club commits + bundle-failure cleanup pass (operator-chosen; supersedes AC §4's earlier "single txn" wording).**
  - **Phase 1 (txn A):** manifest parse + validate. No DB writes; read-only access to verify uploader owns the `migration_upload`.
  - **Phase 2 (txn B):** `DeploymentProvisioningService.provision(manifest, currentUserSub)` — creates Deployment (`lifecycle_state = trial`) + N empty Clubs + per-Club seed reference data. Commits. Returns `(deploymentId, clubIds[])`.
  - **Phase 3 (txn C₀, then C₁ … C_N, one per Club, in manifest order, all on the same held Postgres connection):** C₀ creates the **session-scoped (connection-scoped, NOT `ON COMMIT DROP`) cross-tenant Person sub-map TEMP TABLE** (matches S-016 / S-183's cross-tenant Person mapping contract) and COPY-populates it. C₁…C_N each open a per-Club txn → create per-Club `legacy_id_map_<entity>` temp tables `ON COMMIT DROP` → COPY-to-staging + `INSERT … SELECT` into `t_<entity>` in `EntityType.INSERT_ORDER` for tenant-scoped entities, resolving FKs through both the per-Club temp tables AND the connection-scoped Person sub-map → commit Club's txn. The connection is held across all per-Club txns so the Person sub-map survives; released after Phase 4 commits the post-Phase-3 success row.
  - **Phase 4 (post-commit, `REQUIRES_NEW`):** `DeploymentProvisioningService.reconcileKeycloak(deploymentId)`. S-138's hourly `MigrationReconcileJob` retries `kc_state = PENDING`; S-141 does NOT replicate.
  - **On failure inside Phase 3:** rollback the in-flight Club txn → `cleanupFailedIngest(deploymentId)` executes in its own txn (DELETE the Club rows in reverse-FK order for any already-committed Clubs, then DELETE the empty Clubs themselves, then DELETE the Deployment + Keycloak group if it landed). Sets `migration_upload.state = failed`, `failure_code`, `failure_message`.
  - **Backstop reaper:** a new `MigrationIngestReaperJob` (hourly, in this story) sweeps any `t_deployment` row whose source `migration_upload` is `failed` AND whose `t_club` rows or seed data exist — tears them down. Same job sweeps `migration_upload` rows stuck in `decrypting` / `ingesting` past 30 min wall (covers server-crash case). This unblocks the next handshake (S-138 AC §7 409 check stays correct because the orphan Deployment is gone). The hourly tick aligns with S-138's reconcile job.
- **`@TenantId` posture.** Phase 2's Deployment + empty-Club creation runs inside `UnscopedTenantContext` (cross-Club iteration per ADR 0008 + S-138's contract). Each Phase 3 Club-txn `TenantContext.set(clubId)` BEFORE the entity dispatcher's first INSERT for that Club. Per-Club COPY-then-`INSERT … SELECT` honors the discriminator via the raw-JDBC path's explicit `club_id` column write (Hibernate is bypassed in the bulk path).
- **S-138 open question closed — persist `primary_club_id` on `t_deployment`.** V17 (this story) adds `t_deployment.primary_club_id UUID NULL` FK to `t_club(id)`, nullable for non-migrated Deployments. S-141 populates from manifest at Phase 2; `reconcileKeycloak` reads it. Eliminates the divergence S-138 flagged; deterministic across JVM restarts.
- **V17 Flyway migration (this story owns).** New `t_migration_run` parent row tracks the ingest attempt — separate from `t_migration_upload` (the handshake-credential row): `id UUID PK`, `upload_id UUID NOT NULL FK t_migration_upload(id)`, `deployment_id UUID NULL FK t_deployment(id)` (set at Phase 2 commit), `state VARCHAR(32) NOT NULL`, `phase_label VARCHAR(64) NULL`, `phase_progress SMALLINT NULL` (0-100), `current_entity VARCHAR(64) NULL`, `current_club_id UUID NULL`, `committed_club_count SMALLINT NULL`, `started_at TIMESTAMPTZ NOT NULL`, `completed_at TIMESTAMPTZ NULL`, `failure_code VARCHAR(64) NULL`, `failure_message TEXT NULL`, `warnings JSONB NULL` (matches S-016 refinement's `migration_run.warnings` contract for S-183 orphan-actor synth warnings). Plus `t_deployment.primary_club_id UUID NULL` FK above. **No CHECK encoding the state enum** (ADR 0022 D2 — state machine lives on the `MigrationRun` aggregate, alongside `MigrationUpload`'s pre-ingest FSM in S-140).
- **Status endpoint.** `GET /api/v1/migrations/{uploadId}/status` joins `t_migration_upload` + the latest `t_migration_run` row and returns `{state, phaseLabel, phaseProgress, currentEntity?, currentClubId?, committedClubCount?, deploymentId?, clubIds?[], failureCode?, failureMessage?, warnings?[]}` — PK lookup on `migration_upload.id` + FK index on `migration_run.upload_id`. Phase-update writes from Phase 3 use `REQUIRES_NEW` so polling sees forward progress mid-ingest WITHOUT contending on the Club's open txn. **Throttle progress UPDATEs to once per `EntityType` boundary OR once per 2s wall, whichever comes first** — per-row UPDATEs on a 25M-row entity would dominate the wall budget and bloat WAL. SPA polls every 1.5s while non-terminal, 5s after `provisioning`.
- **Per-upload private key.** S-140 supplies `private_key_ciphertext`. S-141 decrypts under the env master-key holder, holds the unwrapped RSA private key bytes in a `byte[]` (NOT `String` — immutable, lingers in heap dumps), uses to unwrap the session key, zeros both `byte[]`s in a `finally` block, then SQL-NULLs `private_key_ciphertext` in Phase 2's same Postgres txn AND on the failure path (cleanup txn). Satisfies vision §2 NFR (≤60s wipe on success or failure).
- **Cross-story hand-offs:**
  - **S-027 (audit infra):** Phase 3's per-row CRUD audit entries on `t_person`/`t_club`/etc. set `system_actor = true` on `MutationAuditEvent` so the trail distinguishes "user authored row" from "migrator authored row". S-027's `AuditTrailService` already drives the flag off authentication type — S-141 needs a request-scoped override or a tagged `Authentication`. Hand-off line: S-027 must expose a `MIGRATION_INGEST` system-actor mode reachable from this module.
  - **S-138 reconcile retry chain:** already owns the hourly `MigrationReconcileJob`; S-141's `MigrationIngestReaperJob` is the sibling that cleans orphan ingest state. Both run hourly on the same scheduler.
  - **S-183 cross-tenant Person sub-map:** S-141 consumes the per-bundle Person sub-map S-183 owns. The bulk INSERT into `t_user` resolves `person_id` via this sub-map (Person is cross-tenant); per-Club temp tables are NOT used for Person resolution. Both sides — S-183 produces the COPY byte format, S-141 wires `PgConnection.getCopyAPI()` to populate the sub-map temp table once at the start of Phase 3 (across all Clubs), then drops it at the outer Phase 3 boundary.
- **Citation correction (operator-confirmed).** Frontmatter dropped `0019` from `adr_refs`. Format-ownership note in AC §1 + here.

## Edge cases & hidden requirements

- **Double POST on same `uploadId`** (two browser tabs): state-machine guards — second POST sees state ≠ `awaiting_upload` → 409 `upload_in_progress`. Don't open the body stream.
- **`MigrationHandshakeExpiryJob` (S-140) fires mid-ingest:** expiry job's predicate is `state = 'awaiting_upload' AND expires_at < now` — ingest transitions OUT of `awaiting_upload` on request start, so the predicate never matches an in-flight ingest. Confirmed-safe.
- **Re-handshake after `failed` / `expired`:** failed rows persist for audit; new handshake creates a fresh row (does NOT supersede the failed one). S-140's "one `awaiting_upload` per user" invariant still holds.
- **Client aborts mid-stream** (tab close / network drop): the request thread observes truncated body → `MigrationUpload.fail(upload_aborted)` + cleanup pass + key wipe. No row stays in `decrypting`.
- **Status poll concurrent with ingest:** uses Phase 3's `REQUIRES_NEW`-written throttled progress columns — no contention with the open Club txn.
- **Bundle pre-flight S-138 409 check** (user already owns trial/active Deployment): MUST run BEFORE opening the body stream. Don't burn 2 GB then reject.
- **Manifest declares 0 Clubs OR all Clubs empty:** reject pre-decrypt with `manifest_empty_clubs` (violates C34 "1..N Clubs" + S-138 AC §2).
- **Tar entries in vs out of manifest:** missing-from-archive → `bundle_missing_entries`; extras in archive → `bundle_extra_entries`. Both hard-fail. Silent skip would burn the support story.
- **Zip-Slip-equivalent on tar entry names:** reject `..`, leading `/`, `\`. Defense-in-depth — we never write to FS but future-proof.
- **Decompression-bomb cap:** wrap GZIP in counting stream; cap = `min(manifest.declaredUncompressedSize, 10 × bundleSize, 20 GB)`. Refuse beyond.
- **Jackson hardening per NDJSON row:** `StreamReadConstraints.builder().maxStringLength(1_000_000).maxNumberLength(1000).maxNestingDepth(50)`.
- **Error-code taxonomy** (distinct, not a free-text bucket): `BUNDLE_TOO_LARGE` (413), `BUNDLE_DECRYPT_RSA_UNWRAP_FAILED`, `BUNDLE_DECRYPT_AEAD_TAG_FAILED`, `BUNDLE_TAR_PARSE_FAILED`, `MANIFEST_INVALID`, `SCHEMA_VERSION_MISMATCH`, `MANIFEST_EMPTY_CLUBS`, `BUNDLE_MISSING_ENTRIES`, `BUNDLE_EXTRA_ENTRIES`, `NDJSON_PARSE_FAILED`, `MAPPER_CONSTRAINT_VIOLATION`, `DATABASE_CAPACITY_EXCEEDED`, `UPLOAD_ABORTED`, `INGEST_INTERNAL_ERROR`.
- **Telemetry ordering:** `migration.upload_started` on request-start (post-auth, pre-decrypt). `migration.ingest_started` post manifest validation, pre Phase 2. `migration.ingest_completed` post Phase 3 final commit, pre Phase 4. `migration.ingest_failed` carries the structured error code (NOT free-text). `club_count` is manifest-declared (= ingested on success).
- **Orphan audit-actor warnings** (from S-183 mapper synth): land in `migration_upload.warnings_json` AND surface in status / response payload — user sees the count + sample reasons.
- **Server-crash mid-ingest:** `MigrationIngestReaperJob` (above) sweeps. Private-key wipe is best-effort on crash — vision §2 NFR's 60s budget is a soft contract under crash; documented exception. The ciphertext column on the orphan row gets NULLed by the reaper when it tears the row down.
- **Disk-full on Postgres** during temp tables: `DATABASE_CAPACITY_EXCEEDED` → cleanup pass + key wipe + status `failed`. User cannot self-recover; remediation links to operator-contact.
- **Sandbox ingest temptation:** S-141 only writes `lifecycle_state = trial`. No sandbox path through this endpoint — sandbox is the seeded pre-existing Deployment (S-135/S-136).

## Security plan

- **Authz.** `@PreAuthorize("isAuthenticated() and @migrationUploadAuthz.isOwner(#uploadId, authentication)")` on `POST .../bundle` + `GET .../status`. Owner = `migration_upload.user_id == currentUserId`. The current-user lookup uses the existing [`UserPrincipalLookup`](../../../alpenflight/server/src/main/java/ch/alpenflight/platform/tenancy/UserPrincipalLookup.java) / [`JitUserMaterializationFilter`](../../../alpenflight/server/src/main/java/ch/alpenflight/platform/security/JitUserMaterializationFilter.java) stash — no new principal-resolution helper. Non-owner → **404, not 403** (no existence-leak of foreign `uploadId`).
- **Tenancy.** `migration_upload` is pre-Club (uploader-bound only; no `@TenantId`). The `@TenantId = club_id` on each per-Club Phase 3 inside `UnscopedTenantContext` (Phase 2 cross-Club provisioning) → `TenantContext.set(clubId)` per Club-txn entry.
- **Per-upload private-key handling.** Decryption window opens at Phase 2 entry, closes at the orchestrator's `finally`. RSA-private-key + AES-session-key both `byte[]`, `Arrays.fill((byte)0)` + `SecretKeySpec.destroy()` in `finally`. `SQL NULL` on `private_key_ciphertext` in Phase 2's txn AND in the cleanup-pass txn.
- **Plaintext-at-rest defense.** Reuse the S-183 ArchUnit rule (bans `Files.createTempFile`, `File.createTempFile`, `FileOutputStream`, `MappedByteBuffer`) and **extend it for this module** to ban `ByteArrayOutputStream`, `IOUtils.toByteArray`, `InputStream.readAllBytes` on the bundle-stream surface. `spring.servlet.multipart.enabled = false` on this endpoint (also banned at controller level — defense in depth).
- **Plaintext-marker test.** Plant a 32-byte high-entropy marker (`MigrationLeakMarker.VALUE`) in a synthetic NDJSON row's `notes` field; after ingest assert marker appears ONLY in the expected `t_person.notes` row, and is absent from `/tmp`, `/var/tmp`, JVM `java.io.tmpdir`, the configured multipart-resolver dir, and the Postgres `$PGDATA` dir read via `pg_ls_dir` / `pg_read_binary_file`. Belt-and-braces: a `jcmd GC.heap_dump` post-wipe grep catches a missed `byte[]` zeroize.
- **Bundle is UNTRUSTED.** Even though encrypted under our public key (origin "someone who held our PEM"), the JAR ran on the customer's hostile box. Treat manifest + NDJSON as adversarial: zip-slip-style entry-name check, decompression-bomb cap, Jackson `StreamReadConstraints`, `SCHEMA_VERSION_MISMATCH` → 400 (not 500).
- **C14 reaffirmation.** `t_user` has no `password_hash` column (S-052 dropped). A hostile bundle planting `legacy_password_hash` is structurally blocked by the schema; S-183 mapper has no field binding. S-141 does not validate.
- **Telemetry field-set.** `migration.ingest_completed` = `{uploadId, deploymentId, clubCount, durationMs, entityRowCounts: {person: N, ...}}`. **No** names / emails / dates of birth in funnel events. Counts only.
- **Audit log (S-027 stream).** Distinct from funnel: ingest lifecycle gets `MIGRATION_INGEST_STARTED` / `_COMPLETED` / `_FAILED` events with the authenticated Keycloak `sub` as actor + `uploadId` as target. Per-row CRUD audit entries set `system_actor = true` per the hand-off above; actor on those is the migrator service identity, not the human.
- **Rate / abuse.** One in-flight upload per user (S-140 state machine). 2 GB cap via Tomcat `maxPostSize` + servlet `multipart.max-request-size` (multipart is OFF here but the property still affects body-size limits in some configs — set both). 413 on oversize per AC §1. Slowloris bounded by Tomcat `connectionUploadTimeout` (operator tunable; recommend ≥ 30 min total / 60 s idle in deploy docs).
- **OWASP delta:** A01 covered above; A02 AES-GCM auth-tag IS integrity; A04 streaming-only is the insecure-design control; A08 same; A03/A10 N/A.

## Test plan

- **Parity strategy.** Transport-only at this story: `tests/migration/upload-and-ingest.spec.ts` exercises encrypted upload → streaming decrypt → manifest read → handoff to S-183 mappers → S-138 provisioning. Asserts 200 + `{deploymentId, clubIds}` + `consumed_at` set + `private_key_ciphertext` NULL. **Row-level faithfulness (FK orphans, sentinel-value parity, soft-delete invariant) is owned by S-183's parity oracle** (`tests/migration/schema-parity.spec.ts`) — do NOT duplicate. Until S-139's JAR ships, parity_test uses the in-process producer-library substitute (same fallback S-183 uses).
- **Unit (~6):** `MigrationUpload` state-machine transitions; `StreamingDecryptPipeline` chunk composition with fake `InputStream` asserting "no plaintext escapes the boundary before GCM tag verifies"; `ManifestReader` happy + `SCHEMA_VERSION_MISMATCH` + missing-entity.
- **Integration (`@SpringBootTest` against `SharedPostgresContainer`):**
  - Happy path (1 Club, ~50 rows across 3 entities): 200 + Deployment + Clubs + rows + handshake consumed + key NULL + telemetry events in expected order.
  - **Plaintext-leak grep test** (AC §3 + Vision §2 NFR): grep paths listed in the security plan.
  - Corrupt-bundle cases: `BUNDLE_DECRYPT_AEAD_TAG_FAILED`, `BUNDLE_TAR_PARSE_FAILED`, `SCHEMA_VERSION_MISMATCH`, `BUNDLE_MISSING_ENTRIES`.
  - Concurrency: user with existing `active` Deployment → 409 (S-138 path); double POST against same `uploadId` → 409 `upload_in_progress`; fresh-handshake-required after `failed` (POST against consumed `uploadId` → 410 or 409).
  - **Multi-Club bundle with mid-second-Club failure** → assert ZERO Clubs persisted + Deployment torn down (cleanup-pass verification). The replacement for the old "single-txn atomicity" coverage.
  - Status endpoint while ingest in flight: returns current phase + entity + progress.
  - S-138 reconcileKeycloak: Mockito spy on the real `DeploymentProvisioningService` bean asserts one `provision` call + one `reconcileKeycloak` call with correct args. Do NOT re-test S-138's retry behavior.
- **e2e (Playwright `tests/migration/upload-and-ingest.spec.ts`):** ONE happy-path signed-in user — load `/migrate/start`, POST encrypted bundle (test fixture pre-encrypted under deterministic test keypair), poll status to `complete`, land on `/dashboard`. No corrupt-bundle variants here (covered in integration).
- **Test infra deltas:** `MigrationBundleTestFactory` (deterministic encrypted-bundle producer, regenerated per run, NOT checked in); `MigrationLeakGrepHelper` (enumerates disk paths + `pg_ls_dir` / `pg_read_binary_file` queries); `RecordingTelemetrySink` (in-memory `ApplicationEventListener` for ordered `migration.*` event assertions; reusable with S-140); `MigrationHandshakeTestFixture` Spring `@TestConfiguration` shared with S-140's tests (seeds a fresh handshake row + matching private key); `@SpringBootTest` profile that dials `multipart.max-request-size = 1KB` for the cheap 413 assertion.
- **Out of scope for this story's tests:** row-level data faithfulness / FK orphan sweep / sentinel diff / soft-delete invariant (S-183); mapper coverage + `MapperVsSchemaCompatibilityTest` (S-183); handshake issue/supersede/TTL (S-140); Deployment-provisioning internals + Keycloak reconcile retry (S-138); JAR-side encryption / tar layout / NDJSON serialization (S-139); FlightCrew JMH bench (S-183).

## Performance plan

- **Latency budget (2 GB bundle, 15 min wall ceiling).** HTTP receive ~150-200s on 100 Mbit (the dominant variable cost) → AES-GCM ~2s (AES-NI) → tar/GZIP ~10-15s → mapper-throughput @ S-183's JMH floor 200K rows/s → **per-entity `INSERT … SELECT` rewrite is the surprise hot spot** (25M-row `flight_crew` rewrite with FK joins ≈ 300-500s). Allocated 400s for rewrite, 200s receive, 80s headroom. Tight on the largest known legacy DB.
- **Backpressure.** Fixed-chunk pull through `CipherInputStream → GZIPInputStream → TarArchiveInputStream → BufferedReader.lines() → Mapper → CopyManager.copyIn(OutputStream)`. Heap budget ≤ 512 MB on the prod VPS. The banned-pattern ArchUnit rule (Security plan §) prevents the bundle-sized-buffer regression.
- **Temp-table planner trap.** `CREATE TEMP TABLE legacy_id_map_<entity> ... ON COMMIT DROP PRIMARY KEY(legacy_guid)`. **`ANALYZE legacy_id_map_<entity>`** after each COPY, before the dependent `INSERT … SELECT … JOIN`. Postgres does NOT auto-analyze temp tables → planner would see 0 rows → nested-loop disaster.
- **Progress-update throttling (load-bearing).** Per-row UPDATE on `migration_upload.phase_*` on 25M `flight_crew` rows would dominate wall + bloat WAL. Throttle to once per `EntityType` boundary OR once per 2s wall, via a `ProgressReporter` with `lastEmittedAt` (REQUIRES_NEW per emission so polling sees forward progress).
- **Connection / pool.** The orchestrator holds ONE Postgres connection across Phase 2 + Phase 3 (per-Club commits run on this same connection so the connection-scoped Person sub-map TEMP TABLE survives txn boundaries). Connection is released after Phase 4's post-commit reconcile. Vs the old single-txn shape, per-Club commits still give the WAL-pressure win (each Club is a discrete commit) without losing the cross-tenant sub-map. HikariCP pool ≥ 10. Tomcat servlet thread held only during HTTP-receive + ingest; concurrent uploads expected ≤ 1-2.
- **AES-NI assumed.** Boot-time WARN if `Cipher.getInstance("AES/GCM/NoPadding").getProvider()` is `SunJCE` on a host with no `aes` in `/proc/cpuinfo`. Don't 503 — log loudly.
- **Performance test gating.** Fast path: 1-Club / ~50-row synthetic against Testcontainers Postgres, wall < 60s, assert no `Files.createTempFile` invocations (runtime FS-watch), peak heap < 256 MB (JFR snapshot). `@Tag("slow")`: 2 GB synthetic via S-183's `LegacyFixtureSeeder` (once it lands), wall < 900s, peak heap < 512 MB, zero OOM, `migration_upload` UPDATE count < 50. Excluded from `./gradlew test`. Per-mapper regression coverage is owned by S-183's JMH.

<!-- modernize-refine: end -->
