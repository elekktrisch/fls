---
id: S-139b
title: Legacy export jar — completeness (MSSQL/memory tests, full CLI taxonomy, schema-drift, distribution)
epic: E-15
status: todo
depends_on: [S-139]
integration_base: integration/migration
origin: implementation-followup
origin_story: S-139
estimate: M
adr_refs: [0001]
acceptance:
  - Testcontainers MSSQL module IT (FLSTest-seeded via `FlsTestSchemaApplier` + `LegacyFixtureSeeder`, `@EnabledIf(dockerAvailable)`): the jar bundle-writer against a real legacy DB → manifest entry-0 + schemaVersion, per-mapper NDJSON, `legacy_id_map/<E>.pgcopy` is valid binary PGCOPY, tar ordering + safe names, empty-entity (zero rows still emits an entry).
  - Memory NFR test — seed ≥ 1M flight rows, export under `-Xmx256m`; pass = completes + round-trips, peak heap flat across 10K vs 1M rows.
  - ArchUnit no-full-buffer rule extended to `:migration-tool` main — `ByteArrayOutputStream` / `readAllBytes` banned on the entity path (temp files allowed). The structural guard for the streaming NFR the design notes mandated.
  - Manifest field-compatibility guard — a unit test deserialises the jar's `ManifestModel` JSON through the server's strict (`@JsonIgnoreProperties(ignoreUnknown=false)`) `BundleManifest` shape, so a field-name/order drift fails in-module, not only at the S-139a e2e.
  - Full exit-code taxonomy + structured stderr error codes per category (stable integer codes; mirror server `BundleIngestErrorCode` vocabulary where the user hits the same wall — `PUBLIC_KEY_INVALID`, `SCHEMA_VERSION_MISMATCH` — plus producer-local `JDBC_CONNECT_FAILED`, `DISK_FULL`, `OUTPUT_EXISTS`, …).
  - Schema-drift hard-fail — a mapper-SELECTed column absent on an older legacy install aborts with a structured `SOURCE_COLUMN_MISSING` naming table + column, never a raw `SQLException` / silent NULL.
  - Supply-chain (A06) — pin + CVE-scan the jar deps (mssql-jdbc, Tink, jackson, commons-compress) in the build.
  - Distribution — `migration-tool/` README (worked `java -jar` example + checksum verification); CI builds the fat-jar on tagged releases and publishes it as a GitHub Release artifact with a published SHA-256 checksum.
---

## Context

S-139 shipped as a **vertical slice** (operator-chosen, 2026-05-31): the ALPF crypto envelope relocated to `migration-bundle`, the `:migration-tool` Shadow fat-jar (CLI + streaming bundle writer + ALPF encryptor), and the load-bearing **crypto-format-match proof** (jar encrypt = byte-exact inverse of the server decrypt). This story rolls up the completeness deferred from that slice. The producer pipeline is registry-driven (`KnownMappers` ∩ `MapperLegacyBindings`); mapper breadth beyond the 5-binding slice is **S-187a**, not this story.

## Cross-story contracts

- **Reshapes S-139a (not a pure ProcessBuilder swap).** A jar bundle today cannot FK-resolve through the *shipped* server ingest: SYSTEM_GLOBAL entities ship NDJSON `(legacy_guid, code)` with **no** server-side `(legacy_guid, code)→V2-seed` resolution built (`EntityStreamIngestor` plain-INSERTs SYSTEM_GLOBAL, populates no id-map), and the `ClubDeclaration` `countryId` (raw legacy GUID) + `clubStateId` (synthetic `(0,int)` UUID) FK-violate provisioning's `fk_club_country_id` / `fk_club_club_state_id` (which require V2-seed UUIDs). The passing `MigrationBundleParityRoundTripIT` only works because it hand-feeds real seed UUIDs. **S-139a (or a paired S-141 story) must land that server-side resolution before the producer→consumer e2e can pass.** Operator decision: expand S-139a's scope or file the server-resolution story.
- **JRE-version AC conflict (operator call).** S-139 AC says "Runs on JRE 17+", but the fat-jar bundles `migration-bundle` Java-25 bytecode → it requires JRE 25. Either retarget the migration stack to 17 or correct the AC + README to the real floor (25).

## Notes

- Manifest per-stream sha256 is computed during the streaming write (surfaced via `--verbose` / `--dry-run`) but is **not** in the manifest — the server's strict `BundleManifest` reader rejects unknown fields. Decide whether the manifest grows a checksum field on both producer + server, or the checksum stays a producer-side operator aid.
