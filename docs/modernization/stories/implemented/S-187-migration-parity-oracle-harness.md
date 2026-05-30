---
id: S-187
title: Migration-bundle — parity oracle harness + LegacyFixtureSeeder + MapperVsSchemaCompatibilityTest
epic: E-02
status: done
started_at: 2026-05-30
done_at: 2026-05-30
merged: true
merged_at: 2026-05-30
depends_on: [S-183, S-184, S-185, S-186]
integration_base: integration/migration
origin: scope-split
origin_story: S-183
scope_split: [S-187a, S-139a]
refined: true
refined_at: 2026-05-30
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer, performance-engineer]
context7_last_checked: 2026-05-30
github_issue: 175
github_pr: 176
acceptance:
  - **Parity oracle harness** lives in a dedicated `src/parity/java/` source set under `migration-bundle/` with its own `parityTest` Gradle task; not wired into `check`. MSSQL + Postgres containers driven via the `docker` CLI directly (same pattern `alpenflight/database/extract/` uses for sandbox API-version compatibility). The vertical slice round-trips three identity-group mappers — Country / Language / ClubState (SYSTEM_GLOBAL) and Club / User (FULL_PORT) — end-to-end and asserts byte-identical row counts per Club.
  - **`LegacyFixtureSeeder`** Faker-only, deterministic via `parity.seed` (default 42). Anchors on the canonical FLSTest schema applied by `FlsTestSchemaApplier` — looks up Switzerland from `Countries` via `CountryCodeIso2='CH'`, German from `Languages` via `LanguageKey='de'`, and `ClubStates.ClubStateId=1`. 2 Clubs × 3 Users by default, uniformly truncated to microseconds to remove the MSSQL `datetime2(7)` ↔ Postgres `timestamptz` ambiguity.
  - **`FlsTestSchemaApplier`** applies the canonical FLSTest schema from `flsserver/database/FLSTest/2 alter/` + `3 insert/3 Insert Static Data.sql` (handles UTF-16 BOM, splits on `GO`, skips `USE` / `CREATE DATABASE` / `ALTER DATABASE`, semver-aware ordering). No parallel DDL.
  - **`LegacyIdMapPopulator` + `ForeignKeyRewriter`** are the in-process stand-in for S-141's FK resolution stage. Populator builds `legacy_guid → new_uuid` per SYSTEM_GLOBAL by joining each bundle entry against `t_<entity>.<lookup_col>`; rewriter swaps the column value on FULL_PORT JsonNodes before `Mapper.readEntity` binds.
  - **Reports** under `build/reports/parity/<git-sha>-<seed>-<scale>/{summary.json, report.md, deltas/*.json}`. `summary.json.fkOrphans` emitted as JSON `null` until S-187a wires the walker — downstream tooling distinguishes "measured zero" from "not yet implemented" by keying on the concrete value.
  - **`MapperVsSchemaCompatibilityTest`** lives in `alpenflight/server/`; parametrised over all 28 mappers via the new public `KnownMappers.all()` registry. Asserts `mapper.columns()` ⊆ destination table (via Postgres `information_schema`) + every non-nullable non-defaulted column is bound. Wire-alias `legacy_guid → id` per ADR 0019. Skip set: `legacy_int_id`, `operating_club_id`, `keycloak_sub` on `t_user`, V18 columns on `t_mutation_audit_event`, `id` on the three application-generated-PK mappers (PersonClub / PersonCategoryAssignment / AircraftAircraftState). SYSTEM_GLOBAL reference mappers exempted from the non-nullable coverage rule. Composite-included via `includeBuild("../migration-bundle")`.
  - **Sibling stories filed** under the same `integration_base`: [S-187a](S-187a-parity-harness-remaining-mappers-and-gates.md) carries the remaining 25 mappers + 4 coverage gates + producer-drop reconciliation + two-pass UPDATE + composite `legacy_id_map_location` + FK orphan walk + sampled-value diff + soft-delete invariant + negative-path bundle-reject + mutation-smoke + tenant-isolation invariant + full PII allow-list. [S-139a](S-139a-parity-harness-processbuilder-swap.md) swaps the in-process producer for `ProcessBuilder` invocation of `migration-tool-all.jar` once S-139's shadowJar lands.
estimate: L
adr_refs: [0002, 0003, 0008, 0019, 0022, 0023]
---

## Context

Scope-split from [S-183](implemented/S-183-migration-bundle-mappers-and-parity-oracle.md). Builds the parity oracle that S-141 (ingest), S-139 (export), and every future migration story rely on as the rehearsal mechanism.

Implementation cut to a vertical slice per ADR 0022 D1 — three identity-group mappers exercised end-to-end against the canonical FLSTest schema with in-process FK resolution standing in for S-141's pipeline. The remaining 25 mappers + full S-141 reconciliation + sampled diff + negative-path coverage moved to [S-187a](S-187a-parity-harness-remaining-mappers-and-gates.md). Refinement decisions inherited from S-183's block are not re-stated here; the code carries the implemented rationale.

## Cross-story contracts

- **Consumes:** S-183's `Mapper` / `Manifest` / `LegacyIdMapWriter` + S-184/S-185/S-186 mappers. The canonical FLSTest schema under `flsserver/database/FLSTest/`. The V2 Flyway-migrated Postgres schema as the destination invariant set.
- **Produces:** the round-trip harness + the public `KnownMappers.all()` registry the server-side `MapperVsSchemaCompatibilityTest` consumes. [S-187a](S-187a-parity-harness-remaining-mappers-and-gates.md) and [S-139a](S-139a-parity-harness-processbuilder-swap.md) inherit the harness skeleton.
