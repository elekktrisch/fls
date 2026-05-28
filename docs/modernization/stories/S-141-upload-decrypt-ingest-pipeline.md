---
id: S-141
title: Encrypted-bundle upload + streaming decrypt + ingest pipeline
epic: E-15
status: todo
depends_on: [S-016, S-138, S-140]
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
  - **User↔Person link preservation.** Each ingested `t_user` row whose legacy `User.PersonId` is non-null carries the matching new `t_person.id` in its `person_id` column. The per-entity mapping in S-016 owns the legacy-id → new-uuid map for the bundle; the User mapping looks the legacy `PersonId` up in the Person sub-map and sets the new `person_id`. Asserted by an IT that ingests a fixture bundle where every legacy User has a PersonId, then reads back via the `/api/v1/users` admin surface and confirms `personId` matches the per-club Person inventory.
estimate: L
adr_refs: [0018, 0019]
parity_test: tests/migration/upload-and-ingest.spec.ts (new; round-trip from S-139 JAR through this pipeline)
refined: true
refined_at: 2026-05-28
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer, performance-engineer]
github_issue: 155
github_pr: 156
---

## Context
Vision C28 + C32 + §2 NFR (plaintext-at-rest exposure) define the security posture. C34 specifies the data model: one upload → one Deployment containing 1..N Clubs.

This story owns the server-side pipeline; S-139 owns the client-side write; S-016 owns the schema-mapping logic shared between them.

The streaming requirement is load-bearing: a 2 GB plaintext bundle decrypted to a temp file is both a memory-class risk on a single-VPS deployment AND a security risk. Streaming hybrid decrypt avoids both.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Endpoint scaffold (Spring `@PostMapping` with `InputStream` body).
- [ ] Decrypt pipeline: header parse, RSA unwrap, AES-GCM stream-decipher, tar inflate.
- [ ] Manifest reader: enumerate Clubs in bundle, hand off to provisioning service (S-138).
- [ ] Per-entity ingest dispatch using the shared `alpenflight/migration-bundle/` library (S-016).
- [ ] Transactional boundary: full ingest is one Postgres transaction.
- [ ] Status-polling endpoint + progress state.
- [ ] Plaintext-leak test fixture + assertion.
- [ ] Idempotency: re-uploading after `failed` requires a fresh handshake.

## Notes
- A 2 GB bundle in one transaction is a lot; refine whether to split per-Club or per-entity with explicit rollback (operator preference + measurement against the prod-class VPS).
- Decrypt pipeline holds the private key in memory only during the upload window. Refine: secure-byte-array implementation that zeroes on close.
- Per memory `[[feedback-re-runnable-over-frozen-docs]]`: parity test reads from a seeded legacy SQL Server (via S-139's JAR) and writes to a fresh Deployment — re-runnable in CI, not a committed bundle.

<!-- modernize-refine: start -->

## Design notes

Terminal pipeline: stream the encrypted bundle from the wire to Postgres, no plaintext landing on disk. The hard decisions cluster around the transactional boundary, the per-bundle scope of the legacy-id map for AC #10, and the ordering of DB commit vs Keycloak group create.

- **Transactional boundary: one txn per Club, with a parent `migration_run` row tracking partial progress.** (Operator-grilled, 2026-05-28.) Each Club in the bundle is its own txn. On Club-N failure, Clubs 1..N-1 stay committed; the user re-uploads only from Club N onwards (S-016's mapping makes that re-runnable). Better autovacuum / WAL behavior than one-bundle-one-txn; better retry UX than start-over-from-zero. **AC #4 needs to be reworded from "single Postgres transaction" to "one txn per Club with `migration_run` parent" — flag as a small AC edit at finalize time.** New table `migration_run`: `(id uuid PK, migration_upload_id uuid FK, deployment_id uuid FK, total_clubs int, completed_clubs int, current_club_legacy_id text NULL, state {pending|in_progress|kc_pending|complete|failed}, started_at, finished_at)`. State machine lives in Java on a `MigrationRun` aggregate per ADR 0022 D2 — no CHECK constraints encoding states.
- **Streaming pipeline: chained `InputStream`s, no Channels.** `HttpServletRequest.getInputStream()` → header-frame `DataInputStream` → BouncyCastle `CipherInputStream(AES-256-GCM)` (session key from RSA-unwrap of header) → commons-compress `TarArchiveInputStream` → per-entry Jackson `MappingIterator` (NDJSON). Servlet stack, blocking I/O. Session key + RSA private key held in a `SecureBytes` wrapper that overwrites on `close()`.
- **JPA persist mode: `StatelessSession` for bulk entity streams; regular `EntityManager` for Deployment + Club + `migration_run` rows.** Hibernate 7 `StatelessSession` skips L1 cache + dirty checking + cascade — the right primitive at 5M+ rows. `hibernate.jdbc.batch_size=500`, `hibernate.order_inserts=true`. (Detail in `## Performance plan`.) Domain events from the ingest path are intentionally suppressed (no listener cares about migration inserts; firing them would explode the txn).
- **Per-bundle `legacy_id_map`, per-entity-type temp Postgres table.** Lives for the duration of the ingest only (`ON COMMIT DROP`). Per AC #10: the Person sub-map is keyed on the legacy Person GUID and is shared across all Clubs in the bundle — a multi-Club bundle that lists the same pilot under two Clubs inserts one `t_person` row + two `t_person_club` rows. Cross-bundle Person dedupe is **out of scope** (operator-grilled, 2026-05-28): each bundle's legacy GUIDs are local; collisions across bundles are accidental, not by design. Manual merge via S-051 lookup if it ever matters. No `UNIQUE (email)` on `t_person`.
- **KC group create ordering: DB-commit first, KC-create second, `migration_run.state = kc_pending` between.** Blocking the txn on a network call to Keycloak couples ingest atomicity to KC availability — wrong trade-off for a 15-min pipeline. On KC failure, leave `kc_pending` + emit `migration.ingest_kc_pending` telemetry + surface a 207-style response with `deploymentId` + `kcLinkagePending=true`. Hourly retry job (sibling to `MigrationHandshakeExpiryJob`) reattempts. Operator-visible via S-167 dashboard. Never rollback the DB — the data is good; the auth linkage is recoverable.
- **Funnel telemetry: emit via S-147's `FunnelTelemetry.emit(eventId, props)`.** S-147 ships the convention; if not landed by implementation time, the placeholder is a `INFO funnel event=… …` structured-log line with the same JSON shape — swap to the helper without changing call sites.
- **Schema deviation from ADR 0022 D2: none.** All state machines (`migration_upload.state`, `migration_run.state`) are Java-enforced on aggregates. No CHECK constraints, no generated columns, no triggers. `t_user.person_id` is a plain FK with no DB-level cross-tenant carve-out — Person being cross-tenant is enforced by Hibernate `@TenantId` placement (S-051), not by constraint.

## Edge cases & hidden requirements

- **Failed-upload tombstone vs erase.** Mid-stream failure: txn rolls back, no half-provisioned Deployment exists. User restarts at `/migrate/start` and gets a fresh `uploadId` + keypair; the `failed` `migration_upload` row is retained for telemetry, never reused.
- **Two-tab concurrent uploads.** S-140 supersede targets `awaiting_upload` only. A second handshake fired while the first upload is in-flight (`decrypting` / `ingesting`) must be rejected with 409 — superseding a row whose private key is currently mid-decrypt would crash the live pipeline.
- **Manifest ↔ stream mismatch.** Manifest declares 3 Clubs, tar has 2 (or vice versa) → fail-fast `bundle_manifest_mismatch` before any DB writes. Stream references `legacy_club_id` not in manifest → same.
- **Orphan legacy `User.PersonId`.** A legacy `User.PersonId` that resolves to a Person not in the bundle at all (legacy referential drift) → fail with `orphan_person_reference`. Silent null would violate AC #10's intent.
- **Time-gates preserved, not reset.** Ingested Flights carry legacy `LockedAt` / `BilledAt` timestamps as-is; sacred-cow time-gate clocks (2-day-lock, 3-day-bill) evaluate against legacy timestamps, not `now()`. Otherwise a 4-year-old flight ingested today becomes mutable for 2 days — invariant violation.
- **Legacy audit_log actor remap.** Legacy actor `User.Id` references remap through the User sub-map; legacy users not in the bundle (deleted-but-referenced) get a sentinel `actor_legacy_user_id` preserved as-is rather than dropping the row — historical audit integrity matters more than referential cleanliness.
- **Reference-data collision with S-138.** S-138 bootstraps countries + default flight-types + default cost-balance per Club; bundle may also carry these. **Bundle wins** — S-141 runs before the S-138 reference-data bootstrap step, OR S-138's bootstrap uses `INSERT … ON CONFLICT DO NOTHING` keyed on `(club_id, code)`. Otherwise the user sees duplicate default flight-types and loses customized labels.
- **`@TenantId` resolution during ingest.** Caller has no `clubId` claim yet (this story creates tenants). Pipeline runs under a `MigrationIngestTenantContext` (security-plan owns the implementation); `@TenantId` value per insert comes from the manifest-derived per-Club row currently being built, not from any principal claim.
- **UUID v7 generation for ingested rows.** Per ADR 0019, aggregates instantiated via factory methods so `@UuidGenerator` fires; legacy GUID lands in `legacy_id_map_<entity>.legacy_guid` for FK rewriting. Never `INSERT … (id) VALUES (legacy_guid)` — would preserve legacy-v4 distribution and defeat v7's B-tree locality win.

## Security plan

- **Authn/Authz at `/api/v1/migrations/{uploadId}/bundle`.** `@PreAuthorize("isAuthenticated()")` + service guard `migration_upload.created_by_user_id == principal.sub`. **Reject 404 on mismatch** (not 403) — don't leak existence of foreign uploads. State must be `awaiting_upload`; else 409. No admin-bypass.
- **`MigrationIngestTenantContext`.** Null-tenant context that the resolver recognizes; writes allowed only via the migration-ingest service package (ArchUnit guard). `@TenantId` populated from the per-Club row being built, not from any principal claim. Document the escape hatch in S-024's leakage CI manifest so the test doesn't flag it as a regression.
- **`BundleSizeLimitFilter` (preferred over Tomcat connector / Spring multipart).** `OncePerRequestFilter` registered at `Ordered.HIGHEST_PRECEDENCE + 10`, before Spring Security. Wraps `getInputStream()` in a counting wrapper; throws `PayloadTooLargeException` at 2 GB + 1 — never buffers. Reverse-proxy `client_max_body_size 2200M` is a defense-in-depth outer fence (S-041 owns; pin alignment in S-041's test plan). Confirm `spring.servlet.multipart.enabled=false` — this is `application/octet-stream`, not multipart.
- **Tar entry-name allowlist.** `entry.getName()` consulted only for dispatch to a per-entity NDJSON parser via enum match (`{manifest.json, clubs.ndjson, persons.ndjson, users.ndjson, …}`). Names with `/`, `\`, `..`, leading `.`, null byte, or length > 64 → `BundleSchemaException`, rollback. Duplicate entry names within the bundle → reject (parser-confusion).
- **Compression-bomb watermark.** Wrap inflate in a `CountingInputStream`; abort at 4 GB decompressed (2× ceiling). Per-NDJSON-line cap 1 MB. Constants pinned in `MigrationLimits`.
- **Private-key memory hygiene.** `SecureBytes` wrapper (mutable `byte[]` + `Arrays.fill(key, 0)` on close). Three wipe sites mandatory, all in `finally`: (a) success post-commit, (b) any exception path, (c) `AsyncRequestTimeoutException` handler. `WeakReference`-based leak detector in tests fails the build if any path misses. `@JsonIgnore` + custom `toString() = "<redacted>"`. Logback redaction pattern masks `privateKey|sessionKey|masterKey|aesKey`. Never put raw key bytes in `MDC`.
- **No-plaintext-at-rest guarantee.** Forbidden sinks: `/tmp`, `/var/tmp`, JVM working dir, Tomcat `work/`, `java.io.tmpdir`, heap-dump dir. Code-review checklist: any `File` / `Files.createTempFile` / `FileOutputStream` in the ingest call graph is a build-break. commons-compress + BouncyCastle configured without disk-spool options.
- **Bundle replay.** `migration_upload.bundle_sha256` computed via a teed `DigestInputStream`; stored post-commit. Same bytes resubmitted by any user → 409 `BUNDLE_ALREADY_CONSUMED`. Same user resubmitting after `failed` requires a fresh handshake (S-140 invariant). The keypair per-upload is the real integrity gate; the digest is opportunistic.
- **Audit events (S-027).** Per ingest: `migration.deployment.created` (counts only — `{persons:N, users:M, flights:K}`, no field values), `migration.club.created` × N (one per Club, `{clubId, deploymentId, legacy_club_id}`), `migration.ingest.failed` if applicable (`error_code` + `phase` ∈ `{decrypting, parsing, provisioning, ingesting}` + `entity_name`). Per-entity row creation does NOT emit individual audit events — document explicitly in S-027's catalog. For AC #10: `migration.user_person_links.created` is a **batch summary** event carrying `{deploymentId, link_count, sample_legacy_pairs[0..4]}` with `{legacy_user_id, legacy_person_id}` only. No names, no emails. Per-link rows not individually audited (volume + PII).
- **PII redaction.** `AuditPayloadBuilder` is a type-safe builder whitelisting allowed fields per event type. Whole bundle Person fields never reach the audit blob. `t_person` retains its S-051 redaction policy.
- **OWASP deltas** (only what changes here): A01 — `uploadId` is auth anchor, not capability (`created_by_user_id` checked). A04 — no-plaintext-at-rest is the design invariant; enforced by code-review + plaintext-marker test. A05 — `BundleSizeLimitFilter` ordering before Spring Security so oversized requests never allocate auth state. A08 — RSA-unwrap + AES-GCM auth-tag is the integrity gate; no separate bundle signature.
- **Master-key rotation.** Deferred. Operator runbook: drain in-flight uploads (force-fail `awaiting_upload` / `decrypting`), re-deploy with new key, post-rotation uploads need fresh handshake. Not a story today.

## Test plan

**Pyramid.**
- Unit (~6): manifest parser, `SecureBytes` zeroing, `migration_upload` + `migration_run` state-machine guards, legacy-id-map prefetch helper, marker-grep helper, per-phase status emitter.
- IT (~10, Testcontainers Postgres + BouncyCastle): happy-path 50 MB bundle; multi-Club (3 Clubs, shared Person → one `t_person` + three `t_person_club`); **AC #10 IT** (shared Person across Clubs, multi-User PersonId resolution via the per-bundle Person sub-map); failed-decrypt rollback (DB + key wipe); plaintext-leak marker grep; concurrent `/bundle` race; `@TenantId` filter parity across the 3 Clubs; 413 oversize reject; status-poll phase sequence; per-Club partial-rollback (Club-2 fails, Club-1 stays committed, retry resumes from Club-2).
- E2E (1 Playwright): user uploads encrypted bundle → progress poll → lands on new Deployment dashboard. Bundle generated at setup by calling S-139's JAR against a seeded SQL Server container.
- Parity (1, JUnit `@Tag("parity")`, excluded from default `./gradlew test`): seeded legacy SQL Server → S-139 JAR → S-141 ingest → diff per-entity row counts (exact) + cell-by-cell on `User.personId`, `Person.legacyId`, `Club.legacyId`, `PersonClub` cardinality. **Cutover gate: zero-delta on row counts + the four sentinel columns.** Excluded from parity: generated UUID v7s (per-row, expected), `created_at` / `updated_at` (regenerated), `@TenantId` column (greenfield).
- Perf (1, `perf/` suite, nightly only, **out of default CI**): 2 GB bundle ≤ 15 min on prod-class VPS (vision §2 NFR); peak heap < 4 GB; Postgres WAL volume < 20 GB; `pg_stat_bgwriter.checkpoints_req` flat post-ingest.

**Scenarios worth pinning.**
- AC #10 IT: fixture has at least one Person referenced by multiple Users AND multiple Clubs. Assertion is `t_user.person_id` resolves to the same `t_person.id` reachable via `/api/v1/users` admin surface, AND the Person carries N `t_person_club` rows. Don't just check non-null — assert the legacy mapping (via the per-bundle Person sub-map) was actually consulted.
- Plaintext-leak: plant a UUID-shaped marker in (i) a `t_person.last_name`, (ii) a `t_club.name`, (iii) raw bytes inside an NDJSON line. Post-ingest, `grep` over: app's working dir, `java.io.tmpdir`, container's `/tmp` and `/var/tmp`. Pass: marker found only in Postgres data dir + original encrypted bundle. Skip process memory (not feasibly scopable on JVM). Run with `-Xmx256m` + `System.gc()` + 100 ms sleep to force any buffered plaintext to surface.
- Concurrent `/bundle` race: two threads POST simultaneously with same `uploadId`. One must 200; the other must 409 `upload_in_progress`. Align via `CountDownLatch` at the lock-acquire call site; assert via response 409, not via timing.
- Failed-decrypt rollback: inject a tampered AES-GCM auth tag mid-stream. Assert: zero rows in `t_club` / `t_person` / `t_user`; `migration_upload.state = failed`; `migration_upload.private_key_ciphertext IS NULL`; retry returns 409 `requires_new_handshake`. Key-wipe assertion is separate from txn rollback (key wipe lives in `finally`, not the transactional path).
- Per-Club partial rollback: 3-Club bundle, Club-2 has a corrupt NDJSON line. Assert: Club-1 fully committed, Club-2 rolled back, Club-3 never started, `migration_run.state = failed`, `migration_run.completed_clubs = 1`. Re-handshake + re-upload skipping Club-1 completes cleanly.

**Fixtures.**
- `bundle-tiny.bin` (~50 KB, 1 Club, 5 Users, 5 Persons, fixed marker) — built at IT class-setup by invoking S-139's producer library against an in-memory legacy schema seeded from a SQL script.
- `bundle-multi-club.bin` (3 Clubs, shared Person across Clubs) — same generator, different seed.
- `bundle-tampered.bin` — derived from `bundle-tiny.bin` by flipping a byte in the AES-GCM payload; rebuilt per-test for determinism.
- `bundle-2gb.bin` — generated only by perf workflow, not committed.
- No Mailpit / Keycloak side-effects in IT; e2e reuses S-174 / S-175 harnesses.

## Performance plan

- **Streaming buffers ≈ 150 KB total.** Servlet input 64 KB, AES-GCM block 16 KB, tar 64 KB, Jackson default 8 KB. Flat regardless of bundle size. `spring.servlet.multipart.enabled=false`; `server.tomcat.max-swallow-size=-1`.
- **Bulk-load: `StatelessSession` + JDBC `executeBatch`, `batch_size=500`.** Justified over `EntityManager.persist` (OOMs at 5M rows even with `flush+clear`) and `COPY FROM STDIN` (bypasses `@TenantId`, `@UuidGenerator`, typed-ID `@AttributeConverter` — sacred-cow risk). Set `hibernate.jdbc.batch_size=500`, `hibernate.order_inserts=true`, `hibernate.order_updates=true`, `hibernate.jdbc.batch_versioned_data=true`. Flush per entity-stream end, not per row.
- **`legacy_id_map`: per-entity-type temp Postgres table** (`CREATE TEMP TABLE legacy_id_map_<entity> ON COMMIT DROP`). In-memory HashMap wrong at worst-case sizing (100M entries ≈ 5 GB heap on a 4–8 GB JVM). Populate via `COPY` (the one place COPY is correct — not a domain entity, no `@TenantId`). Resolve via batched `WHERE legacy_guid = ANY(?::uuid[])` per 500-row batch — **no per-row FK lookups; every cross-entity ID resolution is a batched `ANY(?)` against the per-type temp table.**
- **Deferrable FKs: `SET CONSTRAINTS ALL DEFERRED`** at txn start. Postgres validates at commit, single index scan per FK. **S-012 / S-013 / S-014 follow-up:** DDL needs `DEFERRABLE INITIALLY IMMEDIATE` on FK declarations. Do NOT use `DISABLE TRIGGER ALL` — superuser-level hammer with blast radius beyond FKs (skips RLS, leaves planner stats stale). Secondary indexes stay live: UUID v7 PK locality keeps the insert-side cost at ~20–30%.
- **Per-Club txn (grilled).** One `StatelessSession` per Club; `CurrentTenantIdentifierResolver` called once per Club, cached for that session's lifetime. Connection-pool impact: each Club's txn is short-lived (~minutes for typical Clubs); HikariCP's default 10 connections leave 9 for normal traffic. Tune `idle_in_transaction_session_timeout` only on the ingest connection (not globally) — per-Club commit timing naturally avoids the multi-minute-idle case.
- **N+1 mitigation, restated for emphasis.** AC #10's User → Person FK lookup, FlightCrew → Flight + Person, AircraftReservation → Aircraft / pilot_person / instructor_person all follow the same `WHERE legacy_guid = ANY(?::uuid[])` batched prefetch shape per 500-row batch. Per-row `legacy_id_map.get(uuid)` would generate millions of roundtrips — fail loud in code review if encountered.
- **Memory budget (8–16 GB VPS, `-Xmx4g` JVM).** Pipeline buffers ~150 KB + Jackson token stack + StatelessSession batch buffer ~1 MB + JDBC PreparedStatement batch buffer ~1 MB + RSA key material ~4 KB ≈ **~5 MB steady-state per ingest**. Plaintext-leak test (security plan) doubles as a memory-leak smoke — if any layer accidentally buffers the decompressed stream, the heap dump shows it.
- **Perf benchmark plan.** Smoke on dev box: 1-Club / 5K Persons / 50K Flights / 250K FlightCrew / 5K Users → ingest < 90 s, peak heap < 1 GB. Prod-class VPS (4 vCPU / 8 GB RAM, per ADR 0010): 1-Club / 50K Persons / 5M Flights / 25M FlightCrew → < 15 min, peak heap < 4 GB, WAL volume < 20 GB. Concurrent-tenant smoke: ingest Club A while Club B reads — Club B p95 GET latency stays within 2× baseline. AC #10 prefetch sanity: 5K-User batch < 50 ms per 500-User batch.

<!-- modernize-refine: end -->
