---
id: S-139
title: Legacy FLS export tool — single-file Java JAR (build + CLI + JDBC + bundle writer + hybrid encrypt)
epic: E-15
status: done
started_at: 2026-05-31
done_at: 2026-05-31
shipped_as: vertical-slice
depends_on: [S-016, S-140, S-141, S-187a]
integration_base: integration/migration
refined: true
refined_at: 2026-05-31
refined_specialists: [requirements, solution, qa, security, performance]
context7_last_checked: 2026-05-31
github_issue: 181
github_pr: 182
acceptance:
  - A new Gradle module `alpenflight/migration-tool/` builds a single-file Shadow fat-jar (`alpenflight-export.jar`) that depends on `:migration-bundle` (shared mappers + the relocated crypto envelope).
  - CLI options: `--jdbc-url <url>`, `--user <name>`, `--handshake-file <path>` (the S-140a combined artifact carrying `uploadId` + RSA-4096 public-key PEM), `--output <path>` (default `./alpenflight-export-<timestamp>.enc`, mode 0600, refuses overwrite without `--force`), `--verbose`, `--dry-run` (reads + reports stats; writes nothing). The DB password is read from an interactive prompt / env / stdin — never argv.
  - `mssql-jdbc` bundled; the JAR forces `ApplicationIntent=ReadOnly` onto the URL (adds it when absent, refuses/overrides `ReadWrite`).
  - The plaintext bundle is a `tar.gz` whose entry 0 is `manifest.json` (schema/format version, source DB metadata, generation timestamp, entity policies, club declarations), followed by one NDJSON stream per entity and `legacy_id_map/<ENTITY>.pgcopy` for FULL_PORT entities; SYSTEM_GLOBAL entities ship NDJSON `(legacy_guid, lookup_key)` rows (the server resolves the V2-seed mapping). Every `EntityType` appears in entity policies XOR an `unmappedReason`.
  - The encrypted output is the **ALPF** envelope — `MAGIC "ALPF" + version + uint16 wrappedKeyLen + RSA-OAEP-SHA256-wrapped AES-256 session key + Google Tink StreamingAead (AES256_GCM_HKDF_4KB) body` — produced by the crypto envelope relocated from the server into `migration-bundle`, and decodable byte-for-byte by the server's existing `TinkMigrationBundleCipher`. The bundle binds the handshake `uploadId` as AEAD associated data.
  - A bundle the JAR produces decrypts + ingests through the real server pipeline (verified end-to-end by S-139a). No codepath emits an unencrypted bundle to disk.
  - Schema drift — a mapper-selected source column absent on an older legacy install — hard-fails with a structured `SOURCE_COLUMN_MISSING` error naming the table + column; never a silent skip or NULL.
  - On non-zero exit, stderr emits a structured, documented error code + remediation hint (`JDBC_CONNECT_FAILED`, `PUBLIC_KEY_INVALID`, `SCHEMA_VERSION_MISMATCH`, `DISK_FULL`, `OUTPUT_EXISTS`, …); exit codes are stable per category.
  - Peak heap is independent of DB size (fully streaming pipeline; no whole-bundle buffering) — verified by exporting ≥ 1M rows under a capped heap.
  - Runs on JRE 17+ (ADR 0001). CI builds the fat-jar on tagged releases and publishes it as a GitHub Release artifact with a published SHA-256 checksum; the `migration-tool/` README documents usage, a worked `java -jar` example, and checksum verification.
estimate: L
adr_refs: [0001]
parity_test: migration-tool crypto-format-match IT (jar-encrypt → server TinkMigrationBundleCipher decrypt, byte-identical); full producer→consumer e2e in S-139a
---

## Context

Vision C28 mandates a single-file Java JAR as the legacy export transport. Shipped as a **vertical slice** (operator-chosen — implemented ahead of its `S-187a` dependency, which only gates export *breadth*): the ALPF crypto envelope, the `migration-tool` fat-jar (CLI + streaming bundle writer + ALPF encryptor), and the load-bearing crypto-format-match proof. The remaining ACs (memory NFR, full exit-code taxonomy, schema-drift hard-fail, distribution, MSSQL module IT) are tracked in **[S-139b](S-139b-export-jar-completeness.md)**; mapper breadth beyond the 5-binding slice is **S-187a**.

## Cross-story contracts

- **Produces:** the ALPF crypto envelope relocated into `migration-bundle/.../crypto` (one source of truth — the server decrypt is now the byte-exact inverse of the jar encrypt; the cipher throws a crypto-local `BundleCipherException` mapped back to `BundleIngestErrorCode` at the server ingest boundary, so the shared library stays free of server-ingest vocabulary). `MapperLegacyBindings` in `migration-bundle/src/main` — one SELECT registry shared by the jar and the parity `ProducerHarness`. Entity selection is `KnownMappers` ∩ `MapperLegacyBindings`, so export coverage grows as S-187a fills the registry, no jar change.
- **Consumes:** S-140a's combined handshake artifact (`--handshake-file`: `uploadId` + RSA-4096 `publicKeyPem`); the relocated cipher's `wrapSessionKey` + `newEncryptingStream`.
- **Defers to [S-139b](S-139b-export-jar-completeness.md):** the completeness ACs above, the ArchUnit no-full-buffer guard for `:migration-tool`, the `ManifestModel`↔`BundleManifest` drift guard, and the JRE-version AC conflict (the fat-jar bundles Java-25 `migration-bundle` bytecode, so "JRE 17+" is unmeetable as written).

## Parity scope

Proven here: **crypto-format-match** — the jar's ALPF encrypt path round-trips byte-identical through the server's `TinkMigrationBundleCipher`, uploadId-AAD enforced (`CryptoFormatMatchIT`, no Docker). The full producer→server-ingest e2e is **S-139a** — and is **blocked on a server-side gap S-139b records**: SYSTEM_GLOBAL `(legacy_guid, code)→V2-seed` resolution and `ClubDeclaration` `countryId`/`clubStateId` FK resolution are not yet built, so a jar bundle cannot FK-resolve through the shipped ingest until they land. S-139a is therefore not a pure ProcessBuilder swap.
