---
id: S-139a
title: Parity oracle harness — swap in-process producer for ProcessBuilder invocation of migration-tool-all.jar
epic: E-02
status: todo
depends_on: [S-139, S-187, S-187a]
integration_base: integration/migration
origin: scope-split
origin_story: S-187
refined: true
refined_at: 2026-05-31
refined_specialists: [requirements, solution, qa, performance]
github_issue: 179
github_pr: 180
acceptance:
  - **Producer side spawns `migration-tool-all.jar`.** Replace `ProducerHarness`'s in-process invocation of `Mapper.writeNdjson` with `ProcessBuilder` invoking the shadowJar from S-139. Stdin = legacy connection params; stdout = the tar.gz bundle bytes; stderr → captured for failure reporting.
  - **No behavioural delta** on the same seeded fixture. Existing happy-path round-trip assertions (S-187 + S-187a) pass byte-for-byte under the swapped producer; nightly 10× passes too.
  - **Walltime budget unchanged.** The swap MUST NOT regress PR-gated ≤ 5 min / nightly ≤ 30 min.
  - **Process-failure path** distinguishable from a parity miss: a non-zero exit + non-empty stderr fails the harness with `producer process failed: <exit>` before any diff runs.
estimate: L
adr_refs: [0019, 0022]
---

## Context

Filed from [S-187](S-187-migration-parity-oracle-harness.md) Design notes — "In-process producer is the temporary affordance. Harness wires producer side directly until `:migration-tool:shadowJar` (S-139) lands". Once the JAR exists, the harness should exercise the production codepath end-to-end.

## Cross-story contracts

- **Consumes:** S-139's `migration-tool-all.jar` artifact + producer CLI surface; S-187's `ProducerHarness` + `BundleStream`.
- **Produces:** no new API surface — this swap is internal to the parity harness.

## Notes

- Reference: S-187 Design notes section "Sibling task for S-139 shadowJar swap".
- Add a flag to gate the swap (`-Dparity.producer=process` vs `-Dparity.producer=inprocess`) for the first PR cycle so a producer-side regression can be triaged against the known-good in-process path without reverting.

<!-- modernize-refine: start -->

## Design notes

**Shape (post-grill 2026-05-31 — supersedes the original "internal to the parity harness" framing above).** S-139a is now a full producer→consumer e2e parity IT living in the **`server`** module, not a producer swap inside `migration-bundle`'s parity source set. Chain: real `migration-tool-all.jar` (S-139) reads a seeded MSSQL Testcontainer → writes the production **encrypted** bundle → POST to the real `/api/v1/migrations/{uploadId}/bundle` endpoint → real `MigrationBundleIngestService` decrypts + ingests into Postgres → diff vs legacy MSSQL. It unifies the two half-tests that exist today: `ParityOracleHarnessTest` (real MSSQL + all mappers, but in-process, no crypto, no real services) and `MigrationBundleParityRoundTripIT` (real encrypted upload + real ingest, but a hand-built bundle, no MSSQL/jar).

**Why server-module.** Consumer = the real ingest endpoint (`server`); producer = a built artifact spawned via `ProcessBuilder` (no compile-time dep). `server → migration-bundle` is the correct dependency direction, so a server-side IT can see the relocated crypto, reach the jar, and drive the HTTP endpoint. The `migration-bundle` parity source set is upstream of `server` and cannot.

**Producer contract = A1 (real encrypted output; no test-only jar mode).** Harness mints a real handshake (`/api/v1/migrations/handshake` → `uploadId` + public-key PEM), invokes the jar with `--jdbc-url --user --public-key-file --output <temp.enc>` (password via **stdin/env, not argv** — argv leaks to `ps`), then uploads the `.enc` verbatim. **No harness-side decrypt** — the real ingest decrypts. This is the central correction to the original ACs (which assumed stdin conn-params / stdout raw tar.gz).

**Crypto relocation — owned by S-139, consumed here.** The jar must encrypt with the exact envelope the server decrypts. The pure-JDK envelope (`MigrationBundleCipher` + Tink impl + `BundleHeader` + format consts) relocates from `server` → shared `migration-bundle` so the jar (encrypt) and server (decrypt) share one source of truth. **The relocation is S-139's work** (the jar cannot encrypt otherwise); S-139a only consumes it. This also closes the format-drift gap (S-139's stated `[header][wrapped-key][iv][ciphertext][tag]` vs the implemented `[MAGIC ALPF][version][len][wrapped-key][Tink StreamingAead]`).

**Failure path (AC4).** One spawn per run (whole bundle, never per-mapper). `waitFor` with a timeout below the run budget + `destroyForcibly()` on expiry; stderr drained on a separate thread (full-pipe deadlock guard). `exit≠0 && stderr non-empty` → fail `producer process failed: <exit>` (surface S-139's structured code) **before** any upload/diff. Jar path resolved from the `:migration-tool:shadowJar` task output via a Gradle dependency — never hardcoded; absent jar → loud "run :migration-tool:shadowJar".

**Diff is structural, not byte-wise.** Compare ingested Postgres rows vs legacy MSSQL per-entity (the existing parity diff), not gzip bytes — gzip/tar framing + manifest timestamps differ cosmetically across codepaths. AC2's literal "byte-for-byte" is unachievable; the cutover gate is **zero row-content delta**.

**ADR 0022:** no schema-level business logic (test infra only). `adr_refs` — 0019 (Entity ID strategy) is a stale ref for this story; 0022 stays.

## Edge cases & hidden requirements

- **ACs are unbuildable as written** — reword via `/modernize-decompose` before implement (tracked under Open design questions).
- **Credentials off argv** — pass the DB password via stdin/env; this IT is the reference invocation pattern others copy.
- **Pipe-buffer deadlock** — drain stderr concurrently with `waitFor`; a full stderr pipe wedges the child.
- **Hung child blows the walltime budget** — mandatory `waitFor` timeout + `destroyForcibly`; a wedged JDBC connect must fail attributably, not silently consume the 5/30-min budget.
- **exit=0 but empty/truncated bundle** — a distinct failure from a parity-miss; assert the `.enc` is non-empty before upload.
- **Temp `.enc` lifecycle** — create under `build/`, delete in `finally`; never let plaintext/`.enc` echo into `build/reports/parity/` (the existing `reportsDoNotLeakSeededPii` guard).
- **Manifest contract on the jar** — the jar's manifest (entity policies, `legacy_id_map/*.pgcopy`, per-entity NDJSON framing) must match what `MigrationBundleIngestService` accepts; see `MigrationBundleTestFactory` for the shape today. This is a hard contract back on S-139, far beyond its current ACs.
- **CLUB FULL_PORT vs provisioning** — the existing IT documents a collision (provisioning mints `t_club`; a FULL_PORT CLUB ingest conflicts). A full-fixture e2e hits this → S-141c (not yet a story).

## Security plan

(N/A — test infra. Ephemeral Testcontainers DB + a throwaway per-run RSA keypair; no production auth/PII surface. The only live decision — keep the DB password off argv — is captured under Edge cases.)

## Test plan

- **The IT is the test** — one server-module full-stack e2e: seed MSSQL (LegacyFixtureSeeder) → spawn jar → POST `.enc` → assert Postgres == legacy per-entity. Tagged `slow`, Docker-gated, not wired into `check`.
- **Unit (no Docker):** the `ProcessBuilder` wrapper — arg assembly, exit/stderr capture, AC4 mapping — driven by a stub script (exit-N + stderr). Keeps the new wiring off the container tax.
- **Diff:** structural per-entity row/NDJSON equivalence, tolerant of manifest `generationTimestamp` + gzip/tar framing; zero row-content delta is the gate.
- **`-Dparity.producer=process|inprocess`** triage flag retained; default `inprocess` until the jar is green nightly, then flip (the flip is the last AC-closing step, not silent).
- **Walltime:** assert the producer step against a generous ceiling (catch ~2× regression, not container noise); nightly `-Dparity.scale=10` stays green.
- **Cross-module fixture reach** — LegacyFixtureSeeder/MSSQL must be visible to `server` tests (composite-include exposes only the main artifact today). This is the S-187a cross-module concern — confirm S-187a actually covers the *classpath* share, not just mapper coverage.

## Performance plan

- **Cold start is negligible** — one JVM spawn (~0.5–2 s) per run against a ~65–160 s container+schema floor (+ scale=10 DB read) ≈ sub-1% of the 5-min PR / 30-min nightly budget. No walltime regression from the swap itself.
- **One spawn per run, not per-entity** — the jar emits the whole bundle in one `main()`; never regress to spawn-per-mapper (multiplies cold start by N).
- **Stream, don't buffer** — redirect the jar output to a temp `.enc` file and stream it into the upload; an in-memory `byte[]` of the scale=10 bundle risks harness-JVM OOM.
- **Mandatory `waitFor` timeout** (≈ PR 3 min / nightly 20 min) so a hung child is an attributable failure, not a budget sink.
- No new DB query/index surface — producer SELECTs are read-only and unchanged.

## Open design questions

Resolved by operator grill (2026-05-31); recorded as **required follow-ups** (cross-story work refine can't do itself), not open forks:

1. **Reword S-139a ACs via `/modernize-decompose S-139a`** — current ACs (stdin/stdout raw tar.gz) contradict the A1 contract. Rewrite to: real handshake → jar `--output .enc` + `--public-key-file` → upload to the real endpoint → real ingest → structural diff → process-failure mapping → walltime + `waitFor` timeout.
2. **Re-refine S-139 (expanded) + pull into the cluster** — the jar must emit bundles the real ingest accepts (manifest entity-policies + `legacy_id_map` pgcopy + NDJSON + the shared Tink crypto format) and **owns the crypto relocation** to `migration-bundle`. `integration_base: integration/migration` added to S-139 by this refinement.
3. **Create S-141c** — provisioning-vs-ingest CLUB reconciliation; does not exist as a story. Needs `/modernize-decompose` before the full-fixture e2e can pass.
4. **S-187a scope check** — confirm it covers cross-module test-fixture *classpath* sharing (server tests reaching LegacyFixtureSeeder/MSSQL), not just mapper coverage; widen its ACs if not.

<!-- modernize-refine: end -->
