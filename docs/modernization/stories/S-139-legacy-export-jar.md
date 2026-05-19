---
id: S-139
title: Legacy FLS export tool — single-file Java JAR (build + CLI + JDBC + bundle writer + hybrid encrypt)
epic: E-15
status: todo
depends_on: [S-016]
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
