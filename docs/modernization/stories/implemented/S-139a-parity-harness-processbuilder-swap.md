---
id: S-139a
title: Parity oracle — full producer→consumer e2e via the real export jar
epic: E-02
status: done
started_at: 2026-05-31
done_at: 2026-05-31
depends_on: [S-139, S-187, S-187a, S-141c]
integration_base: integration/migration
origin: scope-split
origin_story: S-187
refined: true
refined_at: 2026-05-31
refined_specialists: [requirements, solution, qa, performance]
github_issue: 179
github_pr: 180
acceptance:
  - A full producer→consumer e2e IT in the `server` module: the real `:migration-tool` export jar (S-139) reads a seeded MSSQL Testcontainer and writes an encrypted bundle, which is uploaded to the real `/api/v1/migrations/{uploadId}/bundle` endpoint, ingested by the real `MigrationBundleIngestService` into Postgres, and a structural per-entity diff vs the legacy MSSQL passes.
  - The harness mints a real handshake and invokes the jar via `ProcessBuilder` with `--handshake-file` (uploadId + PEM) + `--output <temp.enc>`; the DB password is passed off-argv. No harness-side decrypt — the real ingest pipeline decrypts.
  - The diff is structural (per-entity row/NDJSON content), tolerant of manifest timestamps + gzip/tar framing; the gate is zero row-content delta. (Supersedes the old byte-for-byte assertion, unachievable across the two producer codepaths.)
  - Process-failure path: a non-zero exit + non-empty stderr fails with `producer process failed: <exit>` (surfacing the jar's structured code) before any upload or diff; a `waitFor` timeout + `destroyForcibly` guards a hung child.
  - `-Dparity.producer=process|inprocess` gates the swap; default `inprocess` until the jar is green nightly, then flip. The jar artifact is resolved from the `:migration-tool:shadowJar` task output, never a hardcoded path.
  - Walltime budget unchanged: PR-gated ≤ 5 min, nightly ≤ 30 min at `-Dparity.scale=10`.
estimate: L
adr_refs: [0022]
---

## Context

Scope-split from [S-187](implemented/S-187-migration-parity-oracle-harness.md). S-187's parity harness wired the producer in-process; S-139a swaps in the **real** `migration-tool` export jar so the harness exercises the production producer codepath.

**Shipped here (producer-wrapper slice):** `ExportJarProducer` (spawns the jar; DB password via the `ALPF_DB_PASSWORD` env var, never argv; stderr drained + stdout discarded against pipe deadlock; `waitFor` timeout + `destroyForcibly`; non-zero exit → `producer process failed: <exit>`; zero-exit-but-empty bundle rejected) and `ExportJarLocator` (resolves the jar from the `migration.tool.jar` system property). Both unit-tested via `/bin/sh` stubs in `server/src/test` — no Docker, gates in CI.

**Deferred to [S-139c](S-139c-parity-full-e2e-it-via-real-jar.md):** the frontmatter ACs above describe the Docker-gated full producer→consumer IT (real jar → seeded MSSQL → upload → real ingest → structural diff) + its Gradle `:migration-tool:shadowJar` wiring + the cross-module fixture classpath share. That IT needs an MSSQL container and a migration CI lane (**S-188** — migration is not built/tested in any CI workflow yet), neither available in the dev sandbox. Per ADR 0022 D1: ship + verify the producer half rather than a large unrunnable IT.

## Cross-story contracts

- **Consumes:** S-139's export jar CLI (`--jdbc-url --user --handshake-file --output`, password via `--password-env` default `ALPF_DB_PASSWORD`) + the shared ALPF crypto envelope S-139 relocated into `migration-bundle`.
- **Produces:** `ExportJarProducer` + `ExportJarLocator` (server-test infra) that S-139c's e2e IT composes.

## Load-bearing decisions

- **A1 producer contract.** Real handshake → jar writes the production **encrypted** `.enc` (`--handshake-file` = uploadId + PEM; **not** `--public-key-file`) → the real ingest decrypts. The harness never decrypts. The DB password rides the `ALPF_DB_PASSWORD` env var, never argv (argv leaks to `ps`).
- **One spawn per run** (whole bundle, never per-mapper) — spawn-per-mapper would multiply cold start by N.
- **Failure mapping.** Any non-zero exit → `producer process failed: <exit>` surfacing the jar's stderr, **before** any upload/diff (deliberately stricter than the AC's "exit≠0 AND stderr non-empty" — an exit code alone is dispositive); timeout → `destroyForcibly`; zero-exit-but-empty `.enc` is a distinct failure from a parity-miss.
- **Structural diff, not byte-wise** (S-139c): compare ingested Postgres rows vs legacy MSSQL per-entity; gzip/tar framing + manifest timestamps differ cosmetically across codepaths, so the gate is zero row-content delta.
- **`-Dparity.producer=process|inprocess`** gates the swap; default `inprocess` until the jar is green nightly, then flip (the flip is the AC-closing step in S-139c, not silent).
- Test infra only — no schema/business logic (ADR 0022 D2 holds). `adr_refs` 0019 is stale for this story; 0022 stays.
