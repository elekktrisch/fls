---
id: S-139
title: Legacy FLS export tool — single-file Java JAR (build + CLI + JDBC + bundle writer + hybrid encrypt)
epic: E-15
status: todo
depends_on: [S-016, S-140, S-141, S-187a]
integration_base: integration/migration
refined: true
refined_at: 2026-05-31
refined_specialists: [requirements, solution, qa, security, performance]
context7_last_checked: 2026-05-31
github_issue: 181
github_pr: 182
acceptance:
  - A new Gradle module under `alpenflight/migration-tool/` builds a single-file fat-jar (`alpenflight-export.jar`).
  - The JAR's `main()` accepts CLI options: `--jdbc-url <url>`, `--user <name>`, `--password <secret-or-prompt>`, `--public-key-file <path>` (PEM-encoded RSA-4096 public key, as obtained from S-140), `--output <path>` (default `./alpenflight-export-<timestamp>.enc`), `--verbose`, `--dry-run` (skips encryption + write; prints bundle stats only).
  - JDBC driver bundled: SQL Server `mssql-jdbc`. Read-only connection enforced: the JAR sets `ApplicationIntent=ReadOnly` on the JDBC URL even if the user forgot.
  - Bundle writer streams the legacy schema to a temp `tar.gz` archive containing: `manifest.json` (schema version, source DB metadata, generation timestamp, sha256 of each entity stream), one NDJSON file per entity table (clubs, persons, aircraft, flights, reservations, planning-days, accounting-rule-filters, deliveries, articles, locations, etc. — the entity set comes from S-016's schema-mapping inventory).
  - Encryption per ADR 0019: a one-time AES-256-GCM session key encrypts the archive (streaming); the session key is then RSA-OAEP-wrapped under the user-supplied public key; the output file's layout is `[header][wrapped-key][iv][ciphertext][tag]` with a magic byte sequence + format version in the header.
  - On non-zero exit: stderr emits a structured error code + remediation hint (e.g. `JDBC_CONNECT_FAILED`, `PUBLIC_KEY_INVALID`, `DISK_FULL`).
  - The JAR runs on JRE 17+ (matches the server JRE from ADR 0001).
  - A README in `alpenflight/migration-tool/` documents the usage including a worked example for a typical FLS deployment.
estimate: L
adr_refs: [0001, 0019]
parity_test: tests/migration-tool/jar-export.spec.ts (new — integration test runs the JAR against a seeded legacy SQL Server in Testcontainers)
---

## Context
Vision C28 mandates a single-file Java JAR as the legacy export transport. The schema-mapping logic (which entities, which columns, which transformations) is owned by S-016 (the existing migration-script story); this story owns the *transport*: CLI ergonomics, JDBC read, bundle archive shape, hybrid encryption per ADR 0019.

Sharing code with S-016: extract the schema-mapping into a `alpenflight/migration-bundle/` library consumed by both this JAR and the server-side ingest pipeline (S-141). One source of truth for "what's in the bundle".

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Bootstrap Gradle module `alpenflight/migration-tool/` with the Shadow plugin (fat-jar build).
- [ ] CLI scaffolding: picocli is the boring choice — refine.
- [ ] Bundle archive writer: tar.gz + manifest.json + per-entity NDJSON streams.
- [ ] Hybrid-encryption per ADR 0019: AES-GCM streaming + RSA-OAEP key wrap.
- [ ] Testcontainers-based integration test against a seeded SQL Server matching the legacy schema.
- [ ] README + worked example.
- [ ] CI: build the JAR on every push; publish as a GitHub-release artifact on tagged releases (refine — operator's call on release cadence).

## Notes
- Per memory `[[feedback-re-runnable-over-frozen-docs]]`: the schema parity is verified by re-running this JAR against a seeded legacy DB in CI, not by committing a frozen bundle.
- The JAR explicitly does NOT call back to AlpenFlight during export — it's air-gappable. The user obtains the public key out-of-band (via the AlpenFlight UI, S-140) and pastes the file path. Air-gap support is a customer-comfort signal: their data leaves their DB only on their command, and only encrypted.
- GraalVM native-image: deferred. JIT cold start is fine for the bundle-write step which is bound by DB read time anyway. Note in the README.

<!-- modernize-refine: start -->

## Design notes

S-139 owns **transport only** — entity/column shapes belong to S-013/14/16 + the migration-bundle mappers; no destination schema here. New `:migration-tool` Gradle module (Shadow fat-jar `alpenflight-export.jar`), `settings.gradle.kts` include, `implementation(project(":migration-bundle"))`, bundles `mssql-jdbc`, picocli CLI, JRE 17+ (ADR 0001), forces `ApplicationIntent=ReadOnly`.

**Crypto relocation — S-139 owns it (operator-confirmed, S-139a grill).** Move `BundleHeader`, the `MigrationBundleCipher` port + `TinkMigrationBundleCipher`, `PemEncoders`, and the ALPF format consts from `server` → `migration-bundle/src/main` as one pure-JDK/Tink unit (no Spring; server re-wires via `@Bean`). migration-bundle main gains the Tink + commons-compress deps. The jar reuses `wrapSessionKey` + `newEncryptingStream` verbatim → byte-exact inverse of the server decrypt. One source of ALPF truth.

**Envelope = ALPF v1** (the AC's raw-AES-GCM `[header][wrapped-key][iv][ciphertext][tag]` is STALE — see flags): `MAGIC "ALPF"(4) + version(1, =BundleHeader.CURRENT_VERSION) + uint16 wrappedKeyLen + RSA-OAEP-SHA256-wrapped 32-byte AES-256 session key + Tink StreamingAead(AES256_GCM_HKDF_4KB) body`. Wrap = `RSA/ECB/OAEPWithSHA-256AndMGF1Padding`, MGF1-SHA256.

**uploadId AAD binding (grilled → combined handshake file).** The server binds `uuidBytes(uploadId)` as the StreamingAead associated data (and RSA wrap); without it the server fails the AEAD tag. So the jar needs the uploadId, not just the PEM. Decision: S-140's SPA download becomes a **combined handshake artifact** (uploadId + publicKeyPem); the jar takes `--handshake-file` and feeds the PEM to the wrap + the uploadId as AAD. One artifact = can't mismatch. Requires an S-140 follow-up (tracked below).

**Manifest + id-map authorship.** Policy is intrinsic to the entity — the jar derives `entityPolicies`/`unmappedReason` from `KnownMappers` + `EntityPolicy`, then constructs `Manifest` to self-validate full `EntityType` coverage + the tenant-bypass allow-list before writing entry 0. `ClubDeclaration` rows + per-stream sha256 come from the legacy read. **id-map split:** SYSTEM_GLOBAL entities ship as NDJSON `(legacy_guid, lookup_key)` rows — the *server* resolves lookup_key → V2-seed UUID; the jar emits `legacy_id_map/<E>.pgcopy` (legacy_guid→legacy_guid identity, ADR 0019 GUID-preservation) only for FULL_PORT. The jar NEVER emits V2 seed UUIDs (it can't know them); the CLUB map is server-seeded.

**Producer SELECT bindings — relocate parity→main.** Move `MapperLegacyBindings` from `src/parity` → `src/main` so the jar and the parity `ProducerHarness` share one SELECT registry (else parity proves a different producer than ships). The registry is the vertical-slice 5 today; **S-187a completes the rest → S-139 depends on it.**

**Streaming pipeline.** Per entity: forward-only read-only `ResultSet` (mssql-jdbc `responseBuffering=adaptive`, explicit `setFetchSize`) → `Mapper.writeNdjson` → tar entry, sha256 via `DigestOutputStream` as it writes. Because manifest.json is entry 0 but carries per-stream sha256, stream each entity to a temp file (one entity in flight), then assemble the final tar (manifest first) and pipe through StreamingAead → `--output`. Never buffer the whole DB/bundle in heap; do NOT reuse the parity `ProducerHarness`/`BundleStream` (`byte[]`-sized fixtures). `--dry-run` runs the read+hash pass, prints stats, writes nothing, needs no handshake file.

**Distribution (grilled).** CI builds the fat-jar on tag → GitHub Release artifact + published SHA-256 checksum; README documents verification + a worked `java -jar` example. Code-signing (jarsigner/sigstore) deferred (see follow-ups).

**Stale-AC flags for the operator (decompose's edit, not refine's):** envelope layout + `adr_refs:[0019]` (0019 = entity-id-strategy; no ADR specs the envelope — `BundleHeader`/`TinkMigrationBundleCipher` ARE the spec); `--public-key-file` → `--handshake-file`; jar name `alpenflight-export.jar` vs S-139a's `migration-tool-all.jar` (pick one).

## Edge cases & hidden requirements

- **Version pins.** Manifest must stamp `schemaVersion == Manifest.CURRENT_SCHEMA_VERSION` AND the envelope `version` byte; the server rejects a mismatch with distinct UPGRADE/DOWNGRADE codes. Bump both in lockstep with the library.
- **Manifest coverage gate.** Every `EntityType` must appear in `entityPolicies` XOR `unmappedReason` — the jar emits a reason for each skipped entity, never silently omits (the `Manifest` ctor enforces this).
- **Schema drift (grilled → hard-fail).** A mapper-SELECTed column missing on an older legacy install → abort with a structured `SOURCE_COLUMN_MISSING`/`SCHEMA_DRIFT` naming table+column, not a raw `SQLException`. Never skip/NULL — no silent data loss.
- **Export RAW; let S-141 own the invariants.** Orphan FKs and tombstoned/soft-deleted rows are exported as-is (filtering at export diverges the parity oracle). Negative legacy INT PK (Language/ClubState widen to synthetic UUID `(0,int)`) → reject. Non-UTF8 varchar collations → force UTF-8 decode. Pre-epoch/skewed datetimes round-trip via ISO-8601.
- **Credentials off argv** (prompt → env → stdin); `--password` rejects an inline secret. Redact `password=`/`user=` from any logged JDBC URL.
- **Read-only** — force `ApplicationIntent=ReadOnly`; handle a URL already carrying it (no duplicate param) and a user-supplied `ReadWrite` (override/refuse).
- **Output handling** — default `./alpenflight-export-<timestamp>.enc`, mode `0600`, refuse to overwrite without `--force`, write via temp-sibling + atomic rename; delete the partial `.enc` on DISK_FULL/SIGINT/JDBC-drop (a truncated ALPF later surfaces as server `BUNDLE_TRUNCATED`).
- **Exit-code/stderr taxonomy** — stable documented integer codes per category (picocli's default 1-for-everything defeats scripting); mirror server `BundleIngestErrorCode` vocabulary where the user hits the same wall twice (`PUBLIC_KEY_INVALID`, `SCHEMA_VERSION_MISMATCH`) plus producer-local codes (`JDBC_CONNECT_FAILED`, `DISK_FULL`, `OUTPUT_EXISTS`).
- **JRE 17+** requires-JRE; a clear message (not `UnsupportedClassVersionError`) on an older runtime.

## Security plan

Standalone air-gapped CLI — `@TenantId`/`@PreAuthorize`/server invariants N/A. The whole bundle is customer PII; encryption under the S-140 per-upload public key is the only confidentiality control (blast radius bounded to one upload).

- **Crypto = Tink reuse, no hand-rolled JCA.** Pin primitives as constants not config: body `AES256_GCM_HKDF_4KB`, wrap RSA-OAEP-SHA256/MGF1-SHA256, envelope ALPF v1. The version pin IS the downgrade defence — never expose it as a flag. Session key = 32B `SecureRandom`, wrapped immediately, held in `SecureBytes`, closed on every path incl. failure.
- **Public-key validation BEFORE the DB read** (fail in <1s): reject non-RSA, modulus ≠ 4096, malformed SPKI PEM → `PUBLIC_KEY_INVALID`. Validate uploadId↔key consistency from the combined handshake file.
- **Air-gap is a tested invariant** — zero outbound sockets except the single JDBC connection; no telemetry/update-check/callback. Assert via a no-egress test + a minimal dep tree (picocli, mssql-jdbc, tink). Document as the customer-trust statement.
- **No plaintext bundle at rest** — the only output shape is the encrypted ALPF file; pre-encrypt temp files are ciphertext-only or shredded (`0600`, deleted in `finally`). No `--no-encrypt`/test mode (A1 — rejected in S-139a); reject it at parse. `--dry-run` writes nothing.
- **Log hygiene** — never log the password, creds-in-URL, session/wrapped key, or PII rows; sha256 + counts only.
- **Supply chain (A06)** — pin mssql-jdbc + Tink versions, dependency-scan in the build, publish the SHA-256 checksum with the release so the customer can verify the jar that touches all their PII.

## Test plan

- **Load-bearing gate — crypto-format match.** Encrypt via the jar's (relocated) encrypt path → decrypt via the server's unchanged `TinkMigrationBundleCipher` → byte-identical recovery, plus a structural assert via `BundleHeader.parse` (MAGIC, version 1, uint16 wrappedKeyLen, AES256_GCM_HKDF_4KB body, uploadId AAD). Drift here = S-139a fails opaquely. Add a one-time byte-equality pin that the relocated shared writer reproduces what `MigrationBundleTestFactory` hand-rolled.
- **Module (Testcontainers MSSQL, FLSTest-seeded via `FlsTestSchemaApplier` + `LegacyFixtureSeeder`, Docker-gated, `@EnabledIf(dockerAvailable)`).** Jar bundle-writer against a real legacy DB → manifest entry-0 + schemaVersion + per-stream sha256, per-mapper NDJSON, `legacy_id_map` pgcopy (valid PGCOPY binary), tar ordering + safe names, empty-entity (zero rows still emits an entry + sha over zero bytes).
- **CLI unit (no Docker).** Arg binding, `--dry-run` (no write), `--password` off argv, read-only URL rewrite, exit-code taxonomy, key validation (non-4096/non-RSA/malformed), schema-drift hard-fail.
- **Memory NFR test.** Seed ≥1–2M flight rows, export under `-Xmx256m`; pass = completes + round-trips, peak heap flat across 10K vs 1M rows (the streaming-invariant guard).
- Not duplicated here (S-139a owns): the full jar→server-ingest e2e, binary-level air-gap no-egress, DB write-rejection. Mapper coverage beyond the vertical slice is gated on S-187a.

## Performance plan

- Peak heap = O(AEAD segment 4 KB + JDBC fetch buffer + gzip window), **independent of DB size**. The only way to break the invariant is an accidental full buffer.
- **Stream the JDBC read** — forward-only read-only `ResultSet`, mssql-jdbc adaptive buffering + an explicit `setFetchSize` (~1000); the driver default materializes the whole result set client-side → OOM on a multi-GB flights table. One `SELECT` per entity, no needless `ORDER BY`.
- **Stream the write** — `ResultSet → writeNdjson` row-by-row → tar → gzip → StreamingAead → file, chained, never collected; sha256 via `DigestOutputStream` as written.
- **Manifest ordering (the one real constraint).** Per-entity temp file captures sha256 + count as it streams; then assemble the final tar (manifest first) → encrypt. Rejected: a two-pass SELECT to pre-hash (doubles the DB scan).
- **Trap to ban.** Do NOT reuse parity `ProducerHarness`/`BundleStream` (`byte[]`/`ByteArrayOutputStream`/`readAllBytes` — fixtures-sized). Extend the migration-bundle no-full-buffer ArchUnit rule to `:migration-tool` main (temp files allowed here — the air-gapped customer tool; `ByteArrayOutputStream`/`readAllBytes` on the entity path banned).
- No DB index work (read-only over the customer's DB). GraalVM deferred (bulk export bound by DB read). No latency budget — memory is the measurable NFR.

## Open design questions

Resolved by operator grill (2026-05-31); recorded as **required follow-ups** (cross-story work refine can't do itself):

1. **S-140 follow-up — combined handshake artifact.** S-140's SPA download must change from a bare `.pem` to a combined artifact carrying `uploadId` + `publicKeyPem` (the jar's `--handshake-file`). Needs a new story via `/modernize-decompose` (S-140 is implemented). Without it the jar can't supply the AAD `uploadId` and no bundle decrypts.
2. **Reword S-139 ACs via `/modernize-decompose S-139`** — envelope (ALPF/Tink, not raw-AES); `adr_refs` (drop 0019); `--public-key-file` → `--handshake-file`; add the uploadId-AAD requirement, schema-drift hard-fail, distribution (GitHub Release + SHA-256), and reconcile the jar name vs S-139a.
3. **`depends_on` expanded** to `[S-016, S-140, S-141, S-187a]`. S-187a must complete the `MapperLegacyBindings` registry before the jar can export all entities.
4. **Code-signing deferred** — jarsigner/sigstore provenance is a post-MVP follow-up; the published SHA-256 checksum is the interim integrity control.

<!-- modernize-refine: end -->
